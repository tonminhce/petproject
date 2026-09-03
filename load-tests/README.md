# Production Load Testing Suite (k6)

This directory contains automated k6 performance testing scripts designed to benchmark the platform against enterprise SLAs.

## Prerequisites

Install [k6](https://k6.io/docs/get-started/installation/):

```bash
# macOS
brew install k6

# Linux
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
```

## Available Tests

### 1. End-to-End User Journey (`k6-full-flow.js`)
Tests full purchasing pipeline: Product Browsing -> Cart -> Checkout (OrderCreateSaga) -> Multi-Provider Payment.

```bash
k6 run load-tests/k6-full-flow.js -e BASE_URL=http://localhost:8080
```

- **Target Load**: Ramps up to 100 concurrent Virtual Users (VUs).
- **Quality Gates**:
  - `http_req_failed < 1%`
  - `p(95) duration < 500ms`

### 2. Order Create Saga & HikariCP Stress Test (`k6-order-saga.js`)
Benchmarks `OrderCreateSaga` with isolated transaction boundaries under sustained concurrent requests (200 requests/sec).

```bash
k6 run load-tests/k6-order-saga.js -e BASE_URL=http://localhost:8080
```

- **Objective**: Confirms HikariCP pool is protected from connection starvation while remote HTTP calls are in progress.
