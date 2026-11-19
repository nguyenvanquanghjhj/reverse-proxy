"""One Windows node per machine. Uses app_runtime process ownership; never initializes data."""
import argparse
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))


def private_ip(raw):
    ip = ipaddress.IPv4Address(raw)
    if not ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_multicast:
        raise ValueError('Cần IPv4 LAN riêng thực tế, không dùng localhost, public hoặc IP ví dụ.')
    return str(ip)


def validate_hosts(values):
    hosts = [private_ip(x) for x in values]
    if len(hosts) != 3 or len(set(hosts)) != 3:
        raise ValueError('Cần đúng ba IPv4 LAN khác nhau, mỗi máy một Backend.')
    return hosts


def directory(raw):
    path = Path(raw).resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Thư mục cấu hình riêng phải ở ngoài repository.')
    return path


def read_env(path):
    allowed = {'SHOP_DB_URL', 'SHOP_DB_USER', 'SHOP_DB_PASSWORD', 'SHOP_FILES_DIR', 'SHOP_MAX_PDF_BYTES',
               'SHOP_DB_MAX_CONNECTIONS', 'SHOP_DB_POOL_ENABLED', 'PBL4_PROBE_TOKEN', 'PBL4_CONTROL_TOKEN',
               'STORE_ORIGIN', 'STORE_ENV', 'STORE_ADMIN_EMAIL', 'STORE_ENABLED', 'SHOP_ENABLED'}
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or key not in allowed:
            raise ValueError('Tệp env có khóa không được hỗ trợ; không in nội dung để bảo vệ thông tin riêng.')
        result[key] = value.strip()
    return result


def build_id():
    classes = ROOT / 'target/standalone/classes'
    if not (classes / 'com/example/backend/BackendApplicationServer.class').is_file():
        raise ValueError('Chưa build. Dùng JDK 21 và scripts/build.ps1 trước khi chạy node.')
    digest = hashlib.sha256()
    for path in sorted(p for p in classes.rglob('*') if p.is_file()):
        digest.update(path.relative_to(classes).as_posix().encode());digest.update(path.read_bytes())
    return digest.hexdigest()


def configure(args):
    hosts = validate_hosts(args.hosts or [input(f'IPv4 máy {i}: ').strip() for i in (1, 2, 3)])
    out = directory(args.directory)
    if (out / 'node.json').exists() or (out / 'node.env').exists():
        raise ValueError('Cấu hình đã tồn tại. Chọn thư mục mới; không ghi đè.')
    env = read_env(args.env_file) if args.env_file else {}
    env['SHOP_DB_URL'] = args.db_url or env.get('SHOP_DB_URL') or input('JDBC URL database QA trên máy 1: ').strip()
    from urllib.parse import urlparse
    db = urlparse(env['SHOP_DB_URL'].removeprefix('jdbc:'))
    if db.scheme != 'postgresql' or db.hostname != hosts[0] or not db.path.strip('/') or db.username or db.password or db.query or db.fragment:
        raise ValueError('JDBC URL phải tới IPv4 máy 1, database riêng, không chứa credential hoặc tham số URL.')
    env['SHOP_DB_USER'] = args.db_user or env.get('SHOP_DB_USER') or input('PostgreSQL user: ').strip()
    if not env['SHOP_DB_USER']:
        raise ValueError('Thiếu user PostgreSQL.')
    if not env.get('SHOP_DB_PASSWORD'):
        env['SHOP_DB_PASSWORD'] = getpass.getpass('Mật khẩu PostgreSQL (không hiển thị): ')
    storage = Path(args.files or input('Kho tệp chung (máy 2/3 dùng UNC): ').strip())
    if not storage.is_absolute() or not storage.is_dir():
        raise ValueError('Kho tệp phải tồn tại, có đường dẫn tuyệt đối; máy 2/3 dùng UNC tới máy 1.')
    if os.name == 'nt' and args.role != 'MAIN' and not str(storage).startswith('\\\\'):
        raise ValueError('Backend 2/3 cần UNC thật, không dùng bản sao cục bộ.')
    marker = storage / '.pbl4-lan-storage-id'
    if not marker.exists():
        if args.role != 'MAIN':
            raise ValueError('Chưa thấy định danh kho chung. Cấu hình MAIN trước và kiểm tra SMB.')
        marker.write_text(str(uuid.uuid4()), encoding='ascii')
    storage_id = str(uuid.UUID(marker.read_text(encoding='ascii').strip()))
    token = os.environ.get('PBL4_PROBE_TOKEN')
    if args.probe_token_file:
        token = args.probe_token_file.read_text(encoding='ascii').strip()
    token = token or env.get('PBL4_PROBE_TOKEN') or getpass.getpass('Probe token dùng chung (32+ ký tự, không hiển thị): ')
    if len(token) < 32 or any(c.isspace() for c in token):
        raise ValueError('Probe token cần từ 32 ký tự, không có khoảng trắng.')
    env.update(SHOP_FILES_DIR=str(storage), PBL4_PROBE_TOKEN=token, STORE_ENABLED='true', SHOP_ENABLED='true',
               STORE_ORIGIN=f'http://{hosts[0]}:8080', STORE_ENV='demonstration')
    env.setdefault('SHOP_DB_POOL_ENABLED', 'false')
    # Store runtime keys here, not in Java command lines or Git-tracked configuration.
    out.mkdir(parents=True, exist_ok=True)
    for key,value in env.items():
        if '\n' in value or '\r' in value:raise ValueError('Giá trị cấu hình chứa xuống dòng.')
    with (out / 'node.env').open('x', encoding='utf-8') as stream:
        stream.write(''.join(f'{k}={v}\n' for k,v in env.items()))
    if args.role == 'MAIN':
        subprocess.run([sys.executable, str(ROOT/'deploy/lan/lan_config.py'), '--proxy-bind', hosts[0], '--backends',
                        f'{hosts[0]}:9001', f'{hosts[1]}:9002', f'{hosts[2]}:9003', '--output', str(out/'proxy.properties')], check=True, capture_output=True)
        with (out/'proxy.properties').open('a', encoding='utf-8') as stream:
            stream.write('\nproxy.management.enabled=true\nproxy.management.port=9090\n')
    config = {'role':args.role, 'hosts':hosts, 'storage_id':storage_id, 'pg_data':str(args.pg_data.resolve()) if args.pg_data else None,
              'strategy':'ADAPTIVE', 'scope':'Prepared three physical Windows nodes; NOT physical acceptance evidence'}
    (out/'node.json').write_text(json.dumps(config, indent=2), encoding='utf-8')
    print(f'Đã tạo cấu hình {args.role}: {out}. Không thay .env.store, firewall, pg_hba hoặc quyền SMB.')


