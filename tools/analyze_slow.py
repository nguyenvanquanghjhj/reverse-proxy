"""Derive Slow outcomes and observed saturation without attributing each 503 to admission."""
import argparse
from collections import Counter
import json
from pathlib import Path
import defense_artifacts as artifacts


def analyze(source):
    metadata=json.loads((source/'metadata.json').read_text(encoding='utf-8')); result={}
    for strategy in ('ROUND_ROBIN','LEAST_CONNECTIONS','ADAPTIVE'):
        folders=sorted(source.glob('*-mixed-'+strategy.lower()))
        rows=[r for folder in folders for r in artifacts.read(folder/'requests.csv') if r['kind']=='slow']
        runs=[]
        for folder in folders:
            bounds=artifacts.read(folder/'boundaries.csv'); samples=artifacts.read(folder/'telemetry.csv')
            before=[r for r in bounds if r['phase']=='before']; after=[r for r in bounds if r['phase']=='after']
            # Global counter is repeated in every backend row: never sum three copies.
            delta=max(int(r['proxy_work_rejected_total']) for r in after)-max(int(r['proxy_work_rejected_total']) for r in before)
            runs.append(dict(run=folder.name,work_rejections_all_request_types=delta,
                             before_states=[r['state'] for r in before],sampled_states=dict(Counter(r['state'] for r in samples)),
                             sampled_max_queue=max(float(r['backend_queued'] or 0) for r in samples)))
        result[strategy]=dict(slow_requests=len(rows),slow_status_counts=dict(Counter(r['status'] for r in rows)),runs=runs)
    return dict(source=source.name,git_commit=metadata['git_commit'],git_dirty=metadata['git_dirty'],
                source_complete_sha256=artifacts.digest(source/'complete.json'),results=result,
                limits='State counts are periodic samples, not duration; work rejection is global/all types; 503 alone cannot identify circuit/health/work cause; trace was off in benchmark')


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True); args=parser.parse_args()
    result=analyze(args.source); args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('x',encoding='utf-8') as stream: json.dump(result,stream,indent=2); stream.write('\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
