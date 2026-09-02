# Review Hạ tầng & Cấu hình — petproject (Infra/Config)

- Người review: senior reviewer (DevOps/Java), READ-ONLY
- Ngày: 2026-09-02
- Phạm vi: root `pom.xml`, `docker-compose.yml`, `docker-compose.prod.yml`, `start-docker.sh`, `stop-docker.sh`, `mock-services/mock-payment-provider/*`, `docker/**`, `.env*` , `.gitignore`, `.dockerignore`, `lombok.config`, `README.md`, `docker-README.md`, `docs/scripts/*`. Đã đọc ~21 file đầy đủ + liếc qua `.mvn/`, `.run/`, các service `pom.xml` và 1 vài `application.yml` để đối chiếu consistency (không review sâu code Java, không đọc `media-service/`).

## Kết quả kiểm tra nhanh (checks nền)

- `.env` **không** bị git track (`git ls-files` chỉ có `.env.example`, `.env.prod.example`); `git log --all -- .env` trống — chưa từng bị commit. `.gitignore:22-26` ignore đúng (`*.env.*` + re-include các example). → không có Critical về lộ secret trong git.
- `docs/PRODUCTION-READINESS.md` tồn tại và mô tả đúng overlay prod (chạy cả 2 file, `--scale mock-payment-provider=0`, allowlist...).

## Findings theo file

### pom.xml (root)

- [Medium][pattern] `pom.xml:73,187-190` — `spring-boot-starter-aop` bị pin `4.0.0-M2` (bản milestone) trong `dependencyManagement` của repo, ghi đè bản quản lý `4.1.1` của parent Spring Boot → mọi service dùng starter-aop đều chạy phiên bản milestone cũ hơn Boot. Đề xuất: gỡ pin này để kế thừa từ parent.
- [Medium][performance] `pom.xml:347-366` — Jib chỉ đặt 1 jvmFlag (`/dev/./urandom`), không `-XX:MaxRAMPercentage`/`-Xmx`; kết hợp compose không giới hạn `mem_limit` → JVM mặc định lấy 25% RAM host (container không cgroup limit), 15 container Java có thể OOM máy dev; ES được pin heap 512m (tuning dev) lại bị overlay prod kế thừa nguyên xi. Đề xuất: thêm `-XX:MaxRAMPercentage=75` vào jib; đặt `mem_limit`/`cpus` trong compose (bản prod nâng heap ES).
- [Medium][security] `pom.xml:349-366` — không khai báo `<user>` trong jib container → toàn bộ 14 ảnh service chạy **root** trong container; path tấn công: RCE trong 1 service → root trong container → đọc env secret, pivot toàn mạng nội bộ (Kafka/Postgres không auth mạnh). Đề xuất: `<user>` non-root (vd `65532:65532`) hoặc `runAsNonRoot`.
- [Low][clean-code] `pom.xml:40` + `pom.xml:86-118` — property `<revision>1.0-SNAPSHOT</revision>` khai báo nhưng **không nơi nào dùng** (mọi chỗ ghi cứng `1.0-SNAPSHOT`); flatten plugin cấu hình `resolveCiFriendliesOnly` nên vô tác dụng; README.md:119 lại tuyên bố "flatten-maven-plugin resolves ${revision}". Đề xuất: dùng thật `${revision}` hoặc xóa property + sửa doc.
- [Low][clean-code] `pom.xml:196-201` — BOM testcontainers import ghi cứng `2.0.5` thay vì `${testcontainers.version}` (property 2.0.5 ở dòng 72) — hai nơi phải đồng bộ tay. Đề xuất: dùng property.
- [Low][pattern] `pom.xml:294-301` — checkstyle chỉ nằm trong `pluginManagement` với `failsOnError=false`, `failOnViolation=false`, không có checkstyle.xml, không module nào bind → checkstyle thực chất **không chạy bao giờ** (dead config, không có style gate). Đề xuất: hoặc bind + có config file + failOnViolation=true, hoặc xóa.
- [Low][performance] `pom.xml:362` — `creationTime: USE_CURRENT_TIMESTAMP` làm image không tái-lập (mỗi build sinh layer mới, khó audit image digest). Đề xuất: timestamps từ SOURCE_DATE_EPOCH/pin thời gian khi cần reproducibility.

