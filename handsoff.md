# Tài Liệu Bàn Giao (Handoff) — Petproject Microservices Platform

> **Ngày cập nhật**: 2026-09-03  
> **Repository**: `git@github.com:tonminhce/petproject.git` (nhánh `main`)  
> **Thư mục làm việc**: `/home/tonminh/Documents/petproject`  
> **Môi trường & Công nghệ**: Spring Boot 4.1.1 / Java 25 OpenJDK (`/usr/lib/jvm/java-25-openjdk`) / Keycloak 26 / PostgreSQL 16 / Redis 7.4 / Kafka 3.9.0 (KRaft) / Elasticsearch 8.15 / RustFS S3 Storage  

---

## 1. Tổng Quan Kiến Trúc & Trạng Thái Hệ Thống

Dự án gồm **23 Maven modules** (14 microservices + 8 utils / shared starter libraries + parent pom):

| Service | Cổng Host | Container Name | Trạng thái Docker | Trách nhiệm chính |
|---|:---:|---|:---:|---|
| **gateway-service** | 8080 | `gateway-service` | **HEALTHY** | Spring Cloud Gateway, JWT verification, Redis rate limiting, CORS, edge routing |
| **auth-service** | 8088 | `auth-service` | **HEALTHY** | Keycloak facade, login, refresh, logout, shadow user CRUD, role management |
| **product-service** | 8086 | `product-service` | **HEALTHY** | Quản lý sản phẩm, danh mục, thương hiệu, Redis caching, outbox event publishing |
| **inventory-service** | 8082 | `inventory-service` | **HEALTHY** | Quản lý tồn kho, giữ hàng (reservation), auto-release sweep, release-committed |
| **order-service** | 8084 | `order-service` | **HEALTHY** | Giỏ hàng, tạo đơn hàng, saga choreography, chuyển trạng thái đơn hàng |
| **payment-service** | 8085 | `payment-service` | **HEALTHY** | Tạo thanh toán, capture, refund, idempotency key enforcement |
| **shipping-service** | 8087 | `shipping-service` | **HEALTHY** | Lắng nghe Kafka tạo vận đơn, gán mã tracking, chuyển trạng thái giao hàng, webhook carrier |
| **notification-service** | 8090 | `notification-service` | **HEALTHY** | Lắng nghe Kafka order events, gửi thông báo, lưu lịch sử thông báo |
| **rating-service** | 8089 | `rating-service` | **HEALTHY** | Đánh giá sao, kiểm tra xác minh đơn hàng đã giao (`RTG-11001`), ẩn/hiện đánh giá |
| **search-service** | 8094 | `search-service` | **HEALTHY** | Elasticsearch indexer, tìm kiếm fuzzy/full-text, reindex catalog, nhận rating update |
| **tax-service** | 8091 | **HEALTHY** | `tax-service` | Quản lý Tax Class, Tax Rate theo quốc gia/bang, tính toán thuế theo tỷ lệ |
| **promotion-service** | 8093 | `promotion-service` | **HEALTHY** | Chiến dịch khuyến mãi, mã giảm giá (PERCENT/FIXED), giữ và commit mã |
| **favourite-service** | 8081 | `favourite-service` | **HEALTHY** | Danh sách sản phẩm yêu thích (Wishlist) của người dùng |
| **media-service** | 8083 | `media-service` | **HEALTHY** | Upload ảnh, kiểm tra magic-byte MIME type, sinh 6 biến thể WebP/PNG, lưu RustFS |

### Infrastructure Containers:
- **keycloak** (8080 nội bộ, realm `ecommerce`): `admin` / `admin`. Seed users: `adminuser` / `adminpass` (ADMIN, MANAGER), `testuser` / `changeme` (USER).
- **postgres** (5432): `max_connections=300`. Đã mount và chạy ổn định.
- **redis** (6379): Password `admin` (`docker exec redis redis-cli -a admin`).
- **kafka** (9092, KRaft mode): Cluster id `MkU3OEVBNTcwNTJENDM2Qk`.
- **elasticsearch** (9200): Single node, security disabled.
- **rustfs** (9000, S3 compatible API): Lưu trữ media và ảnh biến thể.

---

## 2. GitNexus Harness (BẮT BUỘC DÙNG KHI CODE)

Dự án đã được index bằng GitNexus với **11,218 symbols, 25,091 relationships, 300 execution flows**.

### 2.1 File Harness & Script
- **Vị trí**: `/home/tonminh/Documents/petproject/utils/gitnexus-harness.sh`
- **Cách dùng nhanh trong terminal**:
  ```bash
  # Cấp quyền thực thi nếu cần
  chmod +x utils/gitnexus-harness.sh
  
  # Tạo alias tạm thời hoặc gọi trực tiếp:
  alias g="/home/tonminh/Documents/petproject/utils/gitnexus-harness.sh"
  
  # Kiểm tra trạng thái index
  g status
  
  # Cập nhật / phân tích lại index khi sửa code
  g fresh  # hoặc: node .gitnexus/run.cjs analyze
  
  # Tìm kiếm luồng thực thi theo concept (nhanh hơn grep rất nhiều)
  g flow "create order"
  g flow "verify purchase"
  
  # Xem 360 độ về một symbol (callers, callees, processes tham gia)
  g here OrderService
  g here CategoryServiceImpl
  
  # BẮT BUỘC TRƯỚC KHI SỬA CODE: Blast Radius / Impact Analysis
  g blast OrderController -d upstream
  g blast RoleRepository -d upstream
  
  # BẮT BUỘC TRƯỚC KHI COMMIT: Kiểm tra phạm vi ảnh hưởng
  g changed
  ```

