# Docker + Spring Cloud Gateway Setup Plan

> Workspace: `/Users/tonminh-mac/IdeaProjects/untitled5`
> Stack: Spring Boot 4.1.1 + Java 25 (Temurin) + Maven 3.9+
> Reference: https://github.com/hoangtien2k3/ecommerce-microservices

---

## Goal

Thiết lập Docker Compose cho **14 services** (1 Spring Cloud Gateway + 13 microservices) chạy local, kèm infrastructure đầy đủ (Postgres, Redis, Kafka, Elasticsearch, Keycloak, RustFS). Build Docker images bằng **Jib** plugin (đã config sẵn trong pom.xml gốc).

---

## Phase 1: Spring Cloud Gateway Module

Tạo module `gateway-service/` mới để làm edge gateway, điều hướng request tới 13 services.

### 1.1 Files to create

| File | Purpose |
|------|---------|
| `gateway-service/pom.xml` | Maven module — Spring Cloud Gateway + Reactive |
| `gateway-service/src/main/java/com/shop/gateway/GatewayServiceApplication.java` | Main app với `@EnableDiscoveryClient` (nếu có Eureka), `@SpringBootApplication` |
| `gateway-service/src/main/resources/application.yml` | Route config: 13 routes → 13 services, JWT validation, CORS, rate limiting |
| `gateway-service/src/main/resources/logback-spring.xml` | Logging config |

### 1.2 Routes mapping (Spring Cloud Gateway → backend)

```
Gateway routes:
  /api/v1/auth/**          → auth-service:8088
  /api/v1/products/**      → product-service:8086
  /api/v1/orders/**        → order-service:8084
  /api/v1/payments/**      → payment-service:8085
  /api/v1/shipping/**      → shipping-service:8087
  /api/v1/inventory/**     → inventory-service:8082
  /api/v1/favourites/**    → favourite-service:8081
  /api/v1/ratings/**       → rating-service:8089
  /api/v1/media/**         → media-service:8083
  /api/v1/tax/**           → tax-service:8091
  /api/v1/promotions/**    → promotion-service:8093
  /api/v1/search/**        → search-service:8094
  /api/v1/notifications/** → notification-service:8090
```

### 1.3 Parent pom.xml updates

- Thêm `<module>gateway-service</module>`
- Thêm `spring-cloud-bom` import trong `dependencyManagement`
- Thêm `spring-cloud-starter-gateway` property (`${spring-cloud.version}`)

---

## Phase 2: Docker Infrastructure Files

### 2.1 Files to create

| File | Purpose |
|------|---------|
| `.env` | Environment variables cho tất cả services |
| `docker-compose.yml` | Full stack orchestration |
| `.dockerignore` | Ignore target/, .idea/, .run/, *.iml |
| `docker/postgres/init/create-all-databases.sql` | 12 databases (keycloak + 11 service DBs) |
| `docker/keycloak/import/ecommerce-realm.json` | Realm "ecommerce" với 2 users (testuser, adminuser) |

### 2.2 .env structure

```bash
# PostgreSQL
POSTGRES_USER=admin
POSTGRES_PASSWORD=admin

# Redis
REDIS_PASSWORD=admin

# Keycloak
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
KEYCLOAK_CLIENT_SECRET=<generate>
KEYCLOAK_PUBLIC_SERVER_URL=http://localhost:8080

# Kafka
KAFKA_SERVERS=kafka:9092

# RustFS (S3)
STORAGE_ACCESS_KEY=admin
STORAGE_SECRET_KEY=admin
STORAGE_BUCKET=ecommerce-media

# Elasticsearch
ELASTICSEARCH_URL=http://elasticsearch:9200

# Mail
MAIL_USERNAME=hoangtien2k3qx1@yopmail.com
MAIL_PASSWORD=ecommerce@!@#

# JWT
JWT_ISSUER_URI=http://keycloak:8080/realms/ecommerce
```

### 2.3 docker-compose.yml services