### docker-compose.yml

- [High][pattern] `docker-compose.yml:628` + `notification-service/src/main/resources/application.yml:7-8` — service notification-service thiếu anchor `*pg-creds` (`<<: *jwt` duy nhất), trong khi tất cả service dùng DB khác đều nhận `POSTGRES_USER/POSTGRES_PASSWORD`; application.yml fallback `${POSTGRES_USER:admin}`. Đường lỗi cụ thể: môi trường dev hoạt động tình cờ (vì `.env` dev cũng là admin/admin), nhưng khi prod xoay mật khẩu mạnh (như overlay và `.env.prod.example` yêu cầu) notification-service âm thầm dùng `admin/admin` → fail xác thực Postgres, service chết trong prod. Đề xuất: thêm `*pg-creds` vào stanza notification-service; đồng thời bỏ fallback `:admin` khỏi application.yml.
- [Medium][security] `docker-compose.yml:52-53,71-72,90-91,126-127,150-152` — port infra publish lên `0.0.0.0` của host: postgres (superuser admin/admin), redis (password `admin`), kafka PLAINTEXT không auth, elasticsearch `xpack.security.enabled=false`, rustfs console admin/admin. Đường tấn công cụ thể: bất kỳ máy nào cùng LAN/VPN (quán cà phê, campus) kết nối host:5432 với admin/admin → superuser Postgres → đọc/đổi mọi DB + mượn `COPY ... PROGRAM` đọc file trong container; ES không auth cho phép xóa index. Đề xuất: bind `127.0.0.1:${PORT}` mặc định (hoặc để default creds dev nhưng chỉ loopback); cân nhắc enable `xpack.security` có username/password từ `.env`.
- [Medium][security] `docker-compose.yml:25-28,45-63` + `docker/postgres/init/create-all-databases.sql` — 12 service DB + keycloak dùng chung MỘT superuser (`POSTGRES_USER`) không có phân quyền per-service; hệ quả: SQLi hoặc lộ env-var ở 1 service bất kỳ = đọc/ghi toàn bộ data khách hàng ở 11 database khác + cả realm Keycloak. Đề xuất: init script tạo role/user riêng cho từng DB (least privilege).
- [Medium][security] `docker-compose.yml:174` — Keycloak chạy `start-dev` (dev mode) và overlay prod (`docker-compose.prod.yml`) chỉ override admin creds, không đổi command → production chạy Keycloak dev-mode (kém optimized, bật dev artifacts). Đề xuất: trong prod overlay chạy `start --optimized` với biến dành riêng hoặc ảnh dựng riêng.
- [Medium][security] `docker-compose.yml:392-401` + `docker-compose.prod.yml:30-31` — mock PSP nằm trong compose gốc (được prod overlay kế thừa) và `PAYMENT_PROVIDER` default `mock`; nếu vận hành prod không nhớ `--scale mock-payment-provider=0` (chỉ ghi trong comment header), payment thật đi vào simulator → rủi ro tài chính khi `PAYMENT_SERVICE_ENABLED=true`. Đề xuất: đưa mock vào `profiles: ["dev"]` hoặc prod overlay set `PAYMENT_PROVIDER` từ env bắt buộc, không default mock.
- [Medium][pattern] `docker-compose.prod.yml:34-39,73,121,127,142,148,154,162,170,181` — 8 service ghi JSONL vào **cùng một file** `/var/log/audit/audit.jsonl` trên volume `audit_logs` dùng chung → các dòng audit bị interleave/hỏng khi 8 JVM append đồng thời; không có rotation on-box. Đề xuất: mỗi service ghi sub-directory riêng (filebeat/vector tail pattern glob), thêm rotation.
- [Low][security] `docker-compose.yml:70,76` — password Redis truyền qua `command: redis-server --requirepass "${REDIS_PASSWORD}"` và healthcheck `redis-cli -a '${REDIS_PASSWORD}'` → hiện trên argv, thấy được qua `docker inspect`/`ps`/`docker compose config`. Đề xuất: dùng redis config file trong volume hoặc disable healthcheck arg.
- [Low][dry] `docker-compose.yml:216-224` + `.env.example:27-35` — bộ 9 biến gateway rate-limit khai báo default ở cả hai nơi, phải đồng bộ thủ công. Đề xuất: chỉ giữ default ở compose (hoặc chỉ ở .env.example).