### 2.2 Quy tắc GitNexus bắt buộc (từ AGENTS.md):
1. **MUST run impact analysis (`g blast <symbol> -d upstream`)** trước khi sửa bất kỳ hàm, class, hoặc method nào. Báo cáo bán kính ảnh hưởng cho người dùng.
2. **MUST warn user** nếu impact analysis trả về rủi ro **HIGH** hoặc **CRITICAL**.
3. **NEVER rename symbols bằng find-and-replace** — phải dùng rename có hiểu biết về đồ thị gọi hàm.
4. **MUST run `g changed` trước khi commit** để đảm bảo không làm rò rỉ tác động ra ngoài phạm vi mong đợi.

---

## 3. Lịch Sử Commit & Tiến Độ Công Việc Đã Hoàn Thành

Tất cả các commit sau đây đã được kiểm tra, format chuẩn, 0 lỗi Checkstyle và **ĐÃ PUSH LÊN `origin/main`**:

1. `d9249d0` - `fix(cleanup): resolve C-5, H-11, H-12, H-13, L-3, L-6`: Xử lý dọn dẹp log, retention và audit table.
2. `b34bfb1` - `fix(security): resolve C-2, C-3, C-4, H-1, H-2, L-1, L-2, L-5`: Bảo mật SQL injection, token isolation, CORS, upload storage bounds.
3. `8c23cf8` - `fix(domain): resolve H-6, H-7, H-9, H-10, H-14, M-6`: Chuyển đổi event sang Java record, fix actor auditing, chuẩn hóa imports.
4. `c650fcb` - `perf(db): resolve M-1, M-2`: Thêm composite index cho database và `@Builder.Default` cho entity.
5. `a52f7b6` - `fix(notification): resolve M-13`: Fix SMTP fallback và an toàn cho notification delivery.
6. `7c18355` - `fix(search): resolve L-12`: Chuẩn hóa Elasticsearch search sort fields.
7. `1f2ee4d` - `fix(api): resolve M-12, M-13, L-4`: API hardening, cache headers, rate limiting.
8. `87191dc` - `fix(e2e): deploy and verify all 14 microservices in docker-compose with newman suite`:
   - Fix `RoleRepository.findByNameIn(Collection<RoleName>)` trong `auth-service`.
   - Fix lỗi Redis deserialization cache `findAll()` và `findTree()` trong `CategoryServiceImpl.java`.
   - Fix lỗi compile `Duration.ofMillis(timeout)` trong `media-service`.
   - Nâng `max_connections=300` cho PostgreSQL để tránh cạn kiệt kết nối.
   - Sửa kiểu dữ liệu `@JdbcTypeCode(Types.CHAR)` cho `TaxRate.country`.
   - Cấu hình chuẩn URL liên dịch vụ (`PRODUCT_SERVICE_URL`, `INVENTORY_SERVICE_URL`) trong Docker.
