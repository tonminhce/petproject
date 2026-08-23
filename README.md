# shop-microservices

Production-grade e-commerce microservices platform built with Spring Boot 4.1.1 + Java 25.

## Tech stack

- **Runtime**: Java 25 (Temurin)
- **Framework**: Spring Boot 4.1.1
- **Build**: Maven 3.9.x (multi-module reactor)
- **Persistence**: PostgreSQL 16 + Liquibase
- **Search**: Elasticsearch (co.elastic.clients 9.4.x)
- **Messaging**: Apache Kafka 4.x
- **Security**: Keycloak 26 + OAuth2 Resource Server
- **Storage**: AWS S3 / RustFS (S3-compatible)
- **Resilience**: Resilience4j 2.4 (circuit breaker)
- **API Docs**: springdoc-openapi 3.1 (Swagger UI)

## Prerequisites

| Tool        | Version           | Notes                                    |
|-------------|-------------------|------------------------------------------|
| JDK         | 25 (Temurin LTS)  | `~/Library/Java/JavaVirtualMachines/temurin-25.0.3/Contents/Home` |
| Maven       | 3.9+              | `brew install maven`                     |
| Docker      | 24+               | For local infra (Postgres, Kafka, etc.)  |

The Maven build uses a JDK toolchain (`~/.m2/toolchains.xml`) so `JAVA_HOME` can point to anything
— the build always runs on JDK 25.

## Setup

### 1. JDK 25 toolchain (one-time)

```bash
mkdir -p ~/.m2
cat > ~/.m2/toolchains.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 http://maven.apache.org/xsd/toolchains-1.1.0.xsd">
    <toolchain>
        <type>jdk</type>
        <provides>
            <version>25</version>
            <vendor>temurin</vendor>
        </provides>
        <configuration>
            <jdkHome>/Users/tonminh-mac/Library/Java/JavaVirtualMachines/temurin-25.0.3/Contents/Home</jdkHome>
        </configuration>
    </toolchain>
</toolchains>
EOF
```

Verify:

```bash
mvn toolchains:display-discovered-jdk-toolchains
```

### 2. IntelliJ IDEA

1. **File → Project Structure → SDKs** → add `temurin-25.0.3`
   (IntelliJ auto-detects from `~/Library/Java/JavaVirtualMachines/`)
2. **File → Project Structure → Project** → set Project SDK to `25`, language level `25`
3. **File → Settings → Build, Execution, Deployment → Build Tools → Maven** → set
   `Maven home directory` to `/opt/homebrew/Cellar/maven/3.9.16/libexec` (or use the bundled `mvnw`)
4. Reimport Maven project (`pom.xml` → right-click → Maven → Reload)

### 3. Build everything

```bash
./mvnw clean compile        # compile all 22 modules
./mvnw clean install        # full build (compile + test + package)
./mvnw -pl auth-service spring-boot:run   # run a single service
```

## Project layout

```
shop-microservices/                 # parent aggregator (pom)
├── utils/                          # common libraries aggregator
│   ├── common-core/                # contracts, exceptions
│   ├── common-security/            # JWT / OAuth2
│   ├── common-logging/             # AOP perf logging
│   ├── common-keycloak/            # Keycloak admin client
│   ├── common-kafka/               # Kafka helpers
│   ├── common-spring/              # Spring Boot starter (drop-in)
│   └── common-storage/             # S3 object storage
├── auth-service/                   # :8088
├── product-service/                # :8086
├── order-service/                  # :8084
├── payment-service/                # :8085
├── inventory-service/              # :8082
├── shipping-service/               # :8087
├── notification-service/           # :8090
├── rating-service/                 # :8089
├── search-service/                 # :8094
├── promotion-service/              # :8093
├── tax-service/                    # :8091
├── favourite-service/              # :8081
└── media-service/                  # :8083
```

Every service depends on `common-spring` which transitively pulls in:
web, validation, actuator, OAuth2 resource server, Micrometer/Prometheus,
springdoc-openapi, mapstruct, modelmapper, jackson, AOP, dotenv.

## Verify

```bash
# Check compiled bytecode is Java 25 (major version 69)
javap -v auth-service/target/classes/com/shop/authservice/AuthServiceApplication.class | grep major
```

## Notes

- All modules use groupId `com.shop.microservices` and base package `com.shop.*`
- Java compiler release flag is set to 25 in `parent pom.xml`
- `flatten-maven-plugin` resolves `${revision}` for CI-friendly deployable poms
- Container images use `eclipse-temurin:25-jre-alpine`