### docker/keycloak/import/ecommerce-realm.json

- [High][security] `docker/keycloak/import/ecommerce-realm.json:64,77,90,103,127,136` — realm import (dùng chung cho dev VÀ prod qua `--import-realm`, overlay prod không thay import) chứa credential cố định công khai trong git: 4 client secret `changeme` (order/rating/search/product), user `adminuser/adminpass` có ADMIN+MANAGER, `testuser/testpass`; `.env.prod.example` chỉ hướng dẫn xoay 4 client secret, KHÔNG đề cập xoay mật khẩu user người. Đường tấn công cụ thể: ở prod `KEYCLOAK_PUBLIC_SERVER_URL` bắt buộc phải public (khách cần đăng nhập) → kẻ tấn công đăng nhập trang login thường bằng `adminuser/adminpass` (không temporary, không hết hạn) → token ADMIN → nếu `ADMIN_IP_ALLOWLIST` bị bỏ trống (chính overlay phải cảnh báo "empty = allow-all"), full quyền backoffice. Đề xuất: (a) export realm không chứa mật khẩu người dùng, (b) prod xoay mọi mật khẩu + client secret qua admin API hoặc dùng realm khác, (c) thêm bước verify bắt buộc (startup probe) từ chối khởi động nếu default credential còn tồn tại.
- [Medium][security] `docker/keycloak/import/ecommerce-realm.json:35-48` — client `ecommerce-client` là publicClient nhưng `directAccessGrantsEnabled: true`, `fullScopeAllowed: true`, `redirectUris: http://localhost:*`, `webOrigins: *` — cấu hình dev được import thẳng vào prod, mở password-grant và redirect wildcard localhost. Đề xuất: tách client/redirect theo môi trường; prod chỉ whitelist domain thật.

### docker-compose.prod.yml

- (Tín dụng) Thiết kế overlay ingress-only (`ports: !override []` với cảnh báo yêu cầu compose ≥ 2.24), comment fail-closed cho secret rỗng, tài liệu hóa XFF proxy — tốt, hiếm thấy ở dự án tầm này.
- [Medium][security] `docker-compose.prod.yml:56` + `.env.prod.example:31` — `ADMIN_IP_ALLOWLIST` default rỗng = "filter INACTIVE (allow-all)"; template ghi `<operator-cidrs-comma-separated>` — nếu operator copy template mà quên thay placeholder, gateway fail-fast (may mắn), nhưng nếu set rỗng chủ động là allow-all toàn bộ backoffice. Đã có document, chỉ thiếu một guard kỹ thuật. Đề xuất: fail-fast khi env rỗng trong prod (không chỉ khi sai CIDR).
- [Low][documentation] `docker-compose.prod.yml:65` — comment nhắc "Copy this same line into any service block to export that service's spans" cho OTLP là sai (OTLP endpoint nên set global theo host `/etc/hosts` hoặc env, không copy tay); promote OTEL_SDK_DISABLED global thay vì hướng dẫn copy-paste.

### start-docker.sh / stop-docker.sh

- [Medium][documentation] `start-docker.sh:211,221-226` — bảng summary in: "Keycloak Admin `http://localhost:8080`" — **sai port** (gateway chiếm 8080; Keycloak publish 9090 theo compose:187); đồng thời in cứng "(admin / admin)" cho Keycloak/PostgreSQL, "password: admin" cho Redis, "admin/admin" cho RustFS thay vì đọc từ `.env` → vừa gây nhầm hướng operator vừa in credential ra màn hình không cần thiết. Đề xuất: in URL đúng (9090), bỏ phần credential hoặc in động từ `.env`.
- [Low][error-handling] `stop-docker.sh:149` — `down` truyền `--env-file ${ENV_FILE}`; nếu operator xóa `.env` khi stack đang chạy, lệnh down fail → không stop được stack. Đề xuất: down/ps/logs không cần env-file (volume mặc định đủ để resolve project name), bỏ flag này.
- (Tín dụng) `stop-docker.sh:112-127` — chặn hành động destructive ở chế độ non-interactive (CI) trừ `FORCE=1`, confirm rõ ràng trước khi xóa volume — fail-safe tốt.
- [Low][performance] `start-docker.sh:128` — luôn chạy lại `mvn -q -DskipTests jib:dockerBuild` mỗi lần start (mất phút), mâu thuẫn với docker-README.md:16 ("will skip the rebuild step when images exist"). Đề xuất: sửa doc hoặc thêm flag `--no-build`.

