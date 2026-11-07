param([ValidateSet('start','stop','status','build')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'java21.ps1')
$jdk = Get-Java21Tools
$env:PATH = (Split-Path $jdk.Java) + ';' + $env:PATH
$pg = Join-Path $root '.runtime/postgres/bin'
$data = Join-Path $root '.shop/store-postgres'
function Test-DemoPostgresListener {
    # pg_ctl status can report "not running" when a restricted token cannot query
    # the already running process. Probe PostgreSQL protocol before trying to spawn it.
    @'
import socket,struct,sys
try:
    with socket.create_connection(('127.0.0.1',55434),timeout=2) as connection:
        connection.sendall(struct.pack('!II',8,80877103))
        sys.exit(0 if connection.recv(1) in (b'S',b'N') else 1)
except OSError:
    sys.exit(1)
'@ | python -
    return $LASTEXITCODE -eq 0
}
Push-Location $root
try {
    if ($Action -eq 'build') {
        & (Join-Path $PSScriptRoot 'build.ps1')
        return
    }
    if ($Action -eq 'stop') {
        python scripts/store.py stop
        if ($LASTEXITCODE -ne 0) { throw 'Application shutdown failed.' }
        if (Test-Path (Join-Path $data 'postmaster.pid')) {
            & "$pg/pg_ctl.exe" -D $data -m fast -w stop
            if ($LASTEXITCODE -ne 0) { throw 'Database shutdown failed.' }
        }
        return
    }
    if ($Action -eq 'status') {
        python scripts/store.py status
        if ($LASTEXITCODE -ne 0) { throw 'Status check failed.' }
        return
    }
    $firstRun = -not (Test-Path -LiteralPath '.env.store')
    if (-not $firstRun -and @(Get-OwnedJavaProcesses $root).Count -gt 0) {
        python scripts/store.py start
        if ($LASTEXITCODE -ne 0) { throw 'Existing session is not ready. Inspect status and stop before rebuilding.' }
        return
    }
    if ($firstRun) {
        if (Test-Path -LiteralPath $data) { throw 'Database already exists. Restore .env.store before starting.' }
        if (-not (Test-Path "$pg/initdb.exe")) {
            python scripts/shop_prepare.py --postgres-windows
            if ($LASTEXITCODE -ne 0) { throw 'Runtime dependency preparation failed.' }
        }
        New-Item -ItemType Directory -Path '.shop' -Force | Out-Null
        $dbPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
        $passwordFile = Join-Path $root '.shop/init-password.tmp'
        [IO.File]::WriteAllText($passwordFile, $dbPassword, [Text.UTF8Encoding]::new($false))
        try {
            & "$pg/initdb.exe" -D $data -U pbl4_store --encoding=UTF8 --locale=C --auth=scram-sha-256 "--pwfile=$passwordFile"
            if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed.' }
        } finally { Remove-Item -LiteralPath $passwordFile -Force }
        $config = @(
            'STORE_ENV=demonstration', 'STORE_ORIGIN=http://127.0.0.1:8080',
            'SHOP_DB_URL=jdbc:postgresql://127.0.0.1:55434/postgres', 'SHOP_DB_USER=pbl4_store',
            "SHOP_DB_PASSWORD=$dbPassword", ('SHOP_FILES_DIR=' + (Join-Path $root 'shared-files').Replace('\','/')),
            'SHOP_MAX_PDF_BYTES=262144', 'SHOP_DB_MAX_CONNECTIONS=8', 'STORE_ADMIN_EMAIL=admin@example.test'
        )
        [IO.File]::WriteAllLines((Join-Path $root '.env.store'), $config, [Text.UTF8Encoding]::new($false))
    }
    if (-not (Test-Path 'lib/postgresql-42.7.14.jar')) {
        python scripts/shop_prepare.py
        if ($LASTEXITCODE -ne 0) { throw 'JDBC preparation failed.' }
    }
    if (Test-Path (Join-Path $data 'PG_VERSION')) {
        & "$pg/pg_ctl.exe" -D $data status *> $null
        if ($LASTEXITCODE -ne 0 -and -not (Test-DemoPostgresListener)) {
            & "$pg/pg_ctl.exe" -D $data -l (Join-Path $root '.shop/postgres.log') -o '-h 127.0.0.1 -p 55434' -w start
            if ($LASTEXITCODE -ne 0) { throw 'Database startup failed.' }
        }
    }
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($firstRun) {
        $env:STORE_ADMIN_PASSWORD = 'Demo-' + [Guid]::NewGuid().ToString('N').Substring(0,20)
        try {
            python scripts/store.py setup --seed --admin
            if ($LASTEXITCODE -ne 0) { throw 'Store setup failed. Run store.py setup --seed --admin to retry.' }
            [IO.File]::WriteAllLines((Join-Path $root '.shop/admin-access.txt'), @('Email: admin@example.test', "Password: $env:STORE_ADMIN_PASSWORD"), [Text.UTF8Encoding]::new($false))
        } finally { Remove-Item Env:STORE_ADMIN_PASSWORD -ErrorAction SilentlyContinue }
    }
    python scripts/store.py start
    if ($LASTEXITCODE -ne 0) { throw 'Store startup failed. Inspect .shop logs.' }
    Write-Host 'Admin credentials: .shop/admin-access.txt'
} finally { Pop-Location }
