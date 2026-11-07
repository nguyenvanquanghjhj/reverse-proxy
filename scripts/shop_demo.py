#!/usr/bin/env python3
"""Local shop lifecycle. Owns only recorded JVMs, checked by OS process creation identity."""
import argparse
import ctypes
import functools
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.shop'
STATE = RUNTIME / 'processes.json'
CP = os.pathsep.join([str(ROOT / 'target/standalone/classes'), str(ROOT / 'lib/*')])
STRATEGIES = ['ROUND_ROBIN', 'LEAST_CONNECTIONS', 'ADAPTIVE']

def creation(pid):
    if os.name == 'nt':
        from ctypes import wintypes
        k = ctypes.WinDLL('kernel32', use_last_error=True)
        k.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        k.OpenProcess.restype = wintypes.HANDLE
        k.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        k.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = k.OpenProcess(0x1000, False, pid)
        if not handle:
            if ctypes.get_last_error() in (87, 1168):
                return None
            raise OSError('Cannot inspect tracked process; refusing to act')
        try:
            values = [wintypes.FILETIME() for _ in range(4)]
            if not k.GetProcessTimes(handle, *(ctypes.byref(v) for v in values)):
                raise OSError('Cannot read process identity')
            # Nonzero exit time means dead; PID entry may briefly remain visible.
            if values[1].dwHighDateTime or values[1].dwLowDateTime:
                return None
            return str((values[0].dwHighDateTime << 32) | values[0].dwLowDateTime)
        finally:
            k.CloseHandle(handle)
    if sys.platform == 'darwin':
        result = subprocess.run(['ps', '-p', str(pid), '-o', 'lstart='], capture_output=True, text=True)
        return result.stdout.strip() or None
    try:
        text = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return None if text[0] == 'Z' else text[19]
    except FileNotFoundError:
        return None
    except OSError as error:
        raise OSError('Cannot inspect tracked process; refusing to act') from error

def terminate(record):
    current = creation(record['pid'])
    if current is None:
        return
    if current != record['birth']:
        raise RuntimeError('PID identity changed; refusing to terminate')
    if os.name == 'nt':
        from ctypes import wintypes
        k = ctypes.WinDLL('kernel32', use_last_error=True)
        k.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        k.OpenProcess.restype = wintypes.HANDLE
        k.CloseHandle.argtypes = [wintypes.HANDLE]
        k.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        handle = k.OpenProcess(0x1001, False, record['pid'])
        if not handle:
            raise OSError('Cannot open tracked process for shutdown')
        try:
            values = [wintypes.FILETIME() for _ in range(4)]
            k.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
            if not k.GetProcessTimes(handle, *(ctypes.byref(v) for v in values)):
                raise OSError('Cannot verify process identity')
            birth = str((values[0].dwHighDateTime << 32) | values[0].dwLowDateTime)
            if birth != record['birth']:
                raise RuntimeError('PID identity changed; refusing shutdown')
            if not k.TerminateProcess(handle, 0):
                raise OSError('Unable to stop owned JVM')
        finally:
            k.CloseHandle(handle)
    else:
        os.kill(record['pid'], signal.SIGTERM)
    deadline = time.monotonic() + 8
    while creation(record['pid']) == record['birth'] and time.monotonic() < deadline:
        time.sleep(.1)
    if creation(record['pid']) == record['birth']:
        raise RuntimeError('Owned process did not stop; tracking retained')

def state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}

def save(data):
    RUNTIME.mkdir(exist_ok=True)
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, indent=2))
    temporary.replace(STATE)

@functools.cache
def java():
    executable = shutil.which('java')
    if not executable:
        raise RuntimeError('JDK 21 required')
    result = subprocess.run([executable, '--version'], capture_output=True, text=True, check=True)
    if ' 21.' not in result.stdout:
        raise RuntimeError('Put JDK 21 first on PATH')
    return executable

def run_admin(command, *args):
    subprocess.run([java(), '-cp', CP, 'com.example.shop.ShopAdmin', command, *args], cwd=ROOT, check=True)