### mock-services/mock-payment-provider/

- [Medium][error-handling] `server.js:22-44,58` — webhook gửi một lần duy nhất (fire-and-forget, delay ngẫu nhiên 200–800ms), không retry, không timeout → payment-service restart/rolling deploy đúng thời điểm hoặc network chớp = CAPTURED/REFUNDED bị mất vĩnh viễn, payment treo limbo. Đề xuất: retry đơn giản (vd 3 lần exponential, 5s) + `req.setTimeout`.
- [Low][security] `server.js:86-89` — body POST tích lũy không giới hạn (`raw += chunk`), thân thiện với môi trường dev nhưng có thể làm cạn bộ nhớ nếu gọi nhầm từ mạng khác; container không publish port (điểm cộng). Đề xuất: giới hạn body (1MB) bằng `req.destroy()` khi quá.
- [Low][security] `Dockerfile:1` — `node:20-alpine` chạy root; không `USER node`; nội bộ-only nên nguy cơ thấp. Đề xuất: thêm `USER node` cho đồng bộ hardening.
- (Tín dụng) Không có dependency, `package.json` gọn, HMAC ký webhook đúng như payment-service kỳ vọng, endpoint `/reset` có ích cho test — KISS tốt.

### .env / .env.example / .env.prod.example / .gitignore / .dockerignore

- [Critical — đã loại trừ] `.env` không bị commit, `.gitignore:22-26` đúng. Không có secret thật lộ trong git.
- [Low][security] `.env:57-58` — `MAIL_PASSWORD="ecommerce@!@#"` cho địa chỉ yopmail; giá trị trông như placeholder fixture nhưng nằm trong file thật — xác nhận đây **không** phải Gmail App Password thật trước khi dùng; không bao giờ đưa `.env` vào git/chat CI.
- [Low][documentation] `.env.example:11-115` — thiếu nhiều biến compose thực tế dùng (`GATEWAY_*` có nhưng thiếu `KAFKA_SERVERS`, `PAYMENT_*`, `SHIPMENT_*`, `SMTP_*`, `SEARCH_SERVICE_CLIENT_*`...), nên copy template ra `.env` mới không thể chạy được stack như `.env` hiện tại. Đề xuất: tái sinh `.env.example` từ `.env` (ẩn secret).
- [Low][documentation] `.env:45` — `STORAGE_BUCKET=ecommerce-media` lệch với thiết kế D1 (bucket private tên `media`, mặc định trong compose:496) và với `.env.example:66` (`media`) → gây nhầm lẫn khi debug presign/ACL. Đề xuất: thống nhất một tên bucket.
- [Low][clean-code] `.env:31` + `.env.prod.example:66` — set `KEYCLOAK_CLIENT_SECRET` cho client `ecommerce-client` vốn là public client (không có secret trong realm import) → giá trị vô dụng, cho cảm giác sai về hardening. Đề xuất: xóa khỏi template.
- (Tín dụng) `.env.prod.example` hướng dẫn rõ rotation 4 client secret với semant fail-closed (rỗng = từ chối ở Keycloak), header overlay mô tả đúng hành vi merge của compose.

### README.md / docker-README.md

