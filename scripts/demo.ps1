param(
    [Parameter(Position=0)][ValidateSet('start','basic','rr','adaptive','health','status','strategy','backend2-stop','backend2-start','stop')]
    [string]$Action = 'status',
    [ValidateSet('ROUND_ROBIN','LEAST_CONNECTIONS','ADAPTIVE')][string]$Strategy = 'ADAPTIVE',
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('0.0.0.0','127.0.0.1')][string]$BindHost = '0.0.0.0'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime = Join-Path $repo '.demo'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$stateFile = Join-Path $runtime 'state.json'
$script:state = $null
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(8)
$client.DefaultRequestHeaders.ConnectionClose = $true

function Save-State {
    $temp = Join-Path $runtime 'state.tmp'
    [IO.File]::WriteAllText($temp, ($script:state | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $stateFile -Force
}
function Owned-Process($entry) {
    $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)"
    if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne $entry.startedTicks -or
        $process.Path -ne $script:state.java -or $null -eq $identity -or
        $identity.CommandLine -notlike "*-Dpbl4.demo.session=$($script:state.session)*" -or
        $identity.CommandLine -notlike "*-Dpbl4.demo.role=$($entry.name)*") {
        throw "PID $($entry.pid) does not match the recorded demo process. Refusing to stop/control it."
    }
    return $process
}
function Stop-Owned([string]$name) {
    foreach ($entry in @($script:state.processes | Where-Object name -eq $name)) {
        $process = Owned-Process $entry
        if ($null -ne $process) {
            $process.Kill()
            if (-not $process.WaitForExit(5000)) { throw "Process $name did not exit." }
            $process.Dispose()
        }
    }
    $script:state.processes = @($script:state.processes | Where-Object name -ne $name)
    Save-State
}
function Assert-Free([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start() }
    catch { throw "Port $port is busy. Stop its owner yourself; demo will not kill unrelated processes." }
    finally { $listener.Stop() }
}
function Start-Owned([string]$name, [string[]]$mainArgs, [int]$port) {
    Assert-Free $port
    $log = Join-Path $script:state.directory "$name-$([DateTime]::Now.ToString('HHmmssfff'))"
    $arguments = @("-Dpbl4.demo.session=$($script:state.session)", "-Dpbl4.demo.role=$name", '-Xms32m', '-Xmx128m', '-cp', ('"' + $script:state.jar + '"')) + $mainArgs
    $process = Start-Process -FilePath $script:state.java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput "$log.out.log" -RedirectStandardError "$log.err.log"
    try {
        $entry = [pscustomobject]@{ name=$name; pid=$process.Id; startedTicks=$process.StartTime.ToUniversalTime().Ticks.ToString() }
        $script:state.processes = @($script:state.processes) + @($entry)
        Save-State
    } catch { if (-not $process.HasExited) { $process.Kill() }; throw }
    finally { $process.Dispose() }
}
function Fetch([string]$uri) {
    $response = $client.GetAsync($uri).GetAwaiter().GetResult()
    try {
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "HTTP $([int]$response.StatusCode) from $uri : $body" }
        return $body
    } finally { $response.Dispose() }
}
function Metrics { return (Fetch 'http://127.0.0.1:8080/__proxy/metrics' | ConvertFrom-Json) }
function Assert-Running([switch]$AllowBackend2Down) {
    if ($null -eq $script:state) { throw 'No demo session. Run .\scripts\demo.ps1 start first.' }
    foreach ($name in @('proxy','backend-1','backend-2','backend-3')) {
        if ($AllowBackend2Down -and $name -eq 'backend-2') { continue }
        $entries = @($script:state.processes | Where-Object name -eq $name)
        if ($entries.Count -ne 1 -or $null -eq (Owned-Process $entries[0])) { throw "$name is not running. Use stop then start, or backend2-start for B2." }
    }
}
function Wait-Up {
    $until = [DateTime]::UtcNow.AddSeconds(20)
    do {
        try {
            $m = Metrics
            if (@($m.backends | Where-Object state -eq 'UP').Count -eq 3) { return }
        } catch { }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $until)
    throw "Not all backends reached UP. Logs: $($script:state.directory)"
}
function Start-Backend2 { Start-Owned 'backend-2' @('com.example.backend.DemoBackendServer','9002','8','20','0') 9002 }
function Set-Delays([int[]]$delays) {
    for ($i=0; $i -lt 3; $i++) { $null = Fetch "http://127.0.0.1:$(9001+$i)/control?delayMs=$($delays[$i])&cpuWorkers=0&healthy=true" }
}
function Switch-Strategy([string]$chosen) {
    Stop-Owned 'proxy'
    $config = [IO.File]::ReadAllText((Join-Path $repo 'config/application.properties'))
    foreach ($pair in @(@('proxy.bind.host',$script:state.bindHost), @('proxy.port','8080'), @('backend.servers','127.0.0.1:9001:8,127.0.0.1:9002:8,127.0.0.1:9003:8'), @('loadbalancer.strategy',$chosen))) {
        $pattern = '(?m)^' + [regex]::Escape($pair[0]) + '=.*$'
        if (-not [regex]::IsMatch($config,$pattern)) { throw "Missing config key $($pair[0])" }
        $config = [regex]::Replace($config, $pattern, ($pair[0] + '=' + $pair[1]))
    }
    $path = Join-Path $script:state.directory 'proxy.properties'
    [IO.File]::WriteAllText($path,$config,[Text.UTF8Encoding]::new($false))
    Start-Owned 'proxy' @('com.example.proxy.Main', ('"' + $path + '"')) 8080
    $script:state.strategy = $chosen
    Save-State
    Wait-Up
    Write-Host "Strategy: $chosen (proxy restarted; all backends UP)"
}
function Show-Status {
    $m = Metrics
    Write-Host "Proxy http://127.0.0.1:8080 | $($m.strategy)"
    Write-Host "Listen: $($script:state.bindHost):8080"
    if ($script:state.bindHost -eq '0.0.0.0') {
        try {
            $addresses = @(Get-NetIPConfiguration -ErrorAction Stop | Where-Object { $_.NetAdapter.Status -eq 'Up' -and $null -ne $_.IPv4DefaultGateway } | ForEach-Object { $_.IPv4Address.IPAddress } | Sort-Object -Unique)
            foreach ($address in $addresses) { Write-Host "LAN client (same network): http://${address}:8080/demo" -ForegroundColor Cyan }
            if ($addresses.Count -eq 0) { Write-Host 'Find the LAN IPv4 address with ipconfig, then open http://<IPv4>:8080/demo.' }
        } catch { Write-Host 'Find the LAN IPv4 address with ipconfig, then open http://<IPv4>:8080/demo.' }
        Write-Host 'If a remote client times out: allow inbound TCP 8080 from LocalSubnet in Windows Firewall (docs/DEMO.md).'
    }
    foreach ($b in $m.backends) {
        Write-Host ('backend-{0} port={1}: {2,-7} inflight={3} latencyEWMA={4:N1}ms warmup={5:P0}' -f ($b.port-9000),$b.port,$b.state,$b.inFlight,$b.latencyEwmaMs,$b.warmup)
    }
}
function One-Request([int]$index, [switch]$Details) {
    $uri = 'http://127.0.0.1:8080/work?cost=1'
    $response = $client.GetAsync($uri).GetAwaiter().GetResult()
    try {
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Request failed: $([int]$response.StatusCode) $body" }
        $result = $body | ConvertFrom-Json
        $port = [int]($result.server -replace 'backend-','')
        $header = @($response.Headers.GetValues('X-Proxy-Backend'))[0]
        if ($header -ne "127.0.0.1:$port") { throw 'Body and proxy backend header disagree.' }
        Write-Host "Request $index -> backend-$($port-9000) (port $port) HTTP 200"
        if ($Details) { Write-Host "Client URL: $uri`nX-Proxy-Backend: $header`nBody: $body" }
        return $port
    } finally { $response.Dispose() }
}
function Run-Traffic([string]$chosen) {
    Switch-Strategy $chosen
    for ($i=0; $i -lt 12; $i++) { $null = Fetch 'http://127.0.0.1:8080/work?cost=1' }
    $rows = @(); $samples = @()
    $watch = [Diagnostics.Stopwatch]::StartNew()
    # Fixed 15 batches x 8 requests for both strategies. Small closed-loop demonstration, not a capacity benchmark.
    for ($batch=0; $batch -lt 15; $batch++) {
        $pending = @()
        for ($i=0; $i -lt 8; $i++) { $pending += $client.GetAsync('http://127.0.0.1:8080/work?cost=1') }
        $m = Metrics
        foreach ($b in $m.backends) {
            $work = $null; $slot = $null
            if ($chosen -eq 'ADAPTIVE') { $work=$b.estimatedOutstandingWorkMs; $slot=$b.estimatedNextSlotMs }
            $samples += [pscustomobject]@{ batch=$batch; backend=($b.port-9000); state=$b.state; inflight=$b.inFlight; latencyEWMA=$b.latencyEwmaMs; estimatedWorkMs=$work; nextSlotMs=$slot }
        }
        for ($i=0; $i -lt $pending.Count; $i++) {
            $response = $pending[$i].GetAwaiter().GetResult()
            try {
                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $backend = ''
                if ($response.IsSuccessStatusCode) { $backend = ($body | ConvertFrom-Json).server }
                $rows += [pscustomobject]@{ request=($batch*8+$i+1); status=[int]$response.StatusCode; backend=$backend }
            } finally { $response.Dispose() }
        }
    }
    $watch.Stop()
    $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $script:state.directory "$chosen-requests.csv")
    $samples | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $script:state.directory "$chosen-metrics.csv")
    Write-Host "${chosen}: 120 measured requests, 8 concurrent, 12 separate warm-up requests"
    foreach ($port in 9001,9002,9003) {
        $count = @($rows | Where-Object backend -eq "backend-$port").Count
        $observed = @($samples | Where-Object backend -eq ($port-9000))
        $inflight = ($observed | Measure-Object inflight -Maximum).Maximum
        $workText = ''
        if ($chosen -eq 'ADAPTIVE') { $workText = ', estimated work peak={0:N1}ms' -f (($observed | Measure-Object estimatedWorkMs -Maximum).Maximum) }
        Write-Host "  backend-$($port-9000): $count responses, sampled inflight peak=$inflight$workText"
    }
    $errors = @($rows | Where-Object { $_.status -lt 200 -or $_.status -ge 300 }).Count
    Write-Host ('  Errors={0}; elapsed={1:N2}s. Raw CSV saved.' -f $errors,$watch.Elapsed.TotalSeconds)
    if ($errors -gt 0) { throw 'Demo traffic had errors; keep CSV and inspect logs. Do not claim a clean comparison.' }
}
function Wait-Backend2([string]$desired, [switch]$ShowTransitions) {
    $until = [DateTime]::UtcNow.AddSeconds(20); $last = ''
    do {
        $b = @((Metrics).backends | Where-Object port -eq 9002)[0]
        if ($ShowTransitions -and $b.state -ne $last) { Write-Host "backend-2: $($b.state)"; $last = $b.state }
        if ($b.state -eq $desired) { return }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $until)
    throw "backend-2 did not reach $desired"
}

