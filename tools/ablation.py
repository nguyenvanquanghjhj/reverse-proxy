"""Matched Adaptive ablation in private source copies; production Java/config and historical data stay unchanged."""
import argparse
import csv
import difflib
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ('full', 'no-work-admission', 'no-exploration')
PATCHES = {
    'no-work-admission': ('com/example/proxy/loadbalancer/Dispatcher.java',
                     'if (overBudget && !relearn) {',
                     'if (false && overBudget && !relearn) { // ablation only'),
    'no-exploration': ('com/example/proxy/loadbalancer/AdaptiveLoadBalancer.java',
                       'boolean exploring = ++dispatches % explorationInterval == 0;',
                       'boolean exploring = false; // ablation only'),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def patch_source(variant, sources):
    result = dict(sources)
    if variant != 'full':
        relative, before, after = PATCHES[variant]
        text = result[relative].decode('utf-8')
        if text.count(before) != 1:
            raise ValueError('Ablation anchor changed; review the patch instead of silently modifying other code')
        result[relative] = text.replace(before, after).encode('utf-8')
    return result


def rows(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream))


def write_csv(path, values):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(values[0]))
        writer.writeheader(); writer.writerows(values)


def summarize(root, plan):
    result = []
    for variant in VARIANTS:
        runs = [root / run['path'] for run in plan['runs'] if run['variant'] == variant]
        per_kind = []
        for kind in ('overall', 'light', 'slow', 'cpu'):
            count = successful = work_rejections = 0
            percentiles, rates = [], []
            for run in runs:
                requests = rows(run / '1-mixed-adaptive/requests.csv')
                selected = [r for r in requests if kind == 'overall' or r['kind'] == kind]
                good = sorted(float(r['latency_ms']) for r in selected if 200 <= int(r['status']) < 300)
                count += len(selected); successful += len(good)
                if good:
                    percentiles.append(good[math.ceil(.95 * len(good)) - 1])
                summary = rows(run / 'summary.csv')[0]
                if kind == 'overall':
                    rates.append(float(summary['success_rps']))
                    work_rejections += int(float(summary['estimated_work_rejections']))
            record = dict(variant=variant, kind=kind, requests=count, successes=successful, errors=count-successful,
                          success_rate=successful/count, p95_success_ms_median=statistics.median(percentiles) if percentiles else '',
                          success_rps_median=statistics.median(rates) if rates else '',
                          work_rejections=work_rejections if kind == 'overall' else '')
            result.append(record)
            if kind != 'overall':
                per_kind.append(record['success_rate'])
        # Gap compares request-class success probabilities, not counts or execution time shares.
        result[-4]['kind_success_rate_gap'] = max(per_kind)-min(per_kind)
    for record in result:
        record.setdefault('kind_success_rate_gap', '')
    return result


