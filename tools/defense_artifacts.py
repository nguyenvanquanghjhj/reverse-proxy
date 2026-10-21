"""Archive and independently verify full clean-source defense experiments plus derived tables."""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path, PurePosixPath
import statistics
import subprocess
import sys
import tempfile
import zipfile


def require(condition,message):
    if not condition: raise ValueError(message)


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    with path.open(encoding='utf-8',newline='') as stream: return list(csv.DictReader(stream))


def write(path,rows):
    with path.open('w',encoding='utf-8',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)


def tables(source):
    plan=json.loads((source/'plan.json').read_text(encoding='utf-8'))
    overview,types,errors=[],[],[]
    for load in plan['loads']:
        directory=source/f'load-{load}'
        metadata=json.loads((directory/'metadata.json').read_text(encoding='utf-8'))
        require(metadata['git_dirty'] is False and metadata['git_commit']==plan['commit'],'Unclean/mixed source snapshot')
        for key in ('requests','warmup_requests','repeats','seed','concurrency','scenarios'):
            require(metadata[key]==plan[key],f'Unmatched {key}')
        require(metadata['offered_rps']==load,'Unmatched load')
        runs=read(directory/'summary.csv'); bytype=read(directory/'request_types.csv')
        for scenario in plan['scenarios']:
            for strategy in ('ROUND_ROBIN','LEAST_CONNECTIONS','ADAPTIVE'):
                group=[r for r in runs if r['scenario']==scenario and r['strategy']==strategy]
                row=dict(offered_rps=load,scenario=scenario,strategy=strategy,runs=len(group))
                for key in ('requests','successes','errors'): row[key]=sum(int(r[key]) for r in group)
                row['error_percent']=100*row['errors']/row['requests']
                for key in ('success_rps','avg_ms','p50_ms','p95_ms','p99_ms','all_outcomes_p95_ms','client_lag_p95_ms'):
                    values=[float(r[key]) for r in group if r[key]!='']
                    for suffix,op in (('median',statistics.median),('min',min),('max',max)):
                        row[key+'_'+suffix]=op(values) if values else ''
                row['client_lag_warning_runs']=sum(r['client_lag_warning']=='True' for r in group)
                overview.append(row)
                for kind in sorted({r['kind'] for r in bytype if r['scenario']==scenario}):
                    subset=[r for r in bytype if r['scenario']==scenario and r['strategy']==strategy and r['kind']==kind]
                    total=sum(int(r['requests']) for r in subset); good=sum(int(r['successes']) for r in subset)
                    types.append(dict(offered_rps=load,scenario=scenario,strategy=strategy,kind=kind,requests=total,
                                      successes=good,errors=total-good,error_percent=100*(total-good)/total))
        for run in runs:
            raw=read(directory/f"{run['repeat']}-{run['scenario']}-{run['strategy'].lower()}"/'requests.csv')
            counts=Counter((r['kind'],r['status'],r['error']) for r in raw)
            for (kind,status,error),count in sorted(counts.items()):
                errors.append(dict(offered_rps=load,scenario=run['scenario'],repeat=run['repeat'],strategy=run['strategy'],
                                   kind=kind,status=status,error=error,count=count))
    return {'matrix.csv':overview,'request-types.csv':types,'status-breakdown.csv':errors}


def validate_raw(source):
    plan=json.loads((source/'plan.json').read_text(encoding='utf-8'))
    directories=[source/f'load-{load}' for load in plan['loads']]
    # Optional historical input is preserved under its own label, never pooled
    # with the clean-source matrix or subjected to a false clean-source claim.
    directories.extend(p for p in sorted((source/'history').glob('*')) if p.is_dir())
    for directory in directories:
        # Use each result's immutable runner and validator, never the installed current runner.
        subprocess.run([sys.executable,str(directory/'verify_benchmark.py'),str(directory)],check=True)


def unpack(archive,destination):
    with zipfile.ZipFile(archive) as zipped:
        members=zipped.infolist(); names=[m.filename for m in members]
        require(len(names)==len(set(names)) and len(names)<10000,'Duplicate/too many archive entries')
        require(sum(m.file_size for m in members)<500_000_000,'Archive too large')
        for member in members:
            path=PurePosixPath(member.filename)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in member.orig_filename
                    and ':' not in member.filename,'Unsafe archive path')
            require((member.external_attr >> 16) & 0o170000 != 0o120000,'Linked archive entry')
            target=(destination/member.filename).resolve()
            require(target.is_relative_to(destination.resolve()),'Archive escapes destination')
        zipped.extractall(destination)


def verify(directory):
    manifest=json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
    require(set(manifest['files'])=={'raw-artifacts.zip','matrix.csv','request-types.csv','status-breakdown.csv'},'Unexpected artifact set')
    for name,expected in manifest['files'].items(): require(digest(directory/name)==expected,f'Hash mismatch: {name}')
    with tempfile.TemporaryDirectory(prefix='pbl4-verify-') as temporary:
        source=Path(temporary); unpack(directory/'raw-artifacts.zip',source)
        validate_raw(source)
        plan=json.loads((source/'plan.json').read_text(encoding='utf-8'))
        require(plan==manifest['plan'],'Plan differs from archive')
        for name,rows in tables(source).items():
            generated=source/name; write(generated,rows)
            require(generated.read_bytes()==(directory/name).read_bytes(),f'Derived table differs: {name}')
    print('PASS: archive SHA256, safe extraction, every frozen raw validator, clean source and all published tables')


def export(source,destination):
    validate_raw(source); results=tables(source)
    destination.mkdir(parents=True,exist_ok=False)
    for name,rows in results.items(): write(destination/name,rows)
    with zipfile.ZipFile(destination/'raw-artifacts.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as zipped:
        for path in sorted(source.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:
                require(not path.is_symlink(),'Linked source artifact')
                zipped.write(path,path.relative_to(source).as_posix())
    manifest=dict(plan=json.loads((source/'plan.json').read_text(encoding='utf-8')),
                  files={p.name:digest(p) for p in sorted(destination.iterdir())},
                  interpretation='Counts sum repeats; RPS/latency median of per-run statistics; all outcomes retained; same host; no causal comparison to history')
    (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    verify(destination)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path); parser.add_argument('--output',type=Path)
    parser.add_argument('--verify',type=Path); args=parser.parse_args()
    if args.verify: verify(args.verify.resolve())
    elif args.source and args.output: export(args.source.resolve(),args.output.resolve())
    else: parser.error('Use --source RAW --output NEW or --verify EXPORTED')


if __name__=='__main__': main()