```
infrastructure/
├── postgres:16-alpine        port 5432
├── redis:7.4-alpine         port 6379
├── kafka:3.9.0 (KRaft)      port 9092
├── elasticsearch:8.15.0     port 9200
├── keycloak:26.0            port 8080
└── rustfs:latest            port 9000/9001

gateway/
└── gateway-service          port 8080

backend/ (13 services)
├── auth-service             port 8088
├── product-service          port 8086
├── order-service            port 8084
├── payment-service          port 8085
├── shipping-service         port 8087
├── inventory-service        port 8082
├── favourite-service        port 8081
├── rating-service           port 8089
├── media-service            port 8083
├── tax-service              port 8091
├── promotion-service        port 8093
├── search-service           port 8094
└── notification-service     port 8090
```

### 2.4 docker/postgres/init/create-all-databases.sql

```sql
CREATE DATABASE keycloak;
CREATE DATABASE authservice;
CREATE DATABASE productservice;
CREATE DATABASE orderservice;
CREATE DATABASE paymentservice;
CREATE DATABASE shippingservice;
CREATE DATABASE inventoryservice;
CREATE DATABASE favouriteservice;
CREATE DATABASE ratingservice;
CREATE DATABASE mediaservice;
CREATE DATABASE taxservice;
CREATE DATABASE promotionservice;
```

### 2.5 docker/keycloak/import/ecommerce-realm.json

Realm config với:
- Realm name: `ecommerce`
- 3 roles: ADMIN, USER, MANAGER
- 1 public client: `ecommerce-client`
- 2 users:
  - `testuser` / `testpass` (USER role)
  - `adminuser` / `adminpass` (ADMIN + MANAGER roles)

---

## Phase 3: Jib Build Configuration

### 3.1 Update jib-maven-plugin trong parent pom.xml

Mỗi service có `<image>` tag riêng để build với tên chính xác:

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <version>3.5.2</version>
    <configuration>
        <from><image>eclipse-temurin:25-jre-alpine</image></from>
        <container>
            <format>OCI</format>
            <jvmFlags>
                <jvmFlag>-Djava.security.egd=file:/dev/./urandom</jvmFlag>
            </jvmFlags>
        </container>
    </configuration>
</plugin>
```

### 3.2 Build command

```bash
# Build all services vào local Docker daemon
mvn clean jib:dockerBuild -Djib.skip=false

# Hoặc từng service
mvn -pl gateway-service -am jib:dockerBuild
mvn -pl auth-service -am jib:dockerBuild
# ...
```

### 3.3 Image names

Tất cả images sẽ có tên: `<service-name>:latest`
- `gateway-service:latest`
- `auth-service:latest`
- `product-service:latest`
- ...

Docker-compose sẽ reference trực tiếp các images này qua `image: <service>:latest`.

---

## Phase 4: Scripts & Documentation

### 4.1 Files to create

| File | Purpose |
|------|---------|
| `start-docker.sh` | One-shot script: build images + start containers + show logs |
| `stop-docker.sh` | Stop + remove containers (giữ volumes) |
| `docker-README.md` | Hướng dẫn sử dụng |

### 4.2 start-docker.sh workflow

```bash
#!/bin/bash
set -e

echo "==> Step 1: Build Docker images với Jib"
mvn clean jib:dockerBuild -Djib.skip=false

echo "==> Step 2: Start infrastructure"
docker-compose up -d postgres redis kafka elasticsearch keycloak rustfs

echo "==> Step 3: Wait for infrastructure ready (60s)"
sleep 60

echo "==> Step 4: Start gateway + backend"
docker-compose up -d

echo "==> Step 5: Show status"
docker-compose ps
echo ""
echo "Gateway:     http://localhost:8080"
echo "Keycloak:    http://localhost:8080/admin"
echo "Postgres:    localhost:5432"
echo "Elastic:     http://localhost:9200"
```

---

## Phase 5: Verification

### 5.1 Health checks

```bash
# Gateway health
curl http://localhost:8080/actuator/health

# Service qua gateway
curl http://localhost:8080/api/v1/products/health

# Direct service health (nếu cần)
curl http://localhost:8086/actuator/health

