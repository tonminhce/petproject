# Docker Stack — E-commerce Microservices

> Two scripts to build, start, and stop the full 20-service Docker stack defined in
> [`docker-compose.yml`](docker-compose.yml).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Docker Desktop | 4.13+ (Compose v2) | Allocates **at least 4 GB RAM** in Settings → Resources |
| Java JDK | 25 | Only required if you want to (re)build service images |
| Maven | wrapper (`./mvnw`) | Ships with the repo — no global install needed |
| Bash | 4+ | macOS ships bash 3.2; the scripts also work on zsh |

> Tip: macOS users can just use the project without installing Java if they already have
> built images cached — `./start-docker.sh` will skip the rebuild step when images exist.

## Quick Start

```bash
# 1. One-time: copy env template (already present in this repo as .env)
#    Adjust passwords if needed.

# 2. Build images + start the full stack
./start-docker.sh

# 3. Browse
#    Gateway   http://localhost:8080
#    Keycloak  http://localhost:8080  (admin / admin)

# 4. Stop (keeps data)
./stop-docker.sh

# 5. Wipe everything (DESTRUCTIVE)
./stop-docker.sh --volumes --images
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                          Docker host                             │
│                                                                  │
│  ┌──────────────┐         ┌────────────────────────────────┐     │
│  │  Browser /   │────────▶│      gateway-service  :8080     │     │
│  │  curl / apps │         │   (Spring Cloud Gateway)       │     │
│  └──────────────┘         └────────┬───────────────────────┘     │
│                                    │                              │
│                ┌───────────────────┼───────────────────────┐      │
│                ▼                   ▼                       ▼      │
│       ┌─────────────┐      ┌─────────────┐         ┌─────────────┐│
│       │ auth-service│      │product-svc  │  … 12 more backends    │
│       │   :8088     │      │   :8086     │                        │
│       └─────────────┘      └─────────────┘                        │
│                │                   │                              │
│                └────────┬──────────┘                              │
│                         ▼                                         │
│  ┌────────────┐ ┌─────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ │
│  │ PostgreSQL │ │  Redis  │ │  Kafka │ │  Elastic │ │  RustFS  │ │
│  │   :5432    │ │  :6379  │ │  :9092 │ │   :9200  │ │  :9000   │ │
│  └────────────┘ └─────────┘ └────────┘ └──────────┘ └──────────┘ │
│                                                                  │
│                       ecommerce-network (bridge)                 │
└─────────────────────────────────────────────────────────────────┘
```

* 6 infrastructure containers (Postgres, Redis, Kafka KRaft, Elasticsearch, Keycloak, RustFS)
* 1 gateway (Spring Cloud Gateway — entry point on :8080)
* 13 backend microservices (auth, product, order, payment, shipping, inventory,
  favourite, rating, media, tax, promotion, search, notification)
* **Total: 20 containers**

## Service URLs

| Service | URL | Credentials |
|---|---|---|
| **Gateway** | http://localhost:8080 | — |
| **Keycloak Admin** | http://localhost:8080 | `admin` / `admin` |
| PostgreSQL | `localhost:5432` | `admin` / `admin`, db `ecommerce` |
| Redis | `localhost:6379` | password `admin` |
| Kafka | `localhost:9092` | — |
| Elasticsearch | `localhost:9200` | (no auth in dev) |
| RustFS S3 API | `localhost:9000` | `admin` / `admin` |
| RustFS Console | `localhost:9001` | `admin` / `admin` |

### Backend service ports (bypass gateway)

| Service | Port | | Service | Port |
|---|---|---|---|---|
| auth-service | 8088 | | product-service | 8086 |
| order-service | 8084 | | payment-service | 8085 |
| shipping-service | 8087 | | inventory-service | 8082 |
| favourite-service | 8081 | | rating-service | 8089 |
| tax-service | 8091 | | promotion-service | 8093 |
| search-service | 8094 | | notification-service | 8090 |

*media-service publishes no host port (dev or prod) — it is reached through
the gateway (:8080, ServiceRoute MEDIA).*

## Useful Commands

All commands assume you are in the repo root.

