# Triển khai trên đúng ba máy Windows

```text
Trình duyệt (máy 2 hoặc 3)
              |
              v
Máy 1: Reverse Proxy :8080
       |               |              |
Máy 1: B1 :9001   Máy 2: B2 :9002  Máy 3: B3 :9003
       |               |              |
       +---------------+--------------+
                       |
        Máy 1: PostgreSQL + Shared Storage (SMB)
```

Không cần máy thứ tư. PostgreSQL và SMB trên máy 1 vẫn là điểm lỗi đơn. Hệ thống
không tạo replica DB hoặc tự failover kho tệp. HTTP thuần chỉ dùng trên LAN tin cậy
hoặc VPN; không public các cổng này ra Internet.

## 1. Chuẩn bị phần mềm và build

Cả ba máy cần Windows, **JDK 21** (cả `java`, `javac`), **Python 3.12+**, cùng build
và JDBC driver. Maven không bắt buộc. Máy 1 thêm PostgreSQL, `pg_ctl`, `psql` và SMB.
Máy 2/3 không cần PostgreSQL server, không copy database hoặc ảnh/PDF thành ba bản.

Gợi ý chuẩn bị: máy 1 RAM 8 GiB, mỗi backend RAM 4 GiB, CPU từ 2 luồng. Đây chưa phải
nghiệm thu hiệu năng. Dự trù thêm chỗ cho JDK, database và tệp streaming QA.
Cáp LAN ổn định hơn Wi-Fi. Wi-Fi riêng dùng được nếu không có client isolation.
Giữ IP bằng DHCP reservation hoặc cấu hình được quản trị mạng chấp thuận.
Không phát tải lớn trên mạng công cộng. Sau khi có dependency/build, chỉ nhập PDF
từ nguồn HTTPS mới cần Internet.

Đặt JDK cho PowerShell hiện tại, không đổi JDK hệ thống:

```powershell
$env:JAVA_HOME = Read-Host 'Thư mục JDK 21 trên máy này'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java --version
javac -version
python --version
```

Tại máy giữ source đã kiểm tra, dừng JVM trước build; giữ PostgreSQL:

```powershell
python scripts/store.py stop
powershell -ExecutionPolicy Bypass -File scripts/build.ps1
python deploy/lan/package.py --out D:\PBL4-LAN-QA\pbl4-three-nodes.zip
```

Gói lấy working tree hiện tại: classes/resources, launcher, source và JDBC; không
chứa `.env.store`, `.shop`, database, shared-files hoặc Git credentials. Copy cùng
ZIP sang cả ba máy và giải nén vào thư mục mới. Không giải nén đè phiên đang chạy.
Giữ `BUILD-MANIFEST.json`; `preflight`/`status` trên ba máy phải in cùng `build_sha256`.
Không dùng JAR cũ trong Git thay cho build vừa kiểm tra.

## 2. Kiểm tra mạng

Mỗi máy chạy `ipconfig`, lấy IPv4 của card LAN đang dùng; tránh Docker/WSL và loopback.
Nhập các biến này trên từng máy, không dùng nguyên IP ví dụ:

```powershell
$MainIP = Read-Host 'IPv4 thực tế máy 1'
$B2IP = Read-Host 'IPv4 thực tế máy 2'
$B3IP = Read-Host 'IPv4 thực tế máy 3'
```

Sau khi dịch vụ tương ứng đã chạy:

```powershell
Test-NetConnection $MainIP -Port 8080
Test-NetConnection $MainIP -Port 5432
Test-NetConnection $MainIP -Port 445
Test-NetConnection $B2IP -Port 9002
Test-NetConnection $B3IP -Port 9003
```

Thay 5432 bằng cổng cluster QA thật. `ping` chỉ kiểm ICMP: ping lỗi không đồng nghĩa
TCP lỗi. TCP thành công chưa chứng minh user DB/token/quyền SMB đúng; `preflight`
và `/ready` kiểm lớp ứng dụng.

Người có quyền quản trị Windows cấu hình firewall giới hạn IP: máy 1 tới B2/B3,
client được phép tới 8080; chỉ IP backend được cấp quyền tới DB/SMB máy 1. Backend
bind `0.0.0.0` để control loopback hoạt động, nên firewall phải giới hạn nguồn.
Tool không tự mở firewall, không cấp quyền Everyone, không cài phần mềm.

## 3. Chuẩn bị PostgreSQL và SMB tại máy 1

Dùng cluster/database QA riêng, không dùng DB chứa đơn cần bảo toàn để thử lỗi.
Launcher không init, migrate, seed hoặc sửa cấu hình DB ngầm.

Trong `postgresql.conf` của cluster QA, `listen_addresses` chỉ gồm localhost và IP
máy 1. Trong `pg_hba.conf`, cho phép đúng database/role QA và IP máy 2/3 bằng `/32`,
`scram-sha-256`, cùng quyền local cho máy 1. Không dùng `trust` cho LAN, `0.0.0.0/0`
hoặc `listen_addresses='*'` để vượt lỗi. Người vận hành restart/reload đúng cluster QA
theo loại thay đổi; không tác động cluster kinh doanh.

