#!/usr/bin/env python3
"""Read-only remote readiness/health checks, with bounded timeouts."""
import argparse, ipaddress, urllib.request, urllib.error, json, os, sys

def main():
    a=argparse.ArgumentParser()
    a.add_argument('--backends',required=True,nargs=3,metavar='IP:PORT')
    opts=a.parse_args()
    token=os.environ.get('PBL4_PROBE_TOKEN','')
    if len(token)<32:a.error('Set PBL4_PROBE_TOKEN in the environment (32+ characters)')
    failures=0
    for target in opts.backends:
        host,port=target.rsplit(':',1);ipaddress.IPv4Address(host)
        req=urllib.request.Request(f'http://{target}/ready',headers={'X-PBL4-Probe-Token':token})
        try:
            with urllib.request.urlopen(req,timeout=2) as response:
                data=json.load(response)
                print(target,response.status,data.get('status'),data.get('dependencies',{}).get('capabilities',{}))
        except (OSError,urllib.error.HTTPError,ValueError) as exc:
            failures+=1; print(target,'NOT READY',type(exc).__name__)
    if failures:sys.exit(1)
if __name__=='__main__':main()