```bash
# Status of every container
docker compose ps

# Tail logs of one service (Ctrl-C to exit)
docker compose logs -f order-service

# Tail logs of everything
docker compose logs -f

# Restart a single service (e.g. after editing its .env)
docker compose restart payment-service

# Rebuild a single image and recreate its container
./mvnw -pl order-service -am jib:dockerBuild
docker compose up -d order-service

# Open a shell inside a container
docker compose exec postgres psql -U admin -d ecommerce

# List built images
docker images | grep -E '(gateway|auth|product|order|payment|shipping|inventory|favourite|rating|media|tax|promotion|search|notification)-service'
```

## Troubleshooting

### Port already in use

```
bind: address already in use 0.0.0.0:5432
```

Find and free the conflicting port:

```bash
# macOS / Linux
lsof -i :5432
# kill the PID printed above

# Or change the host port in .env (POSTGRES_PORT=5433 etc.)
```

### Image build fails with `Could not find goal 'jib:dockerBuild'`

Run from the **project root**, not a sub-module:

```bash
cd /path/to/untitled5
./mvnw jib:dockerBuild
```

### Container exits with `OOMKilled`

Elasticsearch, Kafka, and the Spring services need real RAM.

* Docker Desktop → Settings → Resources → **Memory → 4 GB or more**.
* If a single service is the culprit, raise `ES_JAVA_OPTS` in `docker-compose.yml`
  (current dev value: `-Xms512m -Xmx512m`).

### `Keycloak` never becomes healthy

Keycloak boot is slow (90 s `start_period` by default). The script waits up to
180 s; if it still fails:

```bash
docker logs keycloak | tail -50
# Common cause: Postgres init script failed → check
docker logs postgres | grep -i error
```

### Gateway returns 502 to a backend

A backend service hasn't finished starting yet. Watch it come up:

```bash
docker compose ps
docker compose logs -f --tail=200 product-service
```

### Docker daemon not running

```
[x] Docker daemon is not running. Start Docker Desktop and try again.
```

Start Docker Desktop and wait for the whale icon to stop animating, then re-run
`./start-docker.sh`.

### Clean slate

If the stack is wedged and you want to start over from scratch (DESTRUCTIVE —
deletes all DB data):

```bash
./stop-docker.sh --volumes --images
./start-docker.sh
```

## Data Persistence

The script does **not** remove volumes on stop. Data is kept across `./stop-docker.sh` /
`./start-docker.sh` cycles in these named Docker volumes:

| Volume | Backing service |
|---|---|
| `postgres_data` | PostgreSQL (all 13 service DBs + Keycloak DB) |
| `redis_data` | Redis (AOF + RDB) |
| `kafka_data` | Kafka topics + offsets |
| `elasticsearch_data` | Elasticsearch indexes |
| `rustfs_data` | RustFS objects (uploaded media) |

Inspect them with:

```bash
docker volume ls | grep -E '(postgres|redis|kafka|elasticsearch|rustfs)_data'
docker volume inspect untitled5_postgres_data
```

> ⚠️ `./stop-docker.sh --volumes` **permanently deletes** all data above.
> Treat it like `rm -rf` on the database.

## How the scripts work

### `start-docker.sh`

1. Verifies Docker daemon + Compose v2 plugin
2. Verifies `.env` and `docker-compose.yml` exist
3. Runs `./mvnw jib:dockerBuild` to build 14 local images (`<service>:latest`)
4. Starts 6 infrastructure containers
5. Polls `docker inspect …State.Health.Status` until all infra are healthy
   (or 180 s timeout)
6. Starts 14 application services
7. Waits for `gateway-service` to become healthy
8. Prints the URL summary

### `stop-docker.sh`

1. Parses `-v/--volumes` and `--images` flags
2. Prompts for confirmation before destructive actions
3. Runs `docker compose down` (with `--volumes` and `--rmi all` if requested)

Both scripts are idempotent — `./start-docker.sh` after a partial start picks up
where it left off (Compose recreates existing containers in place).

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Docker/dependency error or build failure |
| 2 | Invalid CLI arguments (`stop-docker.sh`) |