Tạo env QA ngoài Git gồm `SHOP_DB_URL`, `SHOP_DB_USER`, `SHOP_DB_PASSWORD`,
`SHOP_FILES_DIR` tuyệt đối, `STORE_ORIGIN=http://<IP_máy1>:8080` và
`STORE_ENV=demonstration`. Chỉ một operator tại máy 1 chạy setup trên DB QA đã chọn:

```powershell
python scripts/store.py --env-file D:\PBL4-LAN-QA\qa.env setup --seed --admin
```

`deployment` yêu cầu HTTPS; không tắt CSRF hoặc cookie để vượt lỗi Origin. Mọi máy
dùng cùng origin IP máy 1, không trộn localhost/IP trong một phiên.

Chia sẻ thư mục QA mới tại máy 1, ví dụ `D:\PBL4-LAN-QA\shared-files`, bằng SMB.
Cấp cả share ACL và NTFS đọc/ghi cho đúng tài khoản Windows. Máy 2/3 dùng UNC
`\\<máy1>\<share>`, chạy Java dưới tài khoản đã được cấp quyền. Dùng Windows Credential
Manager cho credential SMB, không đặt trong Git hoặc command line. `Test-Path` phải
thành công từ chính người chạy Java. Không tạo ba thư mục local có tên giống nhau.

`configure MAIN` tạo marker `.pbl4-lan-storage-id`. Hai node khác phải đọc cùng marker.
`preflight` tạo/đọc/xóa đúng một tệp probe nonce của lần gọi, giữ nguyên dữ liệu khác.
Marker không thay thế kiểm thử đồng bộ ảnh/PDF thật. Atomic rename trên SMB phải được
kiểm chứng; provider không hỗ trợ sẽ bị storage hiện có từ chối thay vì ghi không an toàn.

## 4. Cấu hình ba vai trò

Dùng cùng probe token ngẫu nhiên tối thiểu 32 ký tự trên ba máy. Tool hỏi token và
mật khẩu bằng prompt ẩn, hoặc đọc `--probe-token-file` riêng ngoài Git. Không đưa
secret vào command history. `node.env` chứa secret, cần ACL chỉ cho đúng người chạy;
không gửi cùng ZIP hoặc commit. Cấu hình đã tồn tại không bị ghi đè.

Máy 1 (thay cổng/database/share bằng giá trị đã chuẩn bị):

```powershell
powershell -ExecutionPolicy Bypass -File deploy/lan/windows.ps1 configure --directory D:\PBL4-LAN-QA\main --role MAIN --hosts $MainIP $B2IP $B3IP --env-file D:\PBL4-LAN-QA\qa.env --db-url "jdbc:postgresql://${MainIP}:5432/pbl4_lan_qa" --files D:\PBL4-LAN-QA\shared-files
```

Máy 2 và 3, nhập cùng role/password DB và token; dùng UNC thật:

```powershell
# Máy 2
powershell -ExecutionPolicy Bypass -File deploy/lan/windows.ps1 configure --directory D:\PBL4-LAN-QA\backend2 --role BACKEND2 --hosts $MainIP $B2IP $B3IP --db-url "jdbc:postgresql://${MainIP}:5432/pbl4_lan_qa" --files "\\${MainIP}\PBL4-QA"
# Máy 3
powershell -ExecutionPolicy Bypass -File deploy/lan/windows.ps1 configure --directory D:\PBL4-LAN-QA\backend3 --role BACKEND3 --hosts $MainIP $B2IP $B3IP --db-url "jdbc:postgresql://${MainIP}:5432/pbl4_lan_qa" --files "\\${MainIP}\PBL4-QA"
```

Không copy env máy 1 rồi giữ đường dẫn local trên hai máy khác. Chọn directory phiên
mới khi đổi IP. Tool không sửa `.env.store` hoặc thay cấu hình localhost đang dùng.

## 5. Khởi động theo thứ tự

1. Máy 1: PostgreSQL QA và SMB trước. Xác minh DB/share từ máy 2/3.
2. Mỗi máy chạy `preflight` với directory vai trò của mình.
3. Máy 1 `start`: chỉ B1:9001 và Proxy:8080; management loopback:9090.
4. Máy 2 `start`: chỉ B2:9002. Máy 3 `start`: chỉ B3:9003.
5. Máy 1 `status`: chờ cả ba READY; đối chiếu IP và build hash.

