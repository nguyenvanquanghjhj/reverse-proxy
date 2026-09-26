param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$classesPath = Join-Path $repoRoot 'target/classes'
$logPath = Join-Path $repoRoot 'target/logs'
New-Item -ItemType Directory -Path $logPath -Force | Out-Null
$processes = @()
try {
    foreach ($backendPort in @(9001, 9002, 9003)) {
        $arguments = @('-cp', ('"' + $classesPath + '"'), 'com.example.backend.DemoBackendServer', $backendPort, 8, 20, 0)
        $processes += Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logPath "backend-$backendPort.out.log") -RedirectStandardError (Join-Path $logPath "backend-$backendPort.err.log")
    }
    Write-Host 'Backends: 9001, 9002, 9003 (each: 8 slots / 20 ms).'
    Write-Host "Logs: $logPath. Press Ctrl+C to stop these backend processes."
    while ($true) {
        foreach ($process in $processes) {
            $process.Refresh()
            if ($process.HasExited) { throw "Backend process $($process.Id) exited; see target/logs." }
        }
        Start-Sleep -Milliseconds 500
    }
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) {
            Stop-Process -InputObject $process -ErrorAction SilentlyContinue
            $process.WaitForExit(5000) | Out-Null
        }
        $process.Dispose()
    }
}
