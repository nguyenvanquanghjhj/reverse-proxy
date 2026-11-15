PBL4 — GOI FILE MOI (KHONG GHI DE MA NGUON CU)

1. Giai nen vao thu muc goc cua repository reverse-proxy.
   File moi: .github/workflows/pbl4-ci.yml
             scripts/defense_live.py
             scripts/test_defense_live.py
             docs/DEMO-VA-KE-HOACH.md
             docs/CODEX-CAPABILITY-ROUTING.md
2. Chay: python scripts/test_defense_live.py
3. Khoi dong PBL4 nhu README hien co.
4. Chay: python scripts/defense_live.py compare --out ..\pbl4-demo-compare-01
5. Mo: ..\pbl4-demo-compare-01\report.html
6. Xem them docs/DEMO-VA-KE-HOACH.md de demo drain va quy trinh phuc hoi.

Luu y:
- Chi them file moi; KHONG tu dong sua source, database, GitHub hay storage.
- Giai doan LAN, Docker, JDBC pooling, keep-alive CHUA duoc trien khai trong goi nay.
- Kiem thu tinh nang script bang mock local PASS; chua chay Java tren Windows cua ban.
- CI la cau hinh moi, CHUA co ket qua PASS tren GitHub truoc khi ban commit va chay.