- [Medium][documentation] `docker-README.md:29,78` — Keycloak Admin chỉ là `http://localhost:8080` (admin/admin) — sai port (9090), lặp lại lỗi của start-docker.sh; người mới chạy sẽ mở nhầm gateway. Đề xuất: sửa thành `http://localhost:9090`, đánh dấu gateway là 8080.
- [Medium][documentation] `README.md:12` — tech stack khai "Apache Kafka 4.x" nhưng compose dùng `apache/kafka:3.9.0` (`docker-compose.yml:87`) → doc lệch thực tế, dễ khiến dùng Kafka client/API sai version. Đề xuất: sửa thành 3.9 (KRaft) hoặc nâng image nếu chủ đích 4.x.
- [Low][documentation] `docker-README.md:71` — "Total: 20 containers" trong khi compose định nghĩa 21 (thiếu mock-payment-provider). Đề xuất: cập nhật số đếm hoặc ghi "(+1 mock PSP)".
- [Low][documentation] `docker-README.md:15-16` — "start-docker.sh will skip the rebuild step when images exist" không đúng (script luôn build, xem finding start-docker.sh). Đề xuất: sửa miêu tả.
- [Low][documentation] `docker-README.md` — không nhắc gì đến `docker-compose.prod.yml`/`.env.prod` và không link tới `docs/PRODUCTION-READINESS.md`; người chỉ đọc docker-README không biết đường deploy prod. Đề xuất: thêm mục "Production" ngắn + link runbook.

### docs/scripts/

- [Low][error-handling] `docs/scripts/e2e-test.sh:80-88` — parse JSON bằng `python3 -c` không có guard; nếu service chết/body không phải JSON, traceback Python thô; kết hợp `set -e` + `curl` fail (connection refused) khiến script thoát giữa chừng, không in summary. Đề xuất: parse qua hàm có kiểm tra lỗi, dùng `curl --fail --retry`.
- [Low][dry] `docs/scripts/e2e-test.sh` vs `e2e-favourite-inventory.sh:24-72` — bộ helper (assert_http, assert_contains, curl_code...) copy-paste gần như nguyên khối giữa 2 script; dùng chung file thư viện `lib/e2e-common.sh` khi có script thứ 3. Cả hai dùng global `/tmp/last_body` → không chạy song song được.
- (Tín dụng) Cả 2 script có `set -e`, PASS/FAIL counter, exit code tường minh khi có failure — tốt hơn trung bình các script e2e ad-hoc.

## Bảng tổng kết findings

| Severity \ Category | pattern | security | performance | documentation | clean-code | dry | error-handling | Tổng |
|---|---|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 1 | 1 | 0 | 0 | 0 | 0 | 0 | **2** |
| Medium | 2 | 6 | 1 | 3 | 0 | 0 | 1 | **13** |
| Low | 1 | 4 | 1 | 4 | 3 | 2 | 1 | **17** |
| **Tổng** | **4** | **11** | **2** | **7** | **3** | **2** | **2** | **32** |

(Gộp: High gồm notification-service thiếu `*pg-creds` và realm import chứa credential mặc định dùng cho prod.)

## Đánh giá chung mức độ trưởng thành

Phần hạ tầng đạt mức **khá cho giai đoạn dev → tiền-prod** (khoảng 6/10): dùng YAML anchors DRY đúng chỗ, healthcheck đầy đủ cho 21 container, `depends_on: condition: service_healthy` nhất quán, overlay prod được thiết kế có chủ đích (ingress-only, rotation fail-closed, audit volume, tài liệu XFF), script shell có fail-fast/confirm-giữ-dữ-liệu tốt, `.env` được quản lý git đúng chuẩn. Điểm hụt lớn nhất nằm ở **boundary dev↔prod**: toàn bộ credential dev (realm import, `changeme`, Keycloak `start-dev`) trôi sang production nếu chỉ chạy overlay mà không thực hiện rotation thủ công — mô hình "an toàn nếu operator nhớ" (documented footgun) thay vì "an toàn mặc định". Việc thiếu resource limits/JVM flags và một số inconsistency (notification-service thiếu pg-creds, port Keycloak sai trong doc/script) là các vết dầu mỡ cần dọn trước khi coi stack này production-ready. Khuyến nghị ưu tiên: (1) tách realm import dev/prod, (2) fix notification-service env, (3) per-service DB role, (4) mem/CPU limits + non-root image.