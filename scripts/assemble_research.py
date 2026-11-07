"""Assemble corrected scenarios without changing raw observations; require identical Java/resources runtime hashes."""
import argparse,hashlib,json,shutil
from pathlib import Path

UNAFFECTED={'homogeneous','heterogeneous','slow-backend','overload-rps30','overload-rps100','overload-rps300','connections'}
CORRECTED={'mixed','interference-pdf0','interference-pdf4','interference-pdf8','interference-pdf16'}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--original',type=Path,required=True);p.add_argument('--corrected',type=Path,required=True);p.add_argument('--failures',type=Path,required=True);p.add_argument('--out',type=Path,required=True);args=p.parse_args()
    original=args.original.resolve();corrected=args.corrected.resolve();failures=args.failures.resolve();out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    environments=[json.loads((folder/'environment.json').read_text(encoding='utf-8')) for folder in (original,corrected,failures)]
    assert all(e['runtime_sha256']==environments[0]['runtime_sha256'] and e['source']==environments[0]['source'] for e in environments),'Java/source/resource fingerprints differ; do not merge as the same runtime'
    selected=[];origins=[]
    def copy_file(folder,name):
        source=folder/name
        if source.exists():
            destination=out/name;shutil.copyfile(source,destination)
            origins.append({'file':name,'source':str(source),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
    for folder,allowed in ((original,UNAFFECTED),(corrected,CORRECTED)):
        for run in json.loads((folder/'results.json').read_text(encoding='utf-8')):
            if run['experiment'] not in allowed:continue
            run={**run,'origin_directory':folder.name};selected.append(run)
            label=run['label']
            for suffix in ('.csv','-snapshots.json','.properties','-warmup.csv','-warmup-snapshots.json'):copy_file(folder,label+suffix)
    assert len(selected)==108 and len({(r['experiment'],r['repeat'],r['strategy']) for r in selected})==108
    # Retain the original failure JSON plus an explicitly derived overlap-window analysis.
    fault_results=json.loads((failures/'failure-analysis.json').read_text(encoding='utf-8'))
    assert len(fault_results)==9 and all(r['passed'] for r in fault_results)
    for run in fault_results:
        selected.append({**run,'experiment':'failure-recovery','origin_directory':failures.name})
        for suffix in ('.csv','-timeline.json','.properties','-warmup.csv','-warmup-snapshots.json'):copy_file(failures,run['label']+suffix)
    for name in ('tcp-connect.csv','direct-proxy.csv','tcp-before.json'):copy_file(original,name)
    for name in ('pdf-fixture.json','fixture.pdf','sql-summary.json'):copy_file(corrected,name)
    copy_file(failures,'runner.json')
    env={**environments[0],'assembly':{'java_and_resources_identical':True,'original_directory':str(original),'corrected_directory':str(corrected),'failure_directory':str(failures),
        'collector_sha256':[e['runner_sha256'] for e in environments],
        'reason':'Original padded PDF failed strict parsing. Replace all mixed/PDF-interference conditions (including order-only matched control) with validated-PDF reruns. Other 63 comparisons never downloaded that fixture. Failure recovery uses the independent observer rerun; original raw results remain retained.'}}
    (out/'environment.json').write_text(json.dumps(env,indent=2),encoding='utf-8')
    (out/'results.json').write_text(json.dumps(selected,indent=2),encoding='utf-8')
    (out/'origin-manifest.json').write_text(json.dumps(origins,indent=2),encoding='utf-8')
    (out/'execution-status.json').write_text(json.dumps({'publishable':True,'comparative_phase':'PASS: 108 runs x 240 requests, 3 repeats for each of 12 conditions and 3 strategies. All selected runs share identical Java/resource fingerprints.',
        'pdf_fixture':'Validated 245760-byte generated PDF, strict pypdf parser. All scenarios that fetch it and their matched order-only control were rerun.',
        'fault_phase':'PASS: 9 independent-observer process-stop/recovery runs; in-flight failures are counted by overlap with the failure window.',
        'calibration_retained':str(original),'origins':'origin-manifest.json pins the source directory and SHA of every copied raw artifact.',
        'LAN':'NOT RUN: no controlled separate peer or network emulation available. Loopback results do not measure LAN RTT/loss/jitter.'},indent=2),encoding='utf-8')
    print('Assembled',len(selected),'records;',len(origins),'raw artifacts with identical Java runtime')
if __name__=='__main__':main()