# Keycloak
curl http://localhost:8080/realms/ecommerce

# Postgres
docker exec -it postgres psql -U admin -l

# Elasticsearch
curl http://localhost:9200/_cluster/health
```

### 5.2 Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f auth-service

# Tail last 100 lines
docker-compose logs --tail=100 gateway-service
```

### 5.3 Test authentication flow

```bash
# Get JWT token
TOKEN=$(curl -X POST http://localhost:8080/realms/ecommerce/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=ecommerce-client" \
  -d "username=testuser" \
  -d "password=testpass" | jq -r '.access_token')

# Call protected API
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/products
```

---

## Tech Stack Differences vs Original Repo

| Component | Original (Java 21) | Workspace (Java 25) |
|-----------|-------------------|---------------------|
| Spring Boot | 3.3.5 | 4.1.1 |
| Java | 21 | 25 |
| Base image | eclipse-temurin:21-jre-alpine | eclipse-temurin:25-jre-alpine |
| Gateway | Apache APISIX 3.9.1 | Spring Cloud Gateway |
| Resilience4j | 2.2.0 (sb3) | 2.4.0 (sb4) |
| Frontend | Next.js 16 | ❌ excluded |
| Postgres | 16 | 16 ✅ same |
| Kafka | 3.9.0 KRaft | 3.9.0 KRaft ✅ same |
| Redis | 7.4-alpine | 7.4-alpine ✅ same |
| Elasticsearch | 8.15.0 | 8.15.0 ✅ same |
| Keycloak | 26.0 | 26.0 ✅ same |
| Storage | RustFS | RustFS ✅ same |

---

## Execution Order

```
1. [Phase 1] Tạo gateway-service module
2. [Phase 1] Update parent pom.xml
3. [Phase 1] Verify: mvn -pl gateway-service compile

4. [Phase 2] Tạo .env
5. [Phase 2] Tạo docker/postgres/init/create-all-databases.sql
6. [Phase 2] Tạo docker/keycloak/import/ecommerce-realm.json
7. [Phase 2] Tạo docker-compose.yml
8. [Phase 2] Tạo .dockerignore

9. [Phase 3] Verify Jib config trong parent pom
10. [Phase 3] Build images: mvn clean jib:dockerBuild

11. [Phase 4] Tạo start-docker.sh, stop-docker.sh

12. [Phase 5] docker-compose up -d
13. [Phase 5] Verify health endpoints
14. [Phase 5] Test auth flow
```

---

## Files Summary (Total: ~15 files)

### Tạo mới:
1. `gateway-service/pom.xml`
2. `gateway-service/src/main/java/com/shop/gateway/GatewayServiceApplication.java`
3. `gateway-service/src/main/resources/application.yml`
4. `gateway-service/src/main/resources/logback-spring.xml`
5. `.env`
6. `.dockerignore`
7. `docker-compose.yml`
8. `docker/postgres/init/create-all-databases.sql`
9. `docker/keycloak/import/ecommerce-realm.json`
10. `start-docker.sh`
11. `stop-docker.sh`
12. `docker-README.md`

### Update:
13. `pom.xml` — thêm gateway-service module, spring-cloud BOM, spring-cloud-starter-gateway

### Skip (đã có sẵn):
- Jib plugin config trong parent pom.xml
- Docker daemon (user cần start Docker Desktop trước)

---

## Prerequisites

1. **Docker Desktop running** (macOS)
2. **8 GB+ RAM** allocated to Docker
3. **Java 25 toolchain** đã setup (đã làm ở phase trước)
4. **Maven 3.9+** working với JDK 25 toolchain
5. **Ports free**: 5432, 6379, 8080, 8081-8094, 9000, 9001, 9092, 9200

---

## Estimated Time

- Phase 1 (Gateway): 20-30 phút
- Phase 2 (Docker infra): 15-20 phút
- Phase 3 (Jib build): 10-15 phút (compile + push to Docker daemon)
- Phase 4 (Scripts): 5 phút
- Phase 5 (Verification): 10-15 phút

**Total: ~1-1.5 giờ**