def load(path):
    out = directory(path)
    config = json.loads((out/'node.json').read_text(encoding='utf-8'))
    validate_hosts(config['hosts'])
    if config['role'] not in {'MAIN','BACKEND2','BACKEND3'}:raise ValueError('Vai trò không hợp lệ.')
    env = read_env(out/'node.env')
    env.update(STORE_ENABLED='true', SHOP_ENABLED='true', PBL4_BACKEND_BIND_HOST='0.0.0.0', PBL4_RUNTIME_DIR=str(out/'runtime'))
    os.environ.update(env)
    import app_runtime as runtime
    runtime.RUNTIME=out/'runtime';runtime.STATE=runtime.RUNTIME/'processes.json'
    return out,config,env,runtime


def storage_probe(config,env):
    storage=Path(env['SHOP_FILES_DIR'])
    if (storage/'.pbl4-lan-storage-id').read_text(encoding='ascii').strip()!=config['storage_id']:
        raise ValueError('Định danh kho tệp không khớp; có thể đang dùng bản sao hoặc nhầm share.')
    probe=storage/('.lan-probe-'+str(uuid.uuid4())+'.tmp')
    try:
        payload=os.urandom(32)
        with probe.open('xb') as stream:stream.write(payload);stream.flush();os.fsync(stream.fileno())
        if probe.read_bytes()!=payload:raise ValueError('Kiểm tra đọc/ghi kho tệp không khớp.')
    finally:
        if probe.exists():probe.unlink()  # Only this invocation's new nonce file.


def request(url,env,method='GET',headers=None):
    req=urllib.request.Request(url,method=method,data=b'' if method=='POST' else None,
        headers={'X-PBL4-Probe-Token':env.get('PBL4_PROBE_TOKEN',''),**(headers or {})})
    try:response=urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=4)
    except urllib.error.HTTPError as error:response=error
    with response:
        data=response.read(65537)
        if len(data)>65536:raise ValueError('Phản hồi vượt giới hạn.')
        if response.status==403:raise ValueError('Bị từ chối probe: kiểm tra token dùng chung và quyền truy cập.')
        return response.status,json.loads(data) if data.startswith(b'{') else {}