def verify(root):
    root = root.resolve()
    plan = json.loads((root / 'plan.json').read_text(encoding='utf-8'))
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    actual_files = {p.relative_to(root).as_posix() for p in root.rglob('*')
                    if p.is_file() and p != root / 'manifest.json' and '__pycache__' not in p.parts}
    if actual_files != set(manifest):
        raise ValueError('Ablation artifact set mismatch')
    for relative, sha in manifest.items():
        path = root / relative
        if not path.resolve().is_relative_to(root) or path.is_symlink() or digest(path) != sha:
            raise ValueError('Ablation artifact hash mismatch: ' + relative)
    if not (plan['repeats'] == 3 and len(plan['runs']) == 9):
        raise ValueError('Expected three matched repeats and three variants')
    baseline = {p.relative_to(root / 'inputs/full/src/main/java').as_posix(): p.read_bytes()
                for p in (root / 'inputs/full/src/main/java').rglob('*.java')}
    seen = set()
    for entry in plan['runs']:
        key = (entry['repeat'], entry['variant'])
        if key in seen or entry['variant'] not in VARIANTS:
            raise ValueError('Duplicate/unknown variant run')
        seen.add(key)
        run = root / entry['path']
        metadata = json.loads((run / 'metadata.json').read_text(encoding='utf-8'))
        if (metadata.get('ablation_variant') != entry['variant'] or metadata['git_commit'] != plan['base_commit']
                or metadata['git_dirty'] is not False or metadata['seed'] != plan['seed']+entry['repeat']-1
                or metadata['requests'] != plan['requests'] or metadata['warmup_requests'] != plan['warmup_requests']
                or metadata['offered_rps'] != plan['rate'] or metadata['concurrency'] != plan['concurrency']):
            raise ValueError('Ablation source/schedule/settings mismatch')
        expected = patch_source(entry['variant'], baseline)
        actual = {p.relative_to(run / 'source').as_posix(): p.read_bytes() for p in (run / 'source').rglob('*.java')}
        if actual != expected:
            raise ValueError('Unexpected experimental source change')
        subprocess.run([sys.executable, str(run / 'verify_benchmark.py'), str(run)], check=True)
    if { (r, v) for r in (1,2,3) for v in VARIANTS } != seen:
        raise ValueError('Missing matched variant')
    for repeat in (1,2,3):
        group = [root / e['path'] for e in plan['runs'] if e['repeat'] == repeat]
        if len({(p / 'schedule-mixed-1.csv').read_bytes() for p in group}) != 1:
            raise ValueError('Variants did not receive an identical schedule')
    stored = rows(root / 'summary.csv')
    expected_summary = summarize(root, plan)
    if len(stored) != len(expected_summary) or any(
            str(e[k]) != s[k] for s,e in zip(stored,expected_summary) for k in e):
        raise ValueError('Ablation summary does not match raw requests')
    print('PASS: ablation hashes, only intended source patches, matched seeds/schedules, frozen raw validators and per-kind summary')


def verify_archive(directory):
    directory = directory.resolve()
    manifest = json.loads((directory / 'archive-manifest.json').read_text(encoding='utf-8'))
    if set(manifest['files']) != {'raw-artifacts.zip', 'summary.csv', 'plan.json'}:
        raise ValueError('Unexpected exported artifact set')
    for name, sha in manifest['files'].items():
        if digest(directory / name) != sha:
            raise ValueError('Exported ablation hash mismatch: '+name)
    from defense_artifacts import unpack
    with tempfile.TemporaryDirectory(prefix='pbl4-ablation-') as temporary:
        root = Path(temporary)
        unpack(directory / 'raw-artifacts.zip', root)
        for name in ('summary.csv', 'plan.json'):
            if (root / name).read_bytes() != (directory / name).read_bytes():
                raise ValueError('Exported table/plan differs from archive')
        subprocess.run([sys.executable, str(root / 'ablation.py'), '--verify', str(root)], check=True)
    print('PASS: ablation ZIP SHA, safe extraction, frozen verification and exported summary/plan')


