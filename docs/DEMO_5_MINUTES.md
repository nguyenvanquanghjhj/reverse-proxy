# Demo 5 phút: RR → LC → Adaptive → DOWN/phục hồi

Chuẩn bị **trước** giờ trình bày: JDK 21 trong PATH, build/test đã đạt, các cổng
8080/9001–9003 trống và PowerShell mở tại root repo. Xem cài đặt/lỗi thường gặp
ở [DEFENSE_GUIDE](DEFENSE_GUIDE.md). Tắt các tab đang gửi workload để tránh làm
đổi thứ tự RR hoặc ghi đè decision. Các lệnh dưới dùng demo script có sẵn.

Chạy toàn bộ khối trong một PowerShell. Nó giữ bản cấu hình gốc trong bộ nhớ,
bật trace cho demo và khôi phục cấu hình/dừng các tiến trình do script quản lý
trong `finally`, kể cả khi một bước thất bại. Không dùng các kết quả này thay
benchmark lịch sử.

```powershell
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $PWD 'config/application.properties'
$savedConfig = [IO.File]::ReadAllBytes($configPath)
function Demo-Step {
    & powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo.ps1 @args
    if ($LASTEXITCODE -ne 0) { throw 'Demo script failed; inspect .demo logs.' }
}
try {
    Demo-Step stop
    $demoConfig = [Text.Encoding]::UTF8.GetString($savedConfig) -replace '(?m)^adaptive.decision-trace.enabled=false\r?$', 'adaptive.decision-trace.enabled=true'
    [IO.File]::WriteAllText($configPath, $demoConfig, [Text.UTF8Encoding]::new($false))
    Demo-Step start -BindHost 127.0.0.1 -Strategy ROUND_ROBIN

    # 0:00–1:00 — RR: 9001,9002,9003,9001,9002,9003.
    Demo-Step rr
    curl.exe -i 'http://127.0.0.1:8080/work?cost=2'
    # Chỉ URL/header/body: forwarding thật, server body khớp X-Proxy-Backend.

    # 1:00–2:00 — LC: giữ một Slow, rồi gửi Light khi reservation còn sống.
    Demo-Step strategy -Strategy LEAST_CONNECTIONS
    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    try {
        $slow = $client.GetAsync('http://127.0.0.1:8080/slow')
        # Poll điều kiện thật thay vì đoán Slow đã giữ lease.
        $until = [DateTime]::UtcNow.AddSeconds(1.5)
        do {
            $m = Invoke-RestMethod 'http://127.0.0.1:8080/__proxy/metrics'
            $held = @($m.backends | Where-Object inFlight -gt 0)
            if ($held.Count -gt 0) { break }
            Start-Sleep -Milliseconds 30
        } while ([DateTime]::UtcNow -lt $until)
        if ($held.Count -eq 0) { throw 'Slow lease not observed; repeat LC demo.' }
        $held | Select-Object id,inFlight
        curl.exe -i 'http://127.0.0.1:8080/work?cost=2'
        $response = $slow.GetAwaiter().GetResult()
        try { $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
        finally { $response.Dispose() }
    } finally { $client.Dispose() }
    # Light chọn backend khác máy đang giữ Slow; LC tính cả hai loại là 1 lease.

    # 2:00–3:30 — Adaptive: học độ trễ khác nhau rồi giải thích ảnh chụp thật.
    Demo-Step strategy -Strategy ADAPTIVE
    curl.exe -s 'http://127.0.0.1:9002/control?delayMs=240&cpuWorkers=0&healthy=true'
    1..12 | ForEach-Object { curl.exe -s 'http://127.0.0.1:8080/work?cost=2' }
    curl.exe -i 'http://127.0.0.1:8080/work?cost=2'
    curl.exe 'http://127.0.0.1:8080/__proxy/decision'
    # Đọc eligibility, service/wait, resourcePenalty, rankingScoreMs và reason.
    # MINIMUM_SCORE khác EXPLORATION; không khẳng định Adaptive luôn thắng.
    curl.exe -s 'http://127.0.0.1:9002/control?delayMs=20&cpuWorkers=0&healthy=true'

    # 3:30–5:00 — Dừng hẳn B2, loại DOWN, khởi động và nhận request lại.
    Demo-Step health
    # Script tự kiểm DOWN không nhận traffic, đợi WARMING/UP và kiểm B2 nhận lại.
    Demo-Step status
} finally {
    try { Demo-Step stop }
    finally { [IO.File]::WriteAllBytes($configPath, $savedConfig) }
}
```

Khi cần khôi phục một phiên đang chạy thủ công: `backend2-start`, đặt control cả
ba backend về `delayMs=20&cpuWorkers=0&healthy=true`, rồi `strategy -Strategy ADAPTIVE`
để xóa lịch sử scheduler; hoặc `stop` rồi `start`. Dùng cùng cú pháp `demo.ps1`
ở trên. Logs/CSV trong `.demo` được giữ; script chỉ dừng PID của phiên đã ghi.
Demo stop dùng kết thúc tiến trình; graceful shutdown được chứng minh riêng bằng
`LifecycleTest`, không suy ra từ thao tác stop.

Nếu kiểm tra thất bại, giữ log và dừng diễn giải số liệu. Trace có thể bị request
khác ghi đè. [Walkthrough](REQUEST_WALKTHROUGH.md) có một trace thực được lưu sẵn;
[QA](DEFENSE_QA_SHORT.md) nêu giới hạn và câu hỏi thường gặp.
