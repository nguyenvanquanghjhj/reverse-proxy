"""Matched A/B of the optional PDF bulkhead; normal orders and 16 background PDF workers, three repeats."""
import argparse,hashlib,json,os,statistics,csv
from pathlib import Path
from lab import Lab,ROOT,PORTS,STRATEGIES,BASE,get,store

def analyze(out):
    runs=json.loads((out/'results.json').read_text(encoding='utf-8'));rows=[]
    for run in runs:
        snapshot=json.loads((out/(run['label']+'-snapshots.json')).read_text(encoding='utf-8'))
        order=run['measured'];pdf=run.get('background-pdf',{})
        distribution={f'127.0.0.1:{port}':0 for port in PORTS}
        for kind in (order,pdf):
            for backend,count in kind.get('distribution',{}).items():
                if backend in distribution:distribution[backend]+=count
        counts=list(distribution.values());denominator=len(counts)*sum(v*v for v in counts)
        gate=snapshot['after']['downloadBulkhead']
        row={'strategy':run['strategy'],'repeat':run['repeat'],'background':run['background_workers'],'enabled':run['enabled'],
             'order_p95_ms':order['p95_ms'],'order_rps':order['successful_rps'],'order_errors':order['http_errors']+order['client_errors'],
             'pdf_p95_ms':pdf.get('p95_ms'),'pdf_rps':pdf.get('successful_rps',0),'pdf_count':pdf.get('count',0),
             'pdf_success':pdf.get('success',0),'pdf_errors':pdf.get('http_errors',0)+pdf.get('client_errors',0),
             'bulkhead_rejected':gate['rejected'],'backend_count_balance_jain':sum(counts)**2/denominator if denominator else None,
             'bulkhead_peak_waiters_sampled':max([s['metrics']['downloadBulkhead']['waiting'] for s in snapshot['samples']] or [0])}
        rows.append(row)
    with (out/'runs.csv').open('w',newline='',encoding='utf-8') as file:
        writer=csv.DictWriter(file,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    groups={}
    for row in rows:groups.setdefault((row['strategy'],row['background'],row['enabled']),[]).append(row)
    summary=[]
    for (strategy,background,enabled),group in groups.items():
        result={'strategy':strategy,'background_workers':background,'enabled':enabled,'repeats':len(group),
                'order_errors':sum(r['order_errors'] for r in group),'pdf_errors':sum(r['pdf_errors'] for r in group),'pdf_count':sum(r['pdf_count'] for r in group)}
        for key in ('order_p95_ms','order_rps','pdf_p95_ms','pdf_rps','backend_count_balance_jain'):
            values=[r[key] for r in group if r[key] is not None]
            result[key]={'mean':statistics.mean(values),'sd':statistics.stdev(values) if len(values)>1 else 0} if values else None
        summary.append(result)
    paired=[]
    for strategy in STRATEGIES:
        for background in (0,16):
            pairs=[]
            for repeat in sorted({r['repeat'] for r in rows}):
                off=next(r for r in rows if r['strategy']==strategy and r['background']==background and r['repeat']==repeat and not r['enabled'])
                on=next(r for r in rows if r['strategy']==strategy and r['background']==background and r['repeat']==repeat and r['enabled'])
                pairs.append({'repeat':repeat,'order_p95_change_percent':100*(on['order_p95_ms']/off['order_p95_ms']-1),
                              'order_rps_change_percent':100*(on['order_rps']/off['order_rps']-1),
                              'pdf_rps_change_percent':100*(on['pdf_rps']/off['pdf_rps']-1) if off['pdf_rps'] else None})
            paired.append({'strategy':strategy,'background_workers':background,'pairs':pairs})
    report={'hypothesis':'Limit PDF work before backend admission to reduce competition for shared workers. Trade-offs must include PDF latency/throughput/rejections, not only order p95.',
            'parameters':{'max_active_pdf_handlers':8,'max_waiting_pdf_handlers':16,'max_wait_ms':250,'default':'OFF'},
            'method':'Same Java build for OFF and ON, 30 warm-up orders, 240 measured orders at concurrency 12; 0 or 16 closed-loop PDF workers. Fresh proxy each condition, backend JVMs each repeat. Variant order alternates by repeat, strategy and background level.',
            'balance_metric':'Jain = (sum backend request counts)^2 / (3 * sum squared counts). This measures count balance across backends, not CPU fairness or per-class SLA.',
            'rate_window':'Successful throughput uses completions inside the foreground measurement window; late PDF drains remain in latency/error outcome counts.',
            'limits':['Only three repeats on one Windows host; no LAN deployment.','Same accumulating private schema within the run; OS/PostgreSQL caches persist.','Global PDF cap does not reserve workers on each backend or guarantee capacity after a backend failure.','PDF background load is closed-loop, not independently paced offered RPS.'],
            'summary':summary,'paired':paired}
    (out/'summary.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig,axes=plt.subplots(1,3,figsize=(14,4.6));lookup={(r['strategy'],r['background_workers'],r['enabled']):r for r in summary}
    for ax,key,title in zip(axes,('order_p95_ms','pdf_p95_ms','pdf_rps'),('Order all-outcome p95 (ms)','PDF all-outcome p95 (ms)','Successful PDF / second')):
        for enabled,color,label,offset in ((False,'#7b8a96','OFF',-.18),(True,'#16816b','ON',.18)):
            values=[lookup[(strategy,16,enabled)][key] for strategy in STRATEGIES]
            ax.bar([i+offset for i in range(3)],[v['mean'] if v else 0 for v in values],.35,yerr=[v['sd'] if v else 0 for v in values],capsize=4,label=label,color=color)
        ax.set_xticks(range(3),['RR','LC','Adaptive']);ax.set_title(title);ax.set_ylim(bottom=0);ax.grid(axis='y',alpha=.2)
    axes[0].legend();fig.suptitle('PDF bulkhead A/B: 16 background PDF workers; mean +/- sample SD across repeats');fig.tight_layout();fig.savefig(out/'comparison.png',dpi=140);fig.savefig(out/'comparison.svg');plt.close(fig)
    import html
    table='<table><thead><tr><th>Strategy</th><th>PDF workers</th><th>Gate</th><th>Order p95</th><th>Order/s</th><th>PDF p95</th><th>PDF/s</th><th>Order errors</th><th>PDF errors / all</th></tr></thead><tbody>'
    for row in summary:
        def fmt(key):return 'n/a' if row[key] is None else f"{row[key]['mean']:.1f} +/- {row[key]['sd']:.1f}"
        values=[row['strategy'],row['background_workers'],'ON' if row['enabled'] else 'OFF',fmt('order_p95_ms'),fmt('order_rps'),fmt('pdf_p95_ms'),fmt('pdf_rps'),row['order_errors'],f"{row['pdf_errors']} / {row['pdf_count']}"]
        table+='<tr>'+''.join('<td>'+html.escape(str(v))+'</td>' for v in values)+'</tr>'
    table+='</tbody></table>'
    (out/'report.html').write_text('<!doctype html><meta charset="utf-8"><title>PDF bulkhead A/B</title><style>body{font:15px/1.6 system-ui;margin:30px}img{max-width:100%}table{border-collapse:collapse}td,th{border:1px solid #ddd;padding:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere}.table{overflow:auto}</style><h1>PDF bulkhead: matched A/B</h1><p>Experimental, OFF by default. Mean +/- sample SD across repeats; all-outcome latency. Compare PDF errors and throughput as well as order latency.</p><img src="comparison.png" alt="A/B results"><div class="table">'+table+'</div><pre>'+html.escape(json.dumps(report,indent=2))+'</pre>',encoding='utf-8')

def main():
    parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group(required=True);mode.add_argument('--out',type=Path);mode.add_argument('--analyze',type=Path)
    parser.add_argument('--repeats',type=int,default=3);parser.add_argument('--requests',type=int,default=240);args=parser.parse_args()
    if args.analyze:analyze(args.analyze.resolve());return
    os.chdir(ROOT);store.configure(ROOT/'.env.store');lab=None;results=[]
    try:
        lab=Lab.__new__(Lab);lab.__init__(args.out.resolve());lab.profile((0,0,0))
        (lab.out/'runner.json').write_text(json.dumps({'sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'repeats':args.repeats,'requests':args.requests}),encoding='utf-8')
        for repeat in range(args.repeats):
            if repeat:
                lab.stop('proxy')
                for port in PORTS:lab.stop(str(port))
                for port in PORTS:lab.backend(port)
            order=STRATEGIES[repeat%3:]+STRATEGIES[:repeat%3]
            for strategy_index,strategy in enumerate(order):
                for level_index,background in enumerate((0,16) if repeat%2==0 else (16,0)):
                    for enabled in ((False,True) if (repeat+strategy_index+level_index)%2==0 else (True,False)):
                        label=f'pdf{background}-{repeat+1}-{strategy}-'+('on' if enabled else 'off')
                        extra={'proxy.pdf.bulkhead.max.inflight':'8' if enabled else '0','proxy.pdf.bulkhead.queue.capacity':'16','proxy.pdf.bulkhead.wait.ms':'250'}
                        lab.proxy(strategy,label,extra=extra)
                        gate=get(BASE+'/__proxy/metrics')['downloadBulkhead'];assert gate['enabled']==enabled and gate['capacity']==(8 if enabled else 0)
                        lab.batch(label+'-warmup',['ORDER'],30,6,100+repeat)
                        run=lab.batch(label,['ORDER'],args.requests,12,20261107+repeat,background=background)
                        run.update(strategy=strategy,repeat=repeat+1,enabled=enabled);results.append(run)
                        (lab.out/'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
                        print(label,'order p95',round(run['measured']['p95_ms'],1),'PDF errors',run.get('background-pdf',{}).get('http_errors',0),flush=True)
    finally:
        if lab and hasattr(lab,'children'):lab.close()
    analyze(args.out.resolve());print('A/B complete',args.out,flush=True)
if __name__=='__main__':main()