9. `7a37f7b` - `test(e2e): add comprehensive business lifecycle Postman collection and update test report (144/144 passed)`.
10. `7ec8584` - `test(e2e): expand Postman suite with full happy and negative edge cases (158/158 passed)`:
    - Bổ sung [`AuthExceptionHandler.java`](file:///home/tonminh/Documents/petproject/auth-service/src/main/java/com/shop/authservice/exception/AuthExceptionHandler.java) xử lý `KeycloakClientException` trả về `401 Unauthorized` (`ERR-0401`) thay vì lỗi 500.
    - Mở rộng thêm 14 edge cases (validation lỗi giá âm, số lượng giỏ hàng bằng 0, mã quốc gia không đúng chuẩn, giữ hàng vượt tồn kho, rating khi chưa mua hàng).
11. `82cc8e8` - `test(e2e): achieve 100% controller endpoint coverage in Postman (110/110 endpoints, 159/159 tests passed)`:
    - Rà soát 100% mã nguồn Controller, bổ sung endpoint nội bộ `product-media-references` và sửa path param cho `shipping carrier webhook`.

---

## 4. Postman Suites & Độ Phủ 100% Endpoints

Hệ thống có **2 bộ sưu tập Postman chuẩn production** được quản lý tại thư mục `docs/postman/`:

### 4.1 Bộ 1: Chu trình Nghiệp vụ E2E & Edge Cases ([`petproject-e2e-business-flow.postman_collection.json`](file:///home/tonminh/Documents/petproject/docs/postman/petproject-e2e-business-flow.postman_collection.json))
- **Quy mô**: 45 requests chia thành 14 folders logic.
- **Dữ liệu động**: Chaining tự động UUID, JWT tokens, SKU, Slug, Idempotency keys.
- **Nội dung kiểm thử**:
  - **Folders 1-10 (Happy Path - 31 requests)**: Đăng nhập -> Tạo danh mục/thương hiệu/sản phẩm -> Xem sản phẩm storefront -> Nhập kho 100 cái -> Thêm giỏ hàng -> Đặt đơn hàng -> Admin Confirm -> Admin Ship -> Admin Deliver -> Khởi tạo thanh toán & Capture thanh toán -> Đánh giá sao (verified buyer) -> Xem đánh giá -> Thêm Wishlist Favourites -> Reindex Elasticsearch & tìm kiếm full-text -> Xem thông báo trạng thái đơn hàng -> Tính thuế & tạo khuyến mãi -> Gọi qua Spring Cloud Gateway port 8080.
  - **Folders 11-14 (Edge Cases & Negative - 14 requests)**:
    - 11. Auth Edge: Sai mật khẩu (401 `ERR-0401`), thiếu token (401), user thường gọi API admin (403 `ERR-0403`).
    - 12. Catalog Edge: Tạo sản phẩm giá âm (400 `ERR-0422-V`), user tạo sản phẩm (403), tìm UUID không tồn tại (404).
    - 13. Inventory Edge: Thêm giỏ hàng số lượng 0 (400 `ERR-0422-V`), thêm sản phẩm ma (404 `PRD-2001`), nhập kho số lượng âm (400), giữ hàng vượt tồn kho (409 `INV-3002`).
    - 14. Order/Rating/Tax/Media Edge: Đánh giá khi chưa mua (403 `RTG-11001`), gửi 10 sao (400 `ERR-0422-V`), mã nước sai format ISO (400 `ERR-0422-V`), upload media không phải multipart (400 `ERR-0400`).

### 4.2 Bộ 2: Toàn bộ Danh mục API của Hệ Thống ([`petproject-comprehensive.postman_collection.json`](file:///home/tonminh/Documents/petproject/docs/postman/petproject-comprehensive.postman_collection.json))
- **Quy mô**: 114 requests.
- **Độ phủ Controller**: **110 / 110 endpoints có trong code (100% Code Coverage)**.
- Đã test và xác nhận không có bất kỳ endpoint nào bị bỏ sót hay trả về 500 lỗi hệ thống.

### 4.3 Lệnh Chạy Toàn Bộ Test với Newman:
```bash
# 1. Chạy luồng nghiệp vụ E2E và Edge cases (45 requests)
npx --yes newman run docs/postman/petproject-e2e-business-flow.postman_collection.json

# 2. Chạy toàn bộ kho API 14 dịch vụ (114 requests)
npx --yes newman run docs/postman/petproject-comprehensive.postman_collection.json
```
*Kết quả hiện tại: **159 / 159 assertions PASSED (100%), 0 FAILURES**.*

---

## 5. Quy Tắc Cốt Lõi Khi Làm Việc (User Hard Rules)

1. **Vừa làm vừa commit và push lên GitHub**:
   - Mọi task làm xong phải commit rõ ràng theo Conventional Commits và push trực tiếp lên `origin/main` qua SSH (`git push origin main`).
2. **Quy tắc Import trong Java**:
   - **TẤT CẢ imports phải đặt ở đầu file**. Tuyệt đối không được dùng FQCN (Fully Qualified Class Name inline, ví dụ `java.util.concurrent.atomic.AtomicReference<>` trong thân class).
   - Kiểm tra Checkstyle: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -T1C validate`. Phải đảm bảo **0 Checkstyle violations**.
3. **Môi trường Java**:
   - Java 25: Luôn set `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`.
4. **GitNexus Intelligence**:
   - Bắt buộc chạy `g blast <symbol> -d upstream` trước khi sửa symbol và chạy `g changed` trước khi commit.

---

## 6. Trạng Thái Hiện Tại & Các Bước Đề Xuất Tiếp Theo Cho Agent Sau

### Trạng thái hiện tại:
- **Toàn bộ 14 microservices**: Build pass, Checkstyle 0 violations, Docker containers healthy và live.
- **Toàn bộ 110 API endpoints**: 100% được phủ và kiểm thử qua Postman / Newman.
- **Tài liệu**: [`docs/postman/E2E_TEST_REPORT.md`](file:///home/tonminh/Documents/petproject/docs/postman/E2E_TEST_REPORT.md) và [`FINDING.md`](file:///home/tonminh/Documents/petproject/FINDING.md) đã được cập nhật đầy đủ chi tiết.

### Việc có thể làm tiếp theo (tùy yêu cầu user):
1. **CI/CD Pipeline & GitHub Actions**: Tích hợp các lệnh Newman E2E test vào GitHub Actions workflow để chạy tự động khi có Pull Request.
2. **Performance / Load Testing**: Chạy thử nghiệm tải với k6 hoặc JMeter trên API Gateway (8080) để kiểm tra Redis rate limiting và khả năng chịu tải của các dịch vụ.
3. **Observability & Distributed Tracing**: Kiểm tra tính liên tục của `traceId` và OpenTelemetry correlation giữa các service khi Kafka event được truyền tải.
