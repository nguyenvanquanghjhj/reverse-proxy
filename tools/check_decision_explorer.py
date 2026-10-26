"""Real Edge + live HTTP for the explorer; synthetic render fixtures exercise exceptional UI states only."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import socket
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from benchmark import Cluster, request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        management_port = listener.getsockname()[1]
    from playwright.sync_api import sync_playwright
    with Cluster(args.output / 'runtime', java=args.java, overrides={
            'adaptive.decision-trace.enabled': 'true', 'adaptive.warmup.ms': '0',
            'proxy.management.enabled': 'true', 'proxy.management.port': management_port}) as cluster:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel='msedge', headless=True)
            page = browser.new_page(viewport={'width': 1400, 'height': 1100})
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(f'http://127.0.0.1:{cluster.port}/__proxy/dashboard')
            page.wait_for_function('document.getElementById("state").className==="ok"')
            before = sum(b['dispatched'] for b in cluster.monitor()['backends'])
            page.locator('#decision-send').click()
            page.wait_for_function('document.getElementById("decision-request").textContent.includes("HTTP 200")')
            raw = page.locator('#decision-json').text_content()
            assert raw.startswith('{'), dict(note=page.locator('#decision-note').inner_text(),
                    request=page.locator('#decision-request').inner_text(), page_errors=errors)
            live = json.loads(raw)
            actual = json.loads(request(cluster.port, '/__proxy/decision')[2])
            assert live == actual, 'UI numbers must exactly match live management JSON'
            assert live['lastDecision']['route'] == 'LIGHT' and live['lastDecision']['units'] == 2
            assert sum(b['dispatched'] for b in cluster.monitor()['backends']) == before + 1, 'Only explicit click sends workload'
            assert page.locator('#candidates [data-selected="true"]').count() == 1
            assert page.locator('#candidates [data-selected="true"]').get_attribute('data-backend') == live['lastDecision']['selectedBackend']
            assert page.locator('#candidates tr').first.locator('td').count() == 7, 'ECT has its own column'
            assert 'không coi request ID' in page.locator('#decision-request').inner_text()
            request(cluster.port, '/work?cost=2')
            page.locator('#refresh').click()
            assert json.loads(page.locator('#decision-json').text_content()) == live, 'Frozen snapshot must survive new traffic'
            page.screenshot(path=str(args.output / 'explorer-live.png'), full_page=True)
            (args.output / 'live-decision.json').write_text(json.dumps(live, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

            # Render fixtures are deliberately synthetic, never used as measured evidence or screenshots.
            fixture = deepcopy(live)
            fixture['lastDecision']['reason'] = 'EXPLORATION'
            page.evaluate('d=>decision(d)', fixture)
            assert 'không phải lựa chọn minimum' in page.locator('#decision-explanation').inner_text()
            fixture['lastDecision']['reason'] = 'NO_ELIGIBLE_BACKEND'
            fixture['lastDecision']['selectedBackend'] = None
            for candidate in fixture['lastDecision']['candidates']:
                candidate['eligibility'] = 'WORK_BUDGET'
            page.evaluate('d=>decision(d)', fixture)
            assert page.locator('#decision-selection').inner_text() == 'Không cấp lease'
            assert page.locator('[data-selected="true"]').count() == 0
            assert 'Vượt ngân sách' in page.locator('#candidates').inner_text()
            fixture = deepcopy(live)
            fixture['lastDecision']['selectedBackend'] = fixture['lastDecision']['candidates'][1]['backend']
            excluded = fixture['lastDecision']['candidates'][0]
            excluded['eligibility'] = 'DOWN'
            for key in ('serviceMs', 'waitMs', 'completionMs', 'outstandingMs', 'resourcePenalty', 'rankingScoreMs'):
                excluded[key] = None
            page.evaluate('d=>decision(d)', fixture)
            assert page.locator('[data-eligibility="DOWN"] td').all_inner_texts()[2:] == ['—'] * 5
            page.evaluate('decision({enabled:false,lastDecision:null})')
            assert page.locator('#candidates tr').count() == 0 and page.locator('#decision-selection').inner_text() == '—'

            # Delayed polling may complete after a click freezes a different snapshot.
            page.evaluate('''()=>{window.originalFetch=window.fetch;window.fetch=(url,options)=>
                url==='/__proxy/decision'?new Promise(resolve=>window.resolveDecision=resolve):window.originalFetch(url,options);
                freeze(false);window.pendingPoll=poll();}''')
            page.wait_for_function('typeof window.resolveDecision==="function"')
            page.evaluate('''async d=>{freeze(true);decision(d);window.resolveDecision(new Response(JSON.stringify({enabled:false,lastDecision:null})));
                await window.pendingPoll;window.fetch=window.originalFetch;}''', live)
            assert json.loads(page.locator('#decision-json').text_content()) == live, 'Late poll overwrote frozen snapshot'

            # Failed trace fetch must clear old selection/formula rather than present it as current.
            page.route('**/__proxy/decision', lambda route: route.abort())
            page.evaluate('async()=>{freeze(false); await poll();}')
            page.wait_for_function('document.getElementById("decision-note").textContent.includes("Không đọc được")')
            assert page.locator('#decision-formula').inner_text() == ''
            page.unroute('**/__proxy/decision')
            page.evaluate('async()=>{freeze(false); await poll();}')
            page.goto(f'http://127.0.0.1:{management_port}/__proxy/dashboard')
            assert page.locator('#decision-send').is_disabled(), 'Management listener remains read-only'
            assert not errors, errors
            browser.close()
    (args.output / 'verification.json').write_text(json.dumps(dict(
        live_http=True, synthetic_render_states=['exploration', 'admission rejection', 'DOWN/null', 'trace disabled'],
        frozen_snapshot=True, fetch_error_clears_old_values=True, management_read_only=True,
        page_errors=errors), indent=2) + '\n', encoding='utf-8')
    print('PASS: live explorer HTTP/trace, seven columns, selected backend, freeze, exploration/rejection/exclusion fixtures, failure clearing, management isolation')


if __name__ == '__main__':
    main()