def preflight(out,config,env,runtime,check_ports=True):
    runtime.java()
    import shutil
    javac=shutil.which('javac')
    version=subprocess.run([javac,'-version'],capture_output=True,text=True) if javac else None
    if version is None or version.returncode or not version.stdout.startswith('javac 21.'):
        raise ValueError('Cần cả java và javac của JDK 21 trên PATH.')
    if sys.version_info < (3,12):raise ValueError('Cần Python 3.12 trở lên.')
    fingerprint=build_id()
    if not (ROOT/'lib/postgresql-42.7.14.jar').is_file():raise ValueError('Thiếu JDBC; chạy scripts/prepare_runtime.py trước.')
    if len(env.get('PBL4_PROBE_TOKEN',''))<32:raise ValueError('Thiếu probe token dùng chung.')
    role=config['role'];index={'MAIN':0,'BACKEND2':1,'BACKEND3':2}[role]
    if check_ports:
        for port in ([8080,9001,9090] if role=='MAIN' else [9001+index]):
            try:
                with socket.socket() as sock:sock.bind((config['hosts'][index],port))
            except OSError as error:raise ValueError(f'Cổng {port} bị chiếm hoặc IPv4 không thuộc máy hiện tại; xem ipconfig.') from error
    storage_probe(config,env)
    result=subprocess.run([runtime.java(),'-cp',runtime.CP,'com.example.store.StoreAdmin','check'],cwd=ROOT,capture_output=True,text=True)
    if result.returncode:raise ValueError('Không truy cập được database/schema. Kiểm tra PostgreSQL, user, pg_hba và database QA; không tự migrate/seed.')
    print(json.dumps({'role':role,'preflight':'PASS','build_sha256':fingerprint,'storage_id':config['storage_id'],'physical_lan':'NOT TESTED'},indent=2))


