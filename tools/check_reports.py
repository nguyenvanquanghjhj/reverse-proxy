"""Check published numbers, manifests, SVG/PNG/HTML outputs and local Markdown links."""
import argparse
import hashlib
from html.parser import HTMLParser
import math
import json
from pathlib import Path
import re
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile
import generate_charts as charts

ROOT=Path(__file__).resolve().parents[1]


class Report(HTMLParser):
    def __init__(self):
        super().__init__(); self.images=[]; self.rows=[]; self.row=None; self.cell=None
    def handle_starttag(self,tag,attrs):
        if tag=='img': self.images.append(dict(attrs)['src'])
        if tag=='tr': self.row=[]
        if tag in ('td','th'): self.cell=''
    def handle_data(self,data):
        if self.cell is not None: self.cell+=data
    def handle_endtag(self,tag):
        if tag in ('td','th'): self.row.append(self.cell); self.cell=None
        if tag=='tr': self.rows.append(self.row); self.row=None


def check_report(bundle,output):
    _,runs,_,_=charts.load_bundle(bundle); expected=charts.summarize(runs)
    actual=charts.read_csv(output/'overall-summary.csv')
    charts.require(len(actual)==len(expected),'Wrong strategy summary count')
    for stored,wanted in zip(actual,expected):
        for key,value in wanted.items():
            same=stored[key]=='' if value is None else stored[key]==value if isinstance(value,str) else math.isclose(float(stored[key]),value,rel_tol=1e-10)
            charts.require(same,f'Published summary differs: {key}')
    manifest=json.loads((output/'manifest.json').read_text(encoding='utf-8'))
    charts.require(manifest['generator_sha256']==charts.digest(Path(charts.__file__)),'Outdated generator manifest')
    charts.require(manifest['bundle_provenance_sha256']==charts.digest(bundle/'provenance.json'),'Wrong report source')
    for name,digest in manifest['files'].items(): charts.require(charts.digest(output/name)==digest,f'Changed chart/report: {name}')
    report=Report(); report.feed((output/'index.html').read_text(encoding='utf-8'))
    markdown=(output/'overall-summary.md').read_text(encoding='utf-8')
    table_rows=[[cell.strip() for cell in line.strip('|').split('|')] for line in markdown.strip().splitlines() if '---' not in line]
    charts.require(report.rows[:4]==table_rows,'HTML/Markdown numbers differ')
    charts.require(len(report.images)==6,'Wrong chart count')
    for name in report.images:
        ET.parse(output/name)
        charts.require((output/Path(name).with_suffix('.png')).read_bytes().startswith(b'\x89PNG\r\n\x1a\n'),'Invalid PNG')
    return markdown.strip()


def check_links():
    broken=[]
    for path in [ROOT/'README.md',*sorted((ROOT/'docs').rglob('*.md')),*sorted((ROOT/'benchmarks/history').glob('*.md'))]:
        text=re.sub(r'```.*?```','',path.read_text(encoding='utf-8'),flags=re.S)
        for target in re.findall(r'\]\(([^)]+)\)',text):
            target=target.split(' "')[0].strip('<>'); parsed=urllib.parse.urlsplit(target)
            if parsed.scheme or not parsed.path: continue
            resolved=(path.parent/urllib.parse.unquote(parsed.path)).resolve()
            if not resolved.exists(): broken.append(f'{path.relative_to(ROOT)} -> {target}')
    charts.require(not broken,'Broken local links:\n'+'\n'.join(broken))


def check_historical_analysis():
    _,runs,_,_=charts.load_bundle(ROOT/'benchmarks/presentation/mixed-20261021')
    summary=charts.summarize(runs)
    lines=(ROOT/'docs/history/PERFORMANCE_ANALYSIS.md').read_text(encoding='utf-8').splitlines()
    for label,row in zip(('Round Robin','Least Connections','Adaptive'),summary):
        candidates=[[v.strip() for v in line.strip('|').split('|')] for line in lines if line.startswith('| '+label+' |')]
        stored=next(v for v in candidates if len(v)==10)
        fields=('requests','successes','errors','success_rps_median','error_percent','avg_ms_median','p50_ms_median','p95_ms_median','p99_ms_median')
        for cell,key in zip(stored[1:],fields):
            charts.require(math.isclose(float(cell.replace(',','')),row[key],abs_tol=.0051),f'Historical analysis differs: {label}/{key}')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--include-defense',action='store_true'); args=parser.parse_args()
    check_report(ROOT/'benchmarks/presentation/mixed-20261021',ROOT/'docs/assets/charts/mixed-20261021')
    check_historical_analysis()
    # Archive thay bản rời: luôn kiểm tra lại từng byte trong CI.
    history=ROOT/'benchmarks/history'
    manifest=json.loads((history/'20260927-manifest.json').read_text(encoding='utf-8'))
    archive=history/manifest['archive']
    charts.require(charts.digest(archive)==manifest['archive_sha256'],'Sai SHA256 archive lịch sử')
    with zipfile.ZipFile(archive) as zipped:
        charts.require(len(zipped.namelist())==len(set(zipped.namelist())) and
                       set(zipped.namelist())==set(manifest['file_sha256']),'Tập file lịch sử không khớp')
        for name,expected in manifest['file_sha256'].items():
            charts.require(hashlib.sha256(zipped.read(name)).hexdigest()==expected,'Sai SHA256 file lịch sử: '+name)
    if args.include_defense:
        table=check_report(ROOT/'benchmarks/presentation/defense-20261021',ROOT/'docs/assets/charts/defense-20261021')
        readme=(ROOT/'README.md').read_text(encoding='utf-8')
        block=readme.split('<!-- PERFORMANCE_TABLE_START -->')[1].split('<!-- PERFORMANCE_TABLE_END -->')[0].strip()
        charts.require(block==table,'README differs from the published raw-derived report')
        analysis=(ROOT/'docs/DEFENSE_PERFORMANCE.md').read_text(encoding='utf-8')
        charts.require(table in analysis,'Defense analysis differs from report')
        names=dict(zip(charts.STRATEGIES,charts.LABELS)); expected=[]
        fmt=lambda value:f'{float(value):.2f}'
        for r in charts.read_csv(ROOT/'benchmarks/defense/20261021/matrix.csv'):
            scenario='Hỗn hợp' if r['scenario']=='mixed' else 'Khác năng lực'
            cells=[scenario,r['offered_rps'],names[r['strategy']],r['successes'],r['errors'],fmt(r['success_rps_median']),
                   fmt(r['success_rps_min'])+'–'+fmt(r['success_rps_max']),fmt(r['error_percent']),fmt(r['p95_ms_median']),
                   fmt(r['p99_ms_median']),fmt(r['client_lag_p95_ms_median'])]
            expected.append('| '+' | '.join(cells)+' |')
        actual=[line for line in analysis.splitlines() if line.startswith(('| Hỗn hợp |','| Khác năng lực |'))
                and len(line.strip('|').split('|'))==11]
        charts.require(sorted(actual)==sorted(expected),'Defense matrix differs from CSV')
    check_links()
    print('PASS: bundle/statistics, HTML/Markdown/CSV, manifests, six SVG/PNG pairs and local document links')


if __name__=='__main__': main()
