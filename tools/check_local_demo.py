"""Optional real Edge smoke test. Requires Playwright only in the verification environment."""
import argparse
from pathlib import Path
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from benchmark import Cluster, request


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--java',required=True)
    parser.add_argument('--output',type=Path,required=True); args=parser.parse_args()
    from playwright.sync_api import sync_playwright
    with Cluster(args.output,java=args.java,overrides={'adaptive.decision-trace.enabled':'true','adaptive.warmup.ms':'0'}) as cluster:
        with sync_playwright() as playwright:
            browser=playwright.chromium.launch(channel='msedge',headless=True)
            context=browser.new_context(viewport={'width':1360,'height':1000}); page=context.new_page(); errors=[]
            page.on('pageerror',lambda error:errors.append(str(error)))
            page.goto(f'http://127.0.0.1:{cluster.port}/demo')
            assert page.locator('#mode').inner_text()=='Qua proxy'
            assert page.locator('#monitor-link').is_visible()
            for index,kind in enumerate(('work','slow','compute','products','download','orders'),1):
                if kind=='products': page.locator('details.more').evaluate('(e)=>e.open=true')
                page.locator(f'[data-kind="{kind}"]').click(); page.locator('#send').click()
                page.wait_for_function('(n)=>document.getElementById("finished").textContent===String(n)',arg=index,timeout=12000)
                assert page.locator('#error-count').inner_text()=='0',page.locator('#result').inner_text()
                assert page.locator('#header-strategy').inner_text()=='Thích nghi'
            page.reload(); assert page.locator('#log tr').count()==6
            page.screenshot(path=str(args.output/'request-studio.png'),full_page=True)
            page.goto(f'http://127.0.0.1:{cluster.ports[0]}/demo')
            assert page.locator('#mode').inner_text()=='Kết nối trực tiếp'
            assert not page.locator('#monitor-link').is_visible()
            page.goto(f'http://127.0.0.1:{cluster.port}/__proxy/dashboard')
            page.wait_for_function('document.getElementById("state").className==="ok"')
            page.wait_for_function('document.querySelectorAll("#backends article").length===3')
            assert page.locator('#strategy').inner_text()=='Thích nghi'
            request(cluster.port,'/work'); page.locator('#refresh').click(); page.wait_for_timeout(2200)
            assert page.locator('#rps').inner_text()!='—'
            assert page.locator('#candidates tr').count()==3
            page.screenshot(path=str(args.output/'dashboard.png'),full_page=True)
            context.set_offline(True); page.locator('#refresh').click(); page.wait_for_timeout(7500)
            assert page.locator('#state').get_attribute('class')=='bad'
            assert 'đã cũ' in page.locator('#state').inner_text(); assert page.locator('#rps').inner_text()=='—'
            context.set_offline(False); page.locator('#refresh').click()
            page.wait_for_function('document.getElementById("state").className==="ok"')
            assert not errors,errors
            browser.close()
    print('PASS: real Edge, six request types, history, strategy headers, direct/proxy, real counters, decision, disconnect/stale/reconnect')


if __name__=='__main__': main()
