# Docker and Spring Cloud Gateway Setup Plan

> **Workspace**: `/Users/tonminh-mac/IdeaProjects/untitled5`
> **Stack**: Spring Boot 4.1.1, Java 25 (Temurin), Maven 3.9+
> **Reference**: https://github.com/hoangtien2k3/ecommerce-microservices

---

## Goal

Configure Docker Compose for 14 services (1 Spring Cloud Gateway and 13 microservices) running locally with complete supporting infrastructure (PostgreSQL, Redis, Kafka, Elasticsearch, Keycloak, and RustFS). Build container images using the Google Jib plugin configured in the root POM.

---

## Phase 1: Spring Cloud Gateway Module

Create the `gateway-service/` module as an edge gateway to route requests across the 13 backend services.

### 1.1 Files to create

| File | Purpose |
|---|---|
| `gateway-service/pom.xml` | Maven configuration for Spring Cloud Gateway and Reactive WebFlux |
| `gateway-service/src/main/java/com/shop/gateway/GatewayServiceApplication.java` | Spring Boot main application class |
| `gateway-service/src/main/resources/application.yml` | Route configuration across backend services, JWT validation, CORS, rate limiting |
| `gateway-service/src/main/resources/logback-spring.xml` | Structured logging configuration |

### 1.2 Route mapping (Gateway to backend services)

```
Gateway routes:
  /api/v1/auth/**          -> auth-service:8088
  /api/v1/products/**      -> product-service:8086
  /api/v1/orders/**        -> order-service:8084
  /api/v1/payments/**      -> payment-service:8085
  /api/v1/shipping/**      -> shipping-service:8087
  /api/v1/inventory/**     -> inventory-service:8082
  /api/v1/favourites/**    -> favourite-service:8081
  /api/v1/ratings/**       -> rating-service:8089
  /api/v1/medias/**        -> media-service:8083
  /api/v1/tax/**           -> tax-service:8091
  /api/v1/promotions/**    -> promotion-service:8093
  /api/v1/search/**        -> search-service:8094
  /api/v1/notifications/** -> notification-service:8090
```

### 1.3 Parent pom.xml updates

- Register `<module>gateway-service</module>`
- Import `spring-cloud-dependencies` in `dependencyManagement`
- Add `spring-cloud-starter-gateway` dependency properties

---

## Phase 2: Docker Infrastructure Files

### 2.1 Files to create

| File | Purpose |
|---|---|
| `.env` | Environment variable values across all services |
| `docker-compose.yml` | Multi-container orchestration definition |
| `.dockerignore` | Build context exclusions |
| `docker/postgres/init/create-all-databases.sql` | Initialization script for 12 databases (Keycloak + 11 service databases) |
| `docker/keycloak/import/ecommerce-realm.json` | Keycloak realm definition with seed roles and test users |

### 2.2 Environment configuration structure

```bash
# PostgreSQL
POSTGRES_USER=admin
POSTGRES_PASSWORD=admin
POSTGRES_DB=postgres
POSTGRES_PORT=5432

# Redis
REDIS_PASSWORD=admin
REDIS_PORT=6379

# Keycloak
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
KEYCLOAK_CLIENT_SECRET=admin
KEYCLOAK_PUBLIC_SERVER_URL=http://localhost:8080

# Kafka
KAFKA_SERVERS=kafka:9092

# RustFS (S3 compatible)
STORAGE_ACCESS_KEY=admin
STORAGE_SECRET_KEY=admin
STORAGE_BUCKET=ecommerce-media

# Elasticsearch
ELASTICSEARCH_URL=http://elasticsearch:9200
ES_ADMIN_USERNAME=elastic
ES_ADMIN_PASSWORD=admin

# Mail configuration
MAIL_USERNAME=notifications@example.com
MAIL_PASSWORD=password

# JWT issuer
JWT_ISSUER_URI=http://keycloak:8080/realms/ecommerce
```

### 2.3 Container topology

```
infrastructure/
├── postgres:16-alpine        port 5432
├── redis:7.4-alpine          port 6379
├── kafka:3.9.0 (KRaft)       port 9092
├── elasticsearch:8.15.0      port 9200
├── keycloak:26.0             port 8080 (internal), 9090 (host)
└── rustfs:latest             port 9000/9001

gateway/
└── gateway-service           port 8080

backend/ (13 services)
├── auth-service              port 8088
├── product-service           port 8086
├── order-service             port 8084
├── payment-service           port 8085
├── shipping-service          port 8087
├── inventory-service         port 8082
├── favourite-service         port 8081
├── rating-service            port 8089
├── media-service             port 8083
├── tax-service               port 8091
├── promotion-service         port 8093
├── search-service            port 8094
└── notification-service      port 8090
```

### 2.4 Keycloak realm defaults

Pre-configured accounts:
- Realm name: `ecommerce`
- Roles: `ADMIN`, `USER`, `MANAGER`
- Client: `ecommerce-client`
- Seed users:
  - `testuser` / `changeme` (role: USER)
  - `adminuser` / `adminpass` (roles: ADMIN, MANAGER)

---

## Phase 3: Jib Build Configuration

### 3.1 Jib plugin settings

Each microservice builds an OCI container image directly to the local Docker daemon using Google Jib:

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

### 3.2 Build commands

```bash
# Build all service images to the local Docker daemon
./mvnw clean package jib:dockerBuild -DskipTests

# Or build a single service image
./mvnw -pl gateway-service -am package jib:dockerBuild -DskipTests
```

---

## Phase 4: Operational Scripts and Documentation

### 4.1 Script files

| File | Purpose |
|---|---|
| `start-docker.sh` | Builds images, boots infrastructure, waits for health, and launches microservices |
| `stop-docker.sh` | Gracefully stops and cleans up containers while preserving data volumes |
| `docker-README.md` | Docker-specific operational commands and troubleshooting guide |

---

## Phase 5: Verification

### 5.1 Health checks

```bash
# Gateway health check
curl http://localhost:8080/actuator/health

# Service health check through gateway
curl http://localhost:8080/actuator/health

# Direct product service check
curl http://localhost:8086/actuator/health

# Keycloak realm metadata check
curl http://localhost:9090/realms/ecommerce

# Elasticsearch cluster health
curl -u elastic:admin http://localhost:9200/_cluster/health
```

### 5.2 Container logs

```bash
# Follow logs across all containers
docker compose logs -f

# Follow logs for an individual service
docker compose logs -f auth-service
```

---

## Technology Stack Comparison

| Component | Original reference repo | Current workspace |
|---|---|---|
| Spring Boot | 3.3.5 | 4.1.1 |
| Java runtime | Java 21 | Java 25 |
| Base Docker image | eclipse-temurin:21-jre-alpine | eclipse-temurin:25-jre-alpine |
| Edge gateway | Apache APISIX | Spring Cloud Gateway (Reactive) |
| Resilience4j | 2.2.0 | 2.4.0 |
| PostgreSQL | 16 | 16 |
| Apache Kafka | 3.9.0 (KRaft) | 3.9.0 (KRaft) |
| Redis | 7.4-alpine | 7.4-alpine |
| Elasticsearch | 8.15.0 | 8.15.0 |
| Keycloak | 26.0 | 26.0 |
| Object storage | RustFS | RustFS |