$lock = $null
try {
    try { $lock = [IO.File]::Open((Join-Path $runtime 'command.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another demo command is running. Wait for it to finish.' }
    if (Test-Path -LiteralPath $stateFile) {
        $script:state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        # Sessions made before LAN support were loopback-only. Do not silently change a live session.
        if ($null -eq $script:state.PSObject.Properties['bindHost']) { $script:state | Add-Member -NotePropertyName bindHost -NotePropertyValue '127.0.0.1' }
    }
    switch ($Action) {
        'start' {
            if ($null -ne $script:state -and @($script:state.processes).Count -gt 0) {
                if ($PSBoundParameters.ContainsKey('BindHost') -and $BindHost -ne $script:state.bindHost) { throw 'To change BindHost, run stop then start -BindHost <address>.' }
                Assert-Running; Wait-Up; Show-Status; break
            }
            foreach ($port in 8080,9001,9002,9003) { Assert-Free $port }
            if ([string]::IsNullOrWhiteSpace($JavaHome)) {
                $JavaHome = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source)
            }
            $java = Join-Path $JavaHome 'bin/java.exe'
            $compiler = Join-Path $JavaHome 'bin/javac.exe'
            if (-not (Test-Path -LiteralPath $compiler)) { throw 'Set JAVA_HOME or use -JavaHome pointing to JDK 21.' }
            $version = & $compiler -version 2>&1
            if ("$version" -notmatch '^javac 21(?:\.|\s|$)') { throw "JDK 21 required; found $version. Set JAVA_HOME or -JavaHome." }
            $oldPath = $env:PATH
            try { $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $oldPath; & (Join-Path $PSScriptRoot 'build.ps1') }
            finally { $env:PATH = $oldPath }
            $session = [guid]::NewGuid().ToString('N')
            $directory = Join-Path $runtime $session
            New-Item -ItemType Directory -Path $directory | Out-Null
            $jar = Join-Path $directory 'demo.jar'
            Copy-Item -LiteralPath (Join-Path $repo 'target/reverse-proxy-demo.jar') -Destination $jar
            $script:state = [pscustomobject]@{ session=$session; java=[IO.Path]::GetFullPath($java); directory=$directory; jar=$jar; strategy=$Strategy; bindHost=$BindHost; processes=@() }
            Save-State
            try {
                Start-Owned 'backend-1' @('com.example.backend.DemoBackendServer','9001','8','20','0') 9001
                Start-Backend2
                Start-Owned 'backend-3' @('com.example.backend.DemoBackendServer','9003','8','20','0') 9003
                Switch-Strategy $Strategy
            } catch {
                foreach ($entry in @($script:state.processes)) { Stop-Owned $entry.name }
                throw
            }
            Show-Status
            Write-Host "Ready. Processes stay running. Stop with: .\scripts\demo.ps1 stop`nLogs/PIDs: $runtime"
        }
        'stop' {
            if ($null -eq $script:state) { Write-Host 'No recorded demo processes.'; break }
            foreach ($entry in @($script:state.processes | Sort-Object { if ($_.name -eq 'proxy') {0} else {1} })) { Stop-Owned $entry.name }
            Write-Host 'Stopped recorded demo processes. Logs and CSV kept.'
        }
        'status' { Assert-Running -AllowBackend2Down; Show-Status }
        'basic' { Assert-Running; $null = One-Request 1 -Details }
        'strategy' { Assert-Running; Switch-Strategy $Strategy; Show-Status }
        'rr' {
            Assert-Running; Set-Delays @(20,20,20); Switch-Strategy 'ROUND_ROBIN'
            for ($i=0; $i -lt 6; $i++) {
                $port = One-Request ($i+1)
                if ($port -ne (9001 + $i%3)) { throw 'RR sequence differed. Check other clients and backend states.' }
            }
            Write-Host 'PASS: B1 -> B2 -> B3 -> B1 -> B2 -> B3'
        }
        'adaptive' {
            Assert-Running
            Write-Host 'DEMO C: same /work?cost=1; B1/B2/B3 delays = 20/80/240ms; capacity=8 each.'
            try {
                Set-Delays @(20,80,240)
                Run-Traffic 'ROUND_ROBIN'
                Run-Traffic 'ADAPTIVE'
                Write-Host 'Compare distribution and sampled metrics above. This is a routing demo, not proof of optimal performance.'
            } finally { Set-Delays @(20,20,20) }
            Write-Host 'Restored delays to 20ms. Proxy remains ADAPTIVE; restart strategy to clear learned history.'
        }
        'backend2-stop' {
            Assert-Running; Stop-Owned 'backend-2'; Wait-Backend2 'DOWN' -ShowTransitions
        }
        'backend2-start' {
            Assert-Running -AllowBackend2Down
            Stop-Owned 'backend-2'; Start-Backend2; Wait-Backend2 'UP' -ShowTransitions
        }
        'health' {
            Assert-Running; Set-Delays @(20,20,20); Switch-Strategy 'ROUND_ROBIN'
            Write-Host 'DEMO D: stop backend-2 process; wait for health checker (no traffic during detection).'
            Stop-Owned 'backend-2'
            try {
                Wait-Backend2 'DOWN' -ShowTransitions
                Write-Host 'Traffic must now use only backend-1/backend-3:'
                for ($i=1; $i -le 6; $i++) { if ((One-Request $i) -eq 9002) { throw 'DOWN backend received traffic.' } }
            } finally { Start-Backend2 }
            Wait-Backend2 'UP' -ShowTransitions
            $seen2 = $false
            for ($i=1; $i -le 6; $i++) { if ((One-Request $i) -eq 9002) { $seen2 = $true } }
            if (-not $seen2) { throw 'Recovered backend-2 did not receive traffic.' }
            Write-Host 'PASS: DOWN excluded; WARMING/UP recovery; backend-2 receives traffic again.'
        }
    }
} catch {
    Write-Host "DEMO ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'Use status / inspect .demo logs. Use stop to clean only tracked processes.'
    exit 1
} finally {
    $client.Dispose()
    if ($null -ne $lock) { $lock.Dispose() }
}
