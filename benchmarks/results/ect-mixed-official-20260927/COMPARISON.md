# Mixed BEFORE / AFTER

Source: `official-20260927` -> `ect-mixed-official-20260927`.

Same JDK, seed, request/warm-up schedules, rate, clients, config and order within each repetition: verified.
Primary statistics below are medians of 3 run statistics, not pooled percentiles. Queue/inflight are means of the 9 backend/run means; peaks are sampled maxima. Counts are totals over 3 repetitions.

| Phase | Strategy | Success RPS | Error % | Avg ms | p95 ms | p99 ms | Queue mean / max | Inflight mean / max |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| before | ROUND_ROBIN | 44.07 | 18.42 | 828.70 | 2498.03 | 2951.88 | 9.80 / 28 | 12.85 / 33 |
| before | LEAST_CONNECTIONS | 43.80 | 19.25 | 816.64 | 2317.25 | 2745.95 | 8.49 / 28 | 11.80 / 33 |
| before | ADAPTIVE | 42.44 | 19.83 | 883.69 | 2212.97 | 2704.67 | 10.51 / 28 | 13.57 / 33 |
| after | ROUND_ROBIN | 41.41 | 21.92 | 877.47 | 2417.50 | 2897.27 | 10.00 / 28 | 13.08 / 33 |
| after | LEAST_CONNECTIONS | 44.29 | 16.58 | 926.73 | 2487.33 | 2924.81 | 9.04 / 28 | 12.36 / 34 |
| after | ADAPTIVE | 50.20 | 6.92 | 477.75 | 2013.02 | 2640.67 | 6.73 / 28 | 9.31 / 32 |

## Adaptive distribution and failures

| Phase | Received B1 / B2 / B3 | 503 | 504 | Work admission rejections | Lag p95 median ms |
|---|---|---:|---:|---:|---:|
| before | 1256 / 1054 / 1165 | 647 | 111 |  | 161.80 |
| after | 839 / 1072 / 1645 | 128 | 130 | 44 | 127.33 |

| Phase | Kind | Successful / offered | Error % | Avg successful ms | 503 | 504 |
|---|---|---:|---:|---:|---:|---:|
| before | light | 1775 / 2160 | 17.82 | 810.76 | 380 | 5 |
| before | slow | 191 / 360 | 46.94 | 2198.94 | 65 | 104 |
| before | cpu | 876 / 1080 | 18.89 | 803.64 | 202 | 2 |
| after | light | 2121 / 2160 | 1.81 | 368.00 | 36 | 3 |
| after | slow | 173 / 360 | 51.94 | 2256.41 | 62 | 125 |
| after | cpu | 1048 / 1080 | 2.96 | 389.56 | 30 | 2 |

See before-after-runs.csv for paired repetitions and before-after-types.csv for all strategies/classes. Blank BEFORE work-rejection count means this counter did not exist in the original implementation.
Telemetry proxy_work_rejected_total is a proxy-wide counter repeated on each backend row; do not sum those three copies. summary.estimated_work_rejections is its boundary delta.
