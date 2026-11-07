"""Summarize retained CSV/JSON experiments. Quantiles are per run; bars show mean +/- sample SD across repeats."""
import argparse,csv,hashlib,html,json,statistics,sys,os
from pathlib import Path
from datetime import datetime,timezone

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('--dashboard',action='store_true');p.add_argument('--bulkhead',type=Path);args=p.parse_args()
    root=args.directory.resolve();results=json.loads((root/'results.json').read_text(encoding='utf-8'));env=json.loads((root/'environment.json').read_text(encoding='utf-8'))
    rows=[]
    for run in results:
        if 'measured' not in run:continue
        m=run['measured'];snapshot=json.loads((root/(run['label']+'-snapshots.json')).read_text(encoding='utf-8'))
        with (root/(run['label']+'.csv')).open(encoding='utf-8',newline='') as file:raw_csv=list(csv.DictReader(file))
        measured=[row for row in raw_csv if row['kind']=='measured']
        starts=[float(row['started_s']) for row in measured]
        assert len(measured)==m['count']==run['n']
        assert sum(200<=int(row['status'])<400 for row in measured)==m['success']
        assert abs(sorted(float(row['latency_ms']) for row in measured)[__import__('math').ceil(len(measured)*.95)-1]-m['p95_ms'])<.000001
        waits=samples=0
        for before,after in zip(snapshot.get('backend_raw_before',[]),snapshot.get('backend_raw_after',[])):
            waits+=float(after['queueWaitTotalMs'])-float(before['queueWaitTotalMs']);samples+=int(after['queueWaitSamples'])-int(before['queueWaitSamples'])
        raw=[sample for s in snapshot['samples'] for sample in s.get('backend_raw',[])]
        processes=[proc for s in snapshot['samples'] for proc in s.get('processes',{}).values()]
        background=run.get('background-pdf',{})
        rows.append({'experiment':run['experiment'],'repeat':run['repeat'],'strategy':run['strategy'],
            'count':m['count'],'success':m['success'],'success_rps':m['successful_rps'],'attempted_rps':run['attempted_rps'],
            'configured_offered_rps':run.get('configured_offered_rps'),'errors':m['http_errors']+m['client_errors'],'timeouts':m['timeouts'],
            'observed_start_rps':(len(starts)-1)/(max(starts)-min(starts)) if len(starts)>1 else None,
            'proxy_503':m['proxy_rejected_503'],'backend_503':m['backend_http503'],'p50_ms':m['p50_ms'],'p95_ms':m['p95_ms'],'p99_ms':m['p99_ms'],
            'success_p95_ms':m['successful_p95_ms'],'queue_wait_mean_ms':waits/samples if samples else None,
            'peak_backend_heap_bytes':max([int(r['heapUsedBytes']) for r in raw] or [0]),
            'peak_backend_queued':max([int(r['queuedRequests']) for r in raw] or [0]),
            'peak_os_threads':max([r.get('os_thread_count',0) for r in processes] or [0]),
            'peak_working_set_bytes':max([r.get('working_set_bytes',0) for r in processes] or [0]),
            'peak_exchange_sockets':snapshot['after']['transport']['peakSockets'],
            'active_exchange_sockets_after':snapshot['after']['transport']['activeSockets'],
            'background_pdf_count':background.get('count',0),'background_pdf_success':background.get('success',0),
            'background_pdf_errors':background.get('http_errors',0)+background.get('client_errors',0),
            'max_schedule_lag_ms':run.get('max_schedule_lag_ms'),
            'observation_errors':len(snapshot.get('observation_errors',[]))})
    with (root/'runs.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    groups={}
    for row in rows:groups.setdefault((row['experiment'],row['strategy']),[]).append(row)
    summary=[]
    for (experiment,strategy),runs in groups.items():
        item={'experiment':experiment,'strategy':strategy,'repeats':len(runs),'count':sum(r['count'] for r in runs),'errors':sum(r['errors'] for r in runs),'timeouts':sum(r['timeouts'] for r in runs)}
        for key in ('p50_ms','p95_ms','p99_ms','success_p95_ms','success_rps','queue_wait_mean_ms','background_pdf_success','background_pdf_errors','peak_backend_queued'):
            values=[r[key] for r in runs if r[key] is not None]
            item[key]={'mean':statistics.mean(values),'sd':statistics.stdev(values) if len(values)>1 else 0,'min':min(values),'max':max(values)} if values else None
        summary.append(item)
    provenance={'generated_utc':datetime.now(timezone.utc).isoformat(),'started_utc':env['started_utc'],
        'source_digest':hashlib.sha256(json.dumps(env['source'],sort_keys=True).encode()).hexdigest(),
        'results_sha256':hashlib.sha256((root/'results.json').read_bytes()).hexdigest(),
        'conditions':'One Windows host, loopback. Four backend workers; -Xms64m -Xmx192m. Fresh backend JVMs each repeat, fresh proxy per condition/strategy; the same accumulating private database and OS caches persist. 30 warm-up requests. RR/LC/Adaptive order rotated.',
        'latency':'All outcomes, including rejections and timeouts. Mean of per-run quantiles, not pooled percentiles; sample SD across repeats. Success-only p95 retained separately.',
        'load':'Fixed closed-loop concurrency 12 except overload: independently paced 30/100/300 RPS, finite 240 requests and <=96 generator workers. Generator scheduling lag retained. Backend delay is application sleep, not TCP RTT.',
        'limitations':['Single host and shared PostgreSQL; not LAN or production capacity.','Short runs and only three repeats; no confidence interval or causal bottleneck proof.','Resource polling adds matched observer traffic.','Queue wait is cumulative worker-admission mean, not p95 or database lock wait.','OS working set/private bytes are distinct from JVM heap.','PDF background workers are closed-loop; their completed count changes with foreground duration.']}
    status=root/'execution-status.json'
    if status.exists():provenance['execution_status']=json.loads(status.read_text(encoding='utf-8'))
    publishable=provenance.get('execution_status',{}).get('publishable',True)
    (root/'summary.json').write_text(json.dumps({'provenance':provenance,'summary':summary,'failures':[r for r in results if r['experiment']=='failure-recovery']},indent=2),encoding='utf-8')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    strategies=['ROUND_ROBIN','LEAST_CONNECTIONS','ADAPTIVE'];colors=['#167d68','#bc5426','#7054ab'];lookup={(r['experiment'],r['strategy']):r for r in summary}
    plt.rcParams.update({'font.size':10,'figure.dpi':130,'axes.spines.top':False,'axes.spines.right':False})
    def save(fig,name):
        if not publishable:fig.suptitle('CALIBRATION / SUPERSEDED — see execution-status.json',fontsize=10,color='#a63232')
        fig.tight_layout();fig.savefig(root/(name+'.png'));fig.savefig(root/(name+'.svg'));plt.close(fig)
    fig,axes=plt.subplots(1,2,figsize=(12,4.6));experiments=['homogeneous','heterogeneous','slow-backend','mixed']
    for ax,key,title in zip(axes,['p95_ms','success_rps'],['All-outcome p95 (ms)','Successful requests / second']):
        for j,strategy in enumerate(strategies):
            values=[lookup[(e,strategy)][key] for e in experiments]
            ax.bar([i+(j-1)*.24 for i in range(4)],[v['mean'] for v in values],.23,yerr=[v['sd'] for v in values],capsize=3,label=strategy,color=colors[j])
        ax.set_xticks(range(4),['Equal','Unequal delay','One slow','Mixed']);ax.set_title(title);ax.set_ylim(bottom=0);ax.grid(axis='y',alpha=.2)
    axes[0].legend(fontsize=8);save(fig,'algorithm-comparison')
    for prefix,xs,key,title,name in [('overload-rps',[30,100,300],'success_rps','Overload: achieved successful throughput','overload'),('interference-pdf',[0,4,8,16],'p95_ms','Order all-outcome p95 under background PDF workers','interference')]:
        fig,ax=plt.subplots(figsize=(8,4.6))
        for j,strategy in enumerate(strategies):
            values=[lookup[(prefix+str(x),strategy)][key] for x in xs]
            ax.errorbar(xs,[v['mean'] for v in values],yerr=[v['sd'] for v in values],marker='o',capsize=4,label=strategy,color=colors[j])
        ax.set_xlabel('Configured offered RPS' if prefix=='overload-rps' else 'Background PDF workers');ax.set_ylabel('Successful RPS' if key=='success_rps' else 'p95 (ms)');ax.set_title(title);ax.set_ylim(bottom=0);ax.legend(fontsize=8);ax.grid(alpha=.2);save(fig,name)
    def fmt(v):return '—' if v is None else f"{v['mean']:.1f} ± {v['sd']:.1f}"
    table='<div class="tw"><table><thead><tr><th>Điều kiện</th><th>Thuật toán</th><th>p50 ms</th><th>p95 ms</th><th>p99 ms</th><th>Success/s</th><th>Lỗi / tổng</th></tr></thead><tbody>'
    for row in summary:
        table+='<tr>'+''.join('<td>'+html.escape(str(v))+'</td>' for v in [row['experiment'],row['strategy'],fmt(row['p50_ms']),fmt(row['p95_ms']),fmt(row['p99_ms']),fmt(row['success_rps']),f"{row['errors']} / {row['count']}"])+'</tr>'
    table+='</tbody></table></div>'
    fragment=f'<p><strong>Đo ngày {html.escape(env["started_utc"][:10])} UTC · 3 repeat · loopback một máy · dữ liệu đã lưu.</strong></p><p>Trung bình ± độ lệch chuẩn giữa các lượt; latency gồm mọi kết quả. Giá trị nhỏ do từ chối nhanh không chứng minh xử lý tốt hơn. Xem riêng tỷ lệ lỗi và throughput thành công.</p><details><summary>Bảng RR / LC / Adaptive</summary>{table}</details><p class="network-note">Nguồn raw: {html.escape(root.name)}/results.json; SHA-256 <code class="mono">{provenance["results_sha256"]}</code>. Không phải metrics realtime. Chưa thử LAN/loss/jitter.</p>'
    if not publishable:fragment='<p><strong>CALIBRATION / SUPERSEDED. Không dùng bộ dữ liệu này làm kết quả bàn giao cuối; xem execution-status.json.</strong></p>'+fragment
    if args.bulkhead:
        location=args.bulkhead.resolve();ab=json.loads((location/'summary.json').read_text(encoding='utf-8'))
        abtable='<div class="tw"><table><thead><tr><th>Thuật toán</th><th>Order p95 OFF → ON</th><th>PDF p95 OFF → ON</th><th>PDF thành công/s OFF → ON</th><th>PDF lỗi ON / tổng</th></tr></thead><tbody>'
        for strategy in strategies:
            off=next(r for r in ab['summary'] if r['strategy']==strategy and r['background_workers']==16 and not r['enabled'])
            on=next(r for r in ab['summary'] if r['strategy']==strategy and r['background_workers']==16 and r['enabled'])
            values=[strategy,fmt(off['order_p95_ms'])+' → '+fmt(on['order_p95_ms']),fmt(off['pdf_p95_ms'])+' → '+fmt(on['pdf_p95_ms']),fmt(off['pdf_rps'])+' → '+fmt(on['pdf_rps']),f"{on['pdf_errors']} / {on['pdf_count']}"]
            abtable+='<tr>'+''.join('<td>'+html.escape(str(v))+'</td>' for v in values)+'</tr>'
        abtable+='</tbody></table></div>'
        digest=hashlib.sha256((location/'results.json').read_bytes()).hexdigest()
        fragment+='<hr><h3>Bulkhead PDF · A/B đã lưu · mặc định tắt</h3><p>36 lượt, 3 repeat; 240 order/lượt, 0 hoặc 16 PDF worker nền. OFF/ON dùng cùng một build mới, tách khỏi build baseline phía trên. ON: 8 handler PDF, 16 chờ, tối đa 250 ms.</p><details><summary>Đánh đổi giữa order và PDF, trung bình ± SD</summary>'+abtable+'</details><p class="network-note">Không coi giảm p95 order là cải thiện toàn hệ thống nếu PDF chậm hơn, throughput giảm hoặc bị từ chối. Nguồn '+html.escape(location.name)+'/results.json · SHA-256 <code class="mono">'+digest+'</code>.</p>'
    (root/'benchmark-comparison.html').write_text(fragment,encoding='utf-8')
    report='<!doctype html><html lang="vi"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>PBL4 research</title><style>body{max-width:1200px;margin:30px auto;padding:0 20px;font:15px/1.6 system-ui;color:#142033}img{width:100%}.tw{overflow:auto}table{border-collapse:collapse;font-size:12px}td,th{padding:8px;border:1px solid #ddd;text-align:left}code,pre{overflow-wrap:anywhere}</style><h1>Reverse proxy · thí nghiệm đã lưu</h1>'+fragment
    report+=''.join(f'<figure><img src="{name}.png" alt="{name}"></figure>' for name in ('algorithm-comparison','overload','interference'))
    if args.bulkhead:
        relative=Path(os.path.relpath(args.bulkhead.resolve(),root)).as_posix()
        report+='<h2>Bulkhead A/B</h2><p><a href="'+html.escape(relative)+'/report.html">Phương pháp, từng repeat, error và fairness</a></p><img src="'+html.escape(relative)+'/comparison.png" alt="Bulkhead A/B">'
    report+='<h2>Phương pháp và giới hạn</h2><pre style="white-space:pre-wrap">'+html.escape(json.dumps(provenance,indent=2))+'</pre></html>'
    (root/'report.html').write_text(report,encoding='utf-8')
    if args.dashboard:
        if not publishable:raise RuntimeError('Calibration results cannot be bundled as the dashboard experiment report')
        target=Path(__file__).resolve().parents[1]/'src/main/resources/web/benchmark-comparison.html';target.write_text(fragment,encoding='utf-8')
    print('Summarized',len(rows),'runs;',len(summary),'groups:',root/'report.html')
if __name__=='__main__':main()