def export(root, destination):
    if destination.is_relative_to(root):
        raise ValueError('Export outside the raw experiment folder')
    destination.mkdir(parents=True, exist_ok=False)
    for name in ('summary.csv', 'plan.json'):
        shutil.copyfile(root / name, destination / name)
    with zipfile.ZipFile(destination / 'raw-artifacts.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(root.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:
                if path.is_symlink():
                    raise ValueError('Linked raw artifact')
                archive.write(path, path.relative_to(root).as_posix())
    manifest = dict(files={p.name:digest(p) for p in destination.iterdir()},
                    interpretation='Independent small ablation; not merged with historical benchmarks')
    (destination / 'archive-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    verify_archive(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', type=Path)
    parser.add_argument('--verify-archive', type=Path)
    parser.add_argument('--package', type=Path, help='With --verify, export a verified raw run to a new evidence folder')
    parser.add_argument('--java')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--requests', type=int, default=300)
    parser.add_argument('--warmup-requests', type=int, default=100)
    parser.add_argument('--rate', type=float, default=60)
    args = parser.parse_args()
    if args.verify_archive:
        verify_archive(args.verify_archive); return
    if args.verify:
        verify(args.verify)
        if args.package:
            export(args.verify.resolve(), args.package.resolve())
        return
    if not args.java or not args.output:
        parser.error('--java and --output required')
    if args.requests < 10 or args.requests % 10 or args.warmup_requests < 10 or args.warmup_requests % 10 or not math.isfinite(args.rate) or args.rate <= 0:
        parser.error('Use positive finite rate and request counts divisible by 10')
    if subprocess.check_output(['git','status','--porcelain'], cwd=ROOT):
        parser.error('Commit source first; ablation requires a clean checkout')
    commit = subprocess.check_output(['git','rev-parse','HEAD'], cwd=ROOT, text=True).strip()
    root = args.output.resolve()
    if not root.is_relative_to(ROOT / 'benchmarks/results'):
        parser.error('Use a new folder under ignored benchmarks/results')
    root.mkdir(parents=True, exist_ok=False)
    source = {p.relative_to(ROOT / 'src/main/java').as_posix(): p.read_bytes() for p in (ROOT / 'src/main/java').rglob('*.java')}
    runner = (ROOT / 'scripts/benchmark.py').read_text(encoding='utf-8')
    anchor = 'STRATEGIES = ("ROUND_ROBIN", "LEAST_CONNECTIONS", "ADAPTIVE")'
    if runner.count(anchor) != 1:
        raise ValueError('Review benchmark strategy anchor')
    for variant in VARIANTS:
        checkout = root / 'inputs' / variant
        patched = patch_source(variant, source)
        patch = []
        for relative, data in patched.items():
            path = checkout / 'src/main/java' / relative
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
            if data != source[relative]:
                patch.extend(difflib.unified_diff(source[relative].decode().splitlines(True), data.decode().splitlines(True),
                                                fromfile='base/'+relative, tofile=variant+'/'+relative))
        (checkout / 'config').mkdir(); (checkout / 'scripts').mkdir()
        shutil.copyfile(ROOT / 'config/application.properties', checkout / 'config/application.properties')
        shutil.copyfile(ROOT / 'scripts/verify_benchmark.py', checkout / 'scripts/verify_benchmark.py')
        experimental_runner = runner.replace(anchor, 'STRATEGIES = ("ADAPTIVE",)').replace(
            'metadata = {"schema_version": 2,', 'metadata = {"ablation_variant": '+repr(variant)+', "schema_version": 2,')
        (checkout / 'scripts/benchmark.py').write_text(experimental_runner, encoding='utf-8')
        (checkout / 'source.patch').write_text(''.join(patch), encoding='utf-8')
    plan = dict(base_commit=commit, purpose='Small matched Adaptive ablation; experimental source copies only',
                requests=args.requests, warmup_requests=args.warmup_requests, rate=args.rate, concurrency=64,
                seed=20260927, repeats=3, scenario='mixed', variants=list(VARIANTS), runs=[],
                limitations='One host, short runs; success-only p95 is median of per-run percentiles; not a production guarantee')
    for repeat in range(1,4):
        order = VARIANTS[repeat-1:] + VARIANTS[:repeat-1]
        for position, variant in enumerate(order, 1):
            relative = f'runs/{repeat}-{variant}'
            plan['runs'].append(dict(repeat=repeat, variant=variant, position=position, path=relative))
    (root / 'plan.json').write_text(json.dumps(plan, indent=2)+'\n', encoding='utf-8')
    shutil.copyfile(__file__, root / 'ablation.py')
    for entry in plan['runs']:
        variant = entry['variant']
        print('ABLATION', entry, flush=True)
        subprocess.run([sys.executable, str(root/'inputs'/variant/'scripts/benchmark.py'), '--java', str(Path(args.java).resolve()),
                        '--output', str(root/entry['path']), '--requests', str(args.requests), '--warmup-requests', str(args.warmup_requests),
                        '--rate', str(args.rate), '--concurrency', '64', '--seed', str(plan['seed']+entry['repeat']-1),
                        '--repeats', '1', '--scenarios', 'mixed'], check=True, cwd=ROOT)
    write_csv(root / 'summary.csv', summarize(root, plan))
    manifest = {p.relative_to(root).as_posix(): digest(p) for p in sorted(root.rglob('*'))
                if p.is_file() and '__pycache__' not in p.parts}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    verify(root)


if __name__ == '__main__':
    main()