def postgres(config,start):
    if config['role']!='MAIN' or not config.get('pg_data'):raise ValueError('Chỉ MAIN có pg_data rõ ràng mới được quản lý PostgreSQL.')
    import shutil
    pg=shutil.which('pg_ctl')
    if not pg:raise ValueError('Đặt PostgreSQL bin trên PATH; không cài tự động.')
    data=Path(config['pg_data'])
    if not (data/'PG_VERSION').is_file():raise ValueError('Cluster chưa tồn tại. Không tự init hoặc reset.')
    if start:
        if subprocess.run([pg,'-D',str(data),'status'],capture_output=True).returncode==0:return False
        # PostgreSQL descendants can retain a PIPE even after pg_ctl exits on Windows.
        # Use a real log handle so preflight is not blocked waiting for pipe EOF.
        with (data.parent/'lan-pg-command.log').open('ab') as log:
            subprocess.run([pg,'-D',str(data),'-l',str(data.parent/'lan-postgres.log'),'-w','start'],check=True,
                           stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    else:subprocess.run([pg,'-D',str(data),'-m','fast','-w','stop'],check=True,capture_output=True)
    return True


def start(out,config,env,runtime,start_pg):
    data=runtime.state()
    if any(runtime.creation(r['pid'])==r['birth'] for r in data.values()):
        print('Phiên do launcher quản lý đang chạy; dùng status, không khởi động trùng.');return
    if start_pg:
        if postgres(config,True):
            pid=int((Path(config['pg_data'])/'postmaster.pid').read_text().splitlines()[0])
            (out/'postgres-owned.json').write_text(json.dumps({'data':config['pg_data'],'pid':pid,'birth':runtime.creation(pid)}))
    preflight(out,config,env,runtime)
    port={'MAIN':9001,'BACKEND2':9002,'BACKEND3':9003}[config['role']]
    try:
        runtime.launch('backend-'+str(port),['com.example.backend.BackendApplicationServer',str(port),'8','0','0'],data)
        deadline=time.monotonic()+25
        while True:
            try:
                code,ready=request(f'http://127.0.0.1:{port}/ready',env)
                if code==200 and ready.get('status')=='READY':break
            except OSError:pass
            if time.monotonic()>deadline:raise ValueError(f'Backend {port} chưa READY. Xem log; kiểm tra DB, storage và token.')
            time.sleep(.2)
        if config['role']=='MAIN':
            runtime.launch('proxy',['com.example.proxy.Main',str(out/'proxy.properties')],data)
            runtime.wait_ready('http://127.0.0.1:9090/__proxy/health',25)
        print('Đã khởi động '+config['role']+'. MAIN chỉ chạy Backend 1 và Proxy; không tạo Backend 2/3 local.')
    except BaseException:runtime.stop(data);raise


def status(out,config,env,runtime):
    states={name:('RUNNING' if runtime.creation(r['pid'])==r['birth'] else 'STOPPED') for name,r in runtime.state().items()}
    nodes=[]
    indices=range(3) if config['role']=='MAIN' else [{'BACKEND2':1,'BACKEND3':2}[config['role']]]
    for i in indices:
        host=config['hosts'][i]
        try:
            code,ready=request(f'http://{host}:{9001+i}/ready',env)
            nodes.append({'backend':i+1,'address':f'{host}:{9001+i}','http':code,'status':ready.get('status')})
        except (OSError,ValueError):nodes.append({'backend':i+1,'status':'UNREACHABLE_OR_TOKEN_ERROR'})
    print(json.dumps({'role':config['role'],'processes':states,'backends':nodes,'build_sha256':build_id(),
        'status_scope':'all three from MAIN' if config['role']=='MAIN' else 'this backend only; global view at MAIN',
        'store':f'http://{config["hosts"][0]}:8080/shop','workload':f'http://{config["hosts"][0]}:8080/workload',
        'dashboard_main_only':'http://127.0.0.1:9090/__proxy/dashboard','log_directory':str(out/'runtime'),
        'physical_lan':'NOT TESTED by this status command'},indent=2))


def stop(out,config,env,runtime,stop_pg):
    data=runtime.state();port={'MAIN':9001,'BACKEND2':9002,'BACKEND3':9003}[config['role']]
    record=data.get('backend-'+str(port))
    if record and runtime.creation(record['pid'])==record['birth']:
        # Remote nodes drain locally; the proxy observes X-Backend-Draining on health probes.
        code,_=request(f'http://127.0.0.1:{port}/control?draining=true',env)
        if code!=200:raise ValueError('Không drain được backend; giữ tiến trình để kiểm tra.')
        deadline=time.monotonic()+20
        while True:
            _,ready=request(f'http://127.0.0.1:{port}/ready',env)
            if ready.get('admitted')==0:break
            if time.monotonic()>deadline:raise ValueError('Backend còn request; chưa dừng, thử lại sau.')
            time.sleep(.2)
        runtime.terminate(record);del data['backend-'+str(port)];runtime.save(data)
    runtime.stop(data)
    if stop_pg:
        owned=out/'postgres-owned.json'
        if not owned.exists() or json.loads(owned.read_text()).get('data')!=config.get('pg_data'):
            raise ValueError('Launcher không khởi động PostgreSQL này; từ chối dừng.')
        record=json.loads(owned.read_text())
        if not record.get('birth') or runtime.creation(record['pid'])!=record['birth']:
            raise ValueError('Danh tính PostgreSQL đã đổi; không dừng tiến trình khác.')
        postgres(config,False);owned.unlink()
    print('Đã dừng đúng các JVM được quản lý. Dữ liệu và kho tệp giữ nguyên.')


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['configure','preflight','start','status','stop','help'])
    parser.add_argument('--directory',type=Path,help='Thư mục cấu hình riêng ngoài Git')
    parser.add_argument('--role',choices=['MAIN','BACKEND2','BACKEND3'])
    parser.add_argument('--hosts',nargs=3);parser.add_argument('--env-file',type=Path)
    parser.add_argument('--db-url');parser.add_argument('--db-user');parser.add_argument('--files')
    parser.add_argument('--probe-token-file',type=Path);parser.add_argument('--pg-data',type=Path)
    parser.add_argument('--start-postgres',action='store_true');parser.add_argument('--stop-postgres',action='store_true')
    args=parser.parse_args(argv)
    if args.action=='help':parser.print_help();return
    if not args.directory:parser.error('Cần --directory ngoài repository')
    if args.action=='configure':
        if not args.role:parser.error('configure cần --role MAIN/BACKEND2/BACKEND3')
        configure(args);return
    out,config,env,runtime=load(args.directory)
    if args.action=='preflight':preflight(out,config,env,runtime)
    elif args.action=='start':start(out,config,env,runtime,args.start_postgres)
    elif args.action=='status':status(out,config,env,runtime)
    else:stop(out,config,env,runtime,args.stop_postgres)


if __name__=='__main__':
    try:main()
    except (ValueError,OSError,RuntimeError,subprocess.SubprocessError) as error:
        print('LAN BLOCKED: '+(str(error) if isinstance(error,(ValueError,RuntimeError)) else type(error).__name__)+'. Xem deploy/lan/README.md.',file=sys.stderr)
        raise SystemExit(2)
