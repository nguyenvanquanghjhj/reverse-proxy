#!/usr/bin/env python3
"""Generate a proxy config for real host IPs without overwriting application.properties."""
from pathlib import Path
import argparse, ipaddress, sys
ROOT=Path(__file__).resolve().parents[2]
def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--proxy-bind',required=True,help='specific non-public IPv4 bind address or 0.0.0.0')
    ap.add_argument('--backends',nargs=3,required=True,metavar='IP:PORT')
    ap.add_argument('--output',required=True,type=Path)
    a=ap.parse_args()
    ipaddress.IPv4Address(a.proxy_bind)
    backend_entries=[]
    for raw in a.backends:
        host,port=raw.rsplit(':',1)
        ipaddress.IPv4Address(host)
        if not 1<=int(port)<=65535:ap.error('invalid backend port')
        backend_entries.append(f'{host}:{port}:8')
    src=(ROOT/'config/application.properties').read_text(encoding='utf-8')
    lines=[]
    for line in src.splitlines():
        if line.startswith('proxy.bind.host='):line=f'proxy.bind.host={a.proxy_bind}'
        if line.startswith('backend.servers='):line='backend.servers='+','.join(backend_entries)
        if line.startswith('proxy.capability-routing.enabled='):line='proxy.capability-routing.enabled=true'
        lines.append(line)
    out=a.output.resolve()
    if out.exists():ap.error(f'refusing to overwrite {out}')
    out.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(out)
if __name__=='__main__':main()