Ví dụ máy 1; đổi directory tương ứng trên hai máy còn lại:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/lan/windows.ps1 preflight --directory D:\PBL4-LAN-QA\main
powershell -ExecutionPolicy Bypass -File deploy/lan/windows.ps1 start --directory D:\PBL4-LAN-QA\main
powershell -ExecutionPolicy Bypass -File deploy/lan/windows.ps1 status --directory D:\PBL4-LAN-QA\main
Get-Content D:\PBL4-LAN-QA\main\runtime\backend-9001.log -Tail 30
```

Nếu muốn MAIN khởi động cluster QA **đã tồn tại**, thêm `--pg-data` khi configure,
đặt `pg_ctl` trên PATH rồi dùng `start --start-postgres`. Tool dùng listen/auth đã
chuẩn bị, không init/reset. PG đã chạy sẵn không được launcher nhận quyền dừng.

DB lỗi: kiểm server/cổng/role/schema/pg_hba. Storage lỗi: kiểm UNC, tài khoản, share
và NTFS. Cổng bị chiếm: xem `Get-NetTCPConnection`, không kill PID tùy ý. Probe 403:
đối chiếu token. Xem log B2 trên máy 2 và log B3 trên máy 3, tại `directory\runtime`.

## 6. Truy cập

- `http://<IP_máy1>:8080/shop`; quản trị `/admin`; thử tải `/workload`.
- Dashboard tại **máy 1**: `http://127.0.0.1:9090/__proxy/dashboard`.
- Dashboard từ máy 2/3 bị từ chối theo giới hạn loopback. Management listener không
  phát workload; dùng Workload Console để gửi. Không mở API quản trị cho cả LAN.
- Ba địa chỉ Dashboard phải là `IP1:9001`, `IP2:9002`, `IP3:9003`.
- Đăng nhập/sử dụng qua Proxy, không dùng trực tiếp từng backend làm origin cửa hàng.

## 7. Nghiệm thu trên ba máy thật

Ghi danh tính máy/IP/build hash, client nằm trên máy nào và đường truyền. Tại DB QA:
kiểm session đăng nhập B1 đọc tiếp B2, sửa sản phẩm B1 đọc B3; tải ảnh/PDF qua từng
backend và so SHA; kiểm ADMIN/CUSTOMER/CSRF; đồng thời đặt stock nhỏ không bán vượt;
replay cùng key/body chỉ một đơn và một lần trừ kho. Lưu response và SQL đã che dữ liệu riêng.

Tại máy 1 đổi RR/LC/Adaptive trên Dashboard hoặc OperatorCli khi có control token.
Giữ danh sách backend/hệ số, không sửa thuật toán. Từ máy 2 hoặc 3:

```powershell
python scripts/ops/lan_benchmark.py --url "http://${MainIP}:8080/work?cost=1" --requests 240 --workers 6 --out D:\PBL4-LAN-QA\evidence\rr-work-01
```

Đổi thư mục mỗi lượt. Cùng tải, timeout, concurrency, ít nhất 3 lần/thuật toán; thử
`/slow` và `/compute?cost=1` cho tải chậm/CPU. Giữ CSV/JSON/SHA và metrics trước/sau.
Nút 300 request của tab là demo tải hỗn hợp, không thay benchmark kiểm soát. Client
và backend chung máy gây nhiễu tài nguyên, phải ghi rõ. PDF chỉ thử trong profile QA
streaming đã nghiệm thu, không bật mặc định hoặc dùng tài liệu khách hàng.

Minh họa sự cố QA: máy 2 `stop` đúng launcher, xem Proxy loại B2, rồi `start` và xem
B2 phục hồi. Graceful stop drain/chờ request; crash thật có thể làm lỗi request đang
chạy. Health check phát hiện qua `/health`; circuit breaker hạn chế dispatch khi lỗi,
cho thử phục hồi có giới hạn; slow start tăng tải dần sau hồi phục. Không bảo đảm
không lỗi trong cửa sổ phát hiện. Không retry POST tự động; mất response sau commit
phải kiểm lại cùng key/body. Không kill PG, rút dây hoặc đổi firewall để demo.

Đối chiếu SQL stock/order và SHA file trước/sau dừng B2. Không kết luận dữ liệu nhất
quán chỉ vì backend cùng PostgreSQL. Không gọi loopback/Docker bridge là ba máy vật lý.

## 8. Dừng an toàn

Máy 2/3 dùng `stop --directory <node>`; `start` để restart riêng. Dừng các backend
remote trước, rồi MAIN. MAIN drain B1, đợi request đã nhận, dừng B1 và Proxy được theo
dõi. Quá thời gian chờ thì từ chối dừng, giữ trạng thái cho người vận hành kiểm tra.
PID luôn được đối chiếu thời điểm tạo, không kill process khác vì trùng PID.

PG mặc định giữ chạy. Chỉ `stop --stop-postgres` khi MAIN đã khởi động cluster đó bằng
`--start-postgres`, danh tính vẫn khớp và mọi backend/writer đã dừng. Không xóa DB,
volume, ảnh/PDF, runtime hoặc dùng reset/clean để tắt ứng dụng.

## Trạng thái nghiệm thu

`PHYSICAL 3-PC LAN TEST: NOT TESTED`

Kiểm cấu hình/unit/localhost không thay thế ba máy thật. SMB/quyền UNC/firewall,
JDBC LAN, mất kết nối và benchmark vật lý cần log thực trước khi đánh dấu PASS.
