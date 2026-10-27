param([string]$Config = 'config/application.properties', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'java21.ps1')
$java21 = Get-Java21Tools
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repoRoot
try {
    if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
    & $java21.Java -jar target/reverse-proxy-demo.jar $Config
    if ($LASTEXITCODE -ne 0) { throw "Proxy exited with code $LASTEXITCODE." }
} finally { Pop-Location }
