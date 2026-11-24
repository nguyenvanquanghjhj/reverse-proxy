"""Verify retained observations and build summary/charts without modifying application files."""
from pathlib import Path
import argparse,collections,csv,hashlib,json,math,statistics,os
os.environ.setdefault("MPLCONFIGDIR",str(Path(__file__).parent/"mplconfig"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT=Path(r"D:\PBL4\evidence\final-validation-20261122\benchmark")
def percentile(values,q):
    values=sorted(values);return round(values[math.ceil(len(values)*q)-1],2) if values else None
def main():
    global ROOT
    parser=argparse.ArgumentParser()
    parser.add_argument("--root",type=Path,default=ROOT)
    ROOT=parser.parse_args().root.resolve()
    results=json.loads((ROOT/"results.json").read_text(encoding="utf-8"))
    execution=json.loads((ROOT/"execution.json").read_text(encoding="utf-8"))
    assert execution["completed"] and len(results)==63
    normalized_configs=set()
    for run in results:
        folder=ROOT/run["name"]
        with (folder/"raw.csv").open(encoding="utf-8",newline="") as f:rows=list(csv.DictReader(f))
        assert len(rows)==run["requests"]
        assert sum(200<=int(r["status"])<400 and not r["client_error"] for r in rows)==run["successful"]
        assert sum(int(r["status"])>=400 for r in rows)==run["http_errors"]
        assert percentile([float(r["latency_ms"]) for r in rows],.95)==run["p95_ms"]
        assert percentile([float(r["latency_ms"]) for r in rows],.99)==run["p99_ms"]
        assert dict(collections.Counter(r["backend_label"] for r in rows))==run["distribution"]
        assert json.loads((folder/"ownership.json").read_text(encoding="utf-8"))["all_stopped"]
        config=(folder/"proxy.properties").read_text(encoding="utf-8")
        normalized_configs.add("\n".join(line for line in config.splitlines()
            if not line.startswith(("proxy.port=","backend.servers=","loadbalancer.strategy="))))
    assert len(normalized_configs)==1, "Unexpected strategy-specific configuration differences"
    groups=[]
    for case in dict.fromkeys(r["case"] for r in results):
        for strategy in ("ROUND_ROBIN","LEAST_CONNECTIONS","ADAPTIVE"):
            trials=[r for r in results if r["case"]==case and r["strategy"]==strategy]
            assert len(trials)==3
            row={"case":case,"strategy":strategy,"repeats":3,"total":sum(r["requests"] for r in trials),
                 "success":sum(r["successful"] for r in trials),"failed":sum(r["failed"] for r in trials)}
            for key in ("mean_ms","p50_ms","p95_ms","p99_ms","successful_rps","attempted_rps","http_error_rate","failure_rate","host_cpu_mean_percent"):
                values=[r[key] for r in trials]
                row[key]={"mean":statistics.mean(values),"sd":statistics.stdev(values),"min":min(values),"max":max(values)}
            row["distribution"]={k:sum(r["distribution"].get(k,0) for r in trials) for k in ("B1","B2","B3","(not selected)")}
            row["dispatched_metrics"]={k:0 for k in ("B1","B2","B3")}
            row["http_errors"]=sum(r["http_errors"] for r in trials)
            row["client_errors"]=sum(r["client_errors"] for r in trials)
            for run in trials:
                before=json.loads((ROOT/run["name"]/"metrics-before.json").read_text(encoding="utf-8"))
                after=json.loads((ROOT/run["name"]/"metrics-after.json").read_text(encoding="utf-8"))
                prior={b["id"]:b for b in before["backends"]}
                labels={f"127.0.0.1:{p}":f"B{i+1}" for i,p in enumerate(run["ports"][:3])}
                for b in after["backends"]:
                    delta=b["dispatched"]-prior[b["id"]]["dispatched"]
                    assert delta>=0
                    row["dispatched_metrics"][labels[b["id"]]]+=delta
            row["p95_runs"]=[r["p95_ms"] for r in trials]
            row["rss_peak_mib"]={k:max(r["sampled_peak_rss_bytes"][k] for r in trials)/1048576 for k in ("proxy","B1","B2","B3")}
            row["sampled_cpu_seconds_mean"]={k:statistics.mean(r["sampled_cpu_seconds"][k] for r in trials) for k in ("proxy","B1","B2","B3")}
            row["recovery"]=[r["recovery"] for r in trials] if case.startswith("D_") else []
            groups.append(row)
    (ROOT/"aggregate.json").write_text(json.dumps(groups,indent=2))
    fields=["case","strategy","total","success","failed","mean_ms","p50_ms","p95_ms","p99_ms","successful_rps","attempted_rps","http_error_rate","failure_rate","B1","B2","B3"]
    with (ROOT/"aggregate.csv").open("w",encoding="utf-8",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader()
        for r in groups:writer.writerow({k:(r[k]["mean"] if isinstance(r.get(k),dict) and "mean" in r[k] else r.get(k,r["distribution"].get(k))) for k in fields})
    cases=list(dict.fromkeys(r["case"] for r in groups))
    labels=["A: light c1","B: load c4","B: load c12","B: load c24","C: unequal","D: failure","E: mixed"]
    names=("ROUND_ROBIN","LEAST_CONNECTIONS","ADAPTIVE");colors=("#3573b9","#d57a28","#3c9164")
    plt.rcParams.update({"font.size":10,"figure.dpi":160,"axes.spines.top":False,"axes.spines.right":False})
    for metric,title,ylabel,filename,scale in (
        ("p95_ms","Latency p95 per trial (mean and sample SD, n=3)","milliseconds","latency-p95",1),
        ("successful_rps","Successful throughput (mean and sample SD, n=3)","successful requests / second","throughput",1),
        ("http_error_rate","HTTP error rate (mean and sample SD, n=3)","HTTP errors / attempts (%)","http-error-rate",100),
    ):
        fig,ax=plt.subplots(figsize=(12,5))
        for j,strategy in enumerate(names):
            rows=[next(r for r in groups if r["case"]==case and r["strategy"]==strategy) for case in cases]
            ax.bar([i+(j-1)*.25 for i in range(len(cases))],[r[metric]["mean"]*scale for r in rows],.25,
                   yerr=[r[metric]["sd"]*scale for r in rows],capsize=3,color=colors[j],label=strategy)
        ax.set_xticks(range(len(cases)),labels);ax.set_ylabel(ylabel);ax.set_title(title);ax.set_ylim(bottom=0)
        ax.legend(fontsize=8);ax.grid(axis="y",alpha=.2);fig.tight_layout()
        for ext in ("png","svg"):fig.savefig(ROOT/(filename+"."+ext))
        plt.close(fig)
    manifest={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(ROOT.rglob("*")) if p.is_file() and p.name!="manifest.json"}
    (ROOT/"manifest.json").write_text(json.dumps(manifest,indent=2))
    print(json.dumps({"verified_trials":len(results),"measured_requests":sum(r["requests"] for r in results),
                      "warmup_requests":sum(r["warmup_requests"] for r in results),"aggregate_rows":len(groups)}))
    for g in groups:
        print(g["case"],g["strategy"],"success",str(g["success"])+"/"+str(g["total"]),
              "p95",round(g["p95_ms"]["mean"],2),"+/-",round(g["p95_ms"]["sd"],2),
              "rps",round(g["successful_rps"]["mean"],2),"+/-",round(g["successful_rps"]["sd"],2),
              "errors",g["failed"])
if __name__=="__main__":main()

