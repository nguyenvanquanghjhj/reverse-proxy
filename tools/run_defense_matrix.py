"""Run matched, sequential experiments using the existing benchmark without changing its workload."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--loads',nargs='+',type=int,default=[30,60,90])
    parser.add_argument('--requests',type=int,default=300)
    parser.add_argument('--warmup-requests',type=int,default=100)
    parser.add_argument('--repeats',type=int,default=3)
    args=parser.parse_args()
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT):
        parser.error('Use a clean committed checkout/worktree; dirty runs are not defense evidence')
    if len(set(args.loads))!=len(args.loads) or any(v<=0 for v in args.loads): parser.error('Unique positive loads required')
    args.output=args.output.resolve(); args.output.mkdir(parents=True,exist_ok=False)
    plan=dict(commit=commit,loads=args.loads,requests=args.requests,warmup_requests=args.warmup_requests,
              repeats=args.repeats,seed=20260927,concurrency=64,scenarios=['mixed','heterogeneous'])
    (args.output/'plan.json').write_text(json.dumps(plan,indent=2)+'\n',encoding='utf-8')
    for load in args.loads:
        destination=args.output/f'load-{load}'
        subprocess.run([sys.executable,str(ROOT/'scripts/benchmark.py'),'--java',args.java,'--output',str(destination),
                        '--requests',str(args.requests),'--warmup-requests',str(args.warmup_requests),'--repeats',str(args.repeats),
                        '--rate',str(load),'--concurrency','64','--seed','20260927','--sample-interval','0.5',
                        '--scenarios','mixed','heterogeneous'],cwd=ROOT,check=True)
        subprocess.run([sys.executable,str(ROOT/'scripts/verify_benchmark.py'),str(destination)],cwd=ROOT,check=True)
        metadata=json.loads((destination/'metadata.json').read_text(encoding='utf-8'))
        if metadata['git_dirty'] is not False or metadata['git_commit']!=commit:
            raise ValueError('Source changed while measuring; keep artifacts but do not publish as clean')


if __name__=='__main__': main()