def check():
    java()
    if not (ROOT / 'target/standalone/classes/com/example/shop/ShopHandler.class').exists():
        raise RuntimeError('Build first: scripts/build.ps1')
    if not (ROOT / 'lib/postgresql-42.7.14.jar').exists():
        raise RuntimeError('Prepare dependencies first: python scripts/shop_prepare.py')
    files = Path(os.environ.get('SHOP_FILES_DIR', ROOT / 'shared-files'))
    if not files.is_absolute():
        raise RuntimeError('SHOP_FILES_DIR must be absolute')
    run_admin('check')
    print('JDK 21, classes, JDBC and PostgreSQL schema ready')

def free_port(port):
    with socket.socket() as s:
        s.bind(('127.0.0.1', port))

def launch(name, args, data):
    env = os.environ.copy()
    env['SHOP_ENABLED'] = 'true'
    env.setdefault('SHOP_FILES_DIR', str(ROOT / 'shared-files'))
    RUNTIME.mkdir(exist_ok=True)
    with (RUNTIME / (name + '.log')).open('ab') as log:
        process = subprocess.Popen([java(), '-Dpbl4.shop.owner=' + str(RUNTIME), '-cp', CP, *args], cwd=ROOT, env=env,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
                                   start_new_session=os.name != 'nt')
    try:
        birth = creation(process.pid)
        if birth is None:
            raise RuntimeError(name + ' failed to start; inspect .shop logs')
    except BaseException:
        # The retained Popen handle belongs to this exact child even if tracking failed.
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=8)
        raise
    data[name] = {'pid': process.pid, 'birth': birth}
    save(data)

def wait_ready(url, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, TimeoutError):
            time.sleep(.15)
    raise RuntimeError('Readiness timeout: ' + url + '; inspect .shop/*.log')

def stop(data):
    failures=[]
    for name in list(data):
        try:
            terminate(data[name]); del data[name]
        except (OSError,RuntimeError) as error:
            failures.append(name+': '+str(error))
        try:save(data)
        except OSError as error:failures.append('Tracking write failed: '+str(error))
    if failures:raise RuntimeError('; '.join(failures))

def backend(data, port):
    name = 'backend-' + str(port)
    if name in data and creation(data[name]['pid']) == data[name]['birth']:
        return
    free_port(port)
    launch(name, ['com.example.backend.DemoBackendServer', str(port), '8', '0', '0'], data)
    wait_ready(f'http://127.0.0.1:{port}/health')

def start(data, strategy, trace=True):
    if any(creation(r['pid']) is not None for r in data.values()):
        raise RuntimeError('Tracked session exists; use status or stop before starting')
    check()
    for port in (8080, 9001, 9002, 9003):
        free_port(port)
    RUNTIME.mkdir(exist_ok=True)
    config = (ROOT / 'config/application.properties').read_text(encoding='utf-8')
    config += f'\nloadbalancer.strategy={strategy}\nadaptive.decision-trace.enabled={str(trace).lower()}\n'
    (RUNTIME / 'proxy.properties').write_text(config, encoding='utf-8')
    try:
        for port in (9001, 9002, 9003):
            backend(data, port)
        launch('proxy', ['com.example.proxy.Main', str(RUNTIME / 'proxy.properties')], data)
        wait_ready('http://127.0.0.1:8080/__proxy/health')
        wait_ready('http://127.0.0.1:8080/api/products')
        deadline=time.monotonic()+25
        while True:
            with urllib.request.urlopen('http://127.0.0.1:8080/__proxy/metrics',timeout=2) as response:
                metrics=json.load(response)
            if len(metrics['backends'])==3 and all(b['state']=='UP' for b in metrics['backends']):break
            if time.monotonic()>=deadline:raise RuntimeError('All backends did not finish health/slow start')
            time.sleep(.1)
    except BaseException:
        stop(data)
        raise
    print('Shop: http://127.0.0.1:8080/shop\nDashboard: http://127.0.0.1:8080/__proxy/dashboard')
