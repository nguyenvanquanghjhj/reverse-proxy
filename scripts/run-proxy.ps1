param([string]$Config = 'config/application.properties', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repoRoot
try {
    if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
    & java -cp target/classes com.example.proxy.Main $Config
    if ($LASTEXITCODE -ne 0) { throw "Proxy exited with code $LASTEXITCODE." }
} finally { Pop-Location }
