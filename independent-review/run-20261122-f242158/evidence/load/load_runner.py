#!/usr/bin/env python3
"""Independent bounded load + concurrency probe for petproject audit run-20261122-f242158.

Scenarios (all bounded, dev compose stack only):
  storm    : 200 concurrent promotion reservations against a quota=5 coupon (ADMIN bypass guard).
  oversell : 30 distinct registered users race to reserve stock=5 of a fresh product via POST /orders.
  dupe     : 20 concurrent checkouts of the SAME cart (duplicate-order race).
  ramp     : 60s mixed public-read ramp with p50/p95/p99.

Usage: python3 load_runner.py <scenario> 
"""
import http.client, json, random, sys, threading, time, uuid
from concurrent.futures import ThreadPoolExecutor

HOST, PORT = "localhost", 8080
RUN_TAG = "AUD" + uuid.uuid4().hex[:8]
OUT = {}

def conn():
    return http.client.HTTPConnection(HOST, PORT, timeout=30)

def call(c, method, path, body=None, token=None, ctype="application/json"):
    hdr = {"Content-Type": ctype}
    if token:
        hdr["Authorization"] = "Bearer " + token
    payload = json.dumps(body) if body is not None else None
    c.request(method, path, body=payload, headers=hdr)
    r = c.getresponse()
    data = r.read()
    try:
        j = json.loads(data.decode("utf-8", "replace"))
    except Exception:
        j = {"raw": data.decode("utf-8", "replace")[:300]}
    return r.status, j

class Client:
    def __init__(self):
        self.c = conn()
    def req(self, method, path, body=None, token=None):
        for attempt in range(3):
            try:
                return call(self.c, method, path, body, token)
            except Exception:
                try: self.c.close()
                except Exception: pass
                self.c = conn()
                if attempt == 2:
                    return -1, {"error": "connection-failed"}
    def close(self):
        try: self.c.close()
        except Exception: pass

def pct(xs, p):
    if not xs: return None
    xs = sorted(xs); k = max(0, min(len(xs)-1, int(round((p/100.0)*(len(xs)-1)))))
    return round(xs[k], 1)

def admin_setup(admin, stock=5):
    c = Client()
    slug = "aud-hot-" + RUN_TAG.lower()
    st, prod = c.req("POST", "/api/v1/backoffice/products", {
        "title": "Audit Hot SKU " + RUN_TAG, "slug": slug, "sku": "SKU-" + RUN_TAG,
        "priceUnit": 2499, "quantity": stock, "status": "ACTIVE",
        "description": "audit temp", "weight": 1.0, "dimensions": "10x10x10"}, admin)
    pid = prod.get("data", {}).get("id")
    if not pid:
        print("product create failed:", st, json.dumps(prod)[:300]); sys.exit(2)
    st2, inv = c.req("POST", "/api/v1/inventory", {"productId": pid, "availableQuantity": stock}, admin)
    if st2 not in (200, 201):
        st2, inv = c.req("PUT", "/api/v1/inventory/" + pid, {"productId": pid, "availableQuantity": stock}, admin)
    st3, cp = c.req("POST", "/api/v1/backoffice/promotions", {
        "code": "STORM-" + RUN_TAG, "name": "Audit storm " + RUN_TAG, "discountType": "PERCENT",
        "discountValue": 10, "maxRedemptions": 5, "perUserLimit": 1, "currency": "VND",
        "status": "ACTIVE", "startsAt": "2026-01-01T00:00:00Z", "endsAt": "2027-01-01T00:00:00Z"}, admin)
    code_ok = st3 in (200, 201)
    c.close()
    print("setup: product=%s inventory=%s coupon_created=%s" % (pid, st2, code_ok))
    return pid, ("STORM-" + RUN_TAG if code_ok else None)

def register_and_login(i):
    uname = "aud" + RUN_TAG.lower() + str(i).zfill(3)
    pw = "Aa1" + uuid.uuid4().hex[:8]
    c = Client()
    c.req("POST", "/api/v1/auth/sign-up", {
        "fullName": "Audit User " + str(i), "username": uname, "password": pw,
        "email": uname + "@audit.example.com", "gender": "MALE",
        "phone": "0900" + str(100000 + i)[-6:].zfill(6)})
    st, tok = c.req("POST", "/api/v1/auth/login", {"username": uname, "password": pw})
    at = (tok.get("data") or {}).get("accessToken") if isinstance(tok.get("data"), dict) else None
    c.close()
    return uname, at

def scenario_storm(admin):
    _, code = admin_setup(admin)
    if not code:
        print("storm skipped: no coupon"); return
    n, workers = 200, 50
    barrier = threading.Barrier(workers, timeout=30)
    lat, ok, fail = [], [], []
    lock = threading.Lock()
    def worker(wid):
        c = Client()
        barrier.wait()
        for j in range(wid, n, workers):
            t0 = time.time()
            st, r = c.req("POST", "/api/v1/promotions/" + code + "/reserve", {
                "userId": str(uuid.uuid4()), "orderId": str(uuid.uuid4()),
                "orderAmount": 100000, "currency": "VND"}, admin)
            dt = (time.time() - t0) * 1000
            with lock:
                lat.append(dt)
                (ok if st == 201 else fail).append((st, (r.get("code"),)))
        c.close()
    with ThreadPoolExecutor(workers) as ex:
        list(ex.map(worker, range(workers)))
    print("STORM quota=5 requests=%d created(201)=%d rejected=%d lat_ms p50=%s p95=%s p99=%s" % (
        len(lat), len(ok), len(fail), pct(lat, 50), pct(lat, 95), pct(lat, 99)))
    OUT["storm"] = {"requests": len(lat), "created": len(ok), "rejected": len(fail),
                    "p50": pct(lat, 50), "p95": pct(lat, 95), "p99": pct(lat, 99), "quota": 5}
    return code

def scenario_oversell(admin, users=30, stock=5):
    pid, _ = admin_setup(admin)
    creds = [register_and_login(i) for i in range(users)]
    tokens = [t for _, t in creds if t]
    print("users registered+logged in: %d/%d" % (len(tokens), users))
    for t in tokens:
        c = Client(); c.req("POST", "/api/v1/carts/me/items", {"productId": pid, "quantity": 1}, t); c.close()
    carts = []
    for t in tokens:
        c = Client(); st, r = c.req("GET", "/api/v1/carts/me", token=t); c.close()
        cid = (r.get("data") or {}).get("id")
        if cid: carts.append((t, cid))
    if not carts:
        print("no carts built - abort"); return
    barrier = threading.Barrier(len(carts), timeout=60)
    results = []
    lock = threading.Lock()
    def checkout(pair):
        t, cid = pair
        c = Client()
        barrier.wait()
        t0 = time.time()
        st, r = c.req("POST", "/api/v1/orders", {"cartId": cid,
            "recipientName": "Race User", "phoneNumber": "0900000001",
            "shippingAddress": "1 Race St"}, t)
        dt = (time.time() - t0) * 1000
        oid = (r.get("data") or {}).get("id") if isinstance(r.get("data"), dict) else None
        with lock:
            results.append((st, r.get("code"), dt, oid))
        c.close()
    with ThreadPoolExecutor(len(carts)) as ex:
        list(ex.map(checkout, carts))
    succ = [r for r in results if r[3]]
    fails = [r for r in results if not r[3]]
    print("OVERSELL stock=%d racers=%d orders_created=%d failed=%d max_latency_ms=%s" % (
        stock, len(carts), len(succ), len(fails), round(max(r[2] for r in results), 1) if results else None))
    from collections import Counter
    print("failure codes:", Counter(r[1] for r in fails))
    OUT["oversell"] = {"stock": stock, "racers": len(carts), "created": len(succ),
                       "failed": len(fails), "order_ids": [r[3] for r in succ]}
    return pid

def scenario_dupe(testuser, admin, stock=5):
    pid, _ = admin_setup(admin, stock=stock)
    c = Client()
    c.req("DELETE", "/api/v1/carts/me", token=testuser)
    st, _r = c.req("POST", "/api/v1/carts/me/items", {"productId": pid, "quantity": 1}, testuser)
    st, r = c.req("GET", "/api/v1/carts/me", token=testuser)
    cid = (r.get("data") or {}).get("id")
    c.close()
    n = 20
    barrier = threading.Barrier(n, timeout=30)
    results, lock = [], threading.Lock()
    def go(_):
        c = Client()
        barrier.wait()
        st, r = c.req("POST", "/api/v1/orders", {"cartId": cid, "recipientName": "Dupe",
            "phoneNumber": "0900000001", "shippingAddress": "1 Dupe St"}, testuser)
        oid = (r.get("data") or {}).get("id") if isinstance(r.get("data"), dict) else None
        with lock: results.append((st, r.get("code"), oid))
        c.close()
    with ThreadPoolExecutor(n) as ex:
        list(ex.map(go, range(n)))
    from collections import Counter
    oids = set(r[2] for r in results if r[2])
    print("DUPECHECKOUT racers=%d success=%d distinct_orders=%d codes=%s" % (
        n, sum(1 for r in results if r[2]), len(oids), Counter(r[1] for r in results)))
    OUT["dupe"] = {"racers": n, "success": sum(1 for r in results if r[2]),
                   "distinct_orders": len(oids), "codes": dict(Counter(r[1] for r in results))}
    return pid

def scenario_ramp(testuser):
    PIDS = []
    c = Client()
    st, r = c.req("GET", "/api/v1/products?page=0&size=30", token=testuser)
    for it in (r.get("data") or {}).get("content", [])[:15]:
        if it.get("id"):
            PIDS.append(it.get("id"))
    c.close()
    stop = time.time() + 60
    import collections
    lat = []
    counts = collections.Counter()
    lock = threading.Lock()
    workers = 12
    def worker(wid):
        c = Client()
        i = 0
        while time.time() < stop:
            i += 1
            mode = i % 3
            if mode == 0:
                path = "/api/v1/products?page=0&size=20"
            elif mode == 1:
                path = "/api/v1/products/" + (random.choice(PIDS) if PIDS else "")
                if not PIDS:
                    path = "/api/v1/products?page=0&size=20"
            else:
                path = "/api/v1/search?q=macbook&size=10"
            t0 = time.time()
            try:
                st, _r = c.req("GET", path)
                dt = (time.time() - t0) * 1000
            except Exception:
                st, dt = -1, (time.time() - t0) * 1000
            with lock:
                lat.append(dt); counts[st] += 1
        c.close()
    with ThreadPoolExecutor(workers) as ex:
        list(ex.map(worker, range(workers)))
    total = len(lat)
    print("RAMP 60s workers=%d requests=%d rps=%.1f p50=%sms p95=%sms p99=%sms errors=%d codes=%s" % (
        workers, total, total/60.0, pct(lat, 50), pct(lat, 95), pct(lat, 99),
        sum(v for k, v in counts.items() if k != 200), dict(counts)))
    OUT["ramp"] = {"seconds": 60, "workers": workers, "requests": total,
                   "rps": round(total/60.0, 1), "p50": pct(lat, 50), "p95": pct(lat, 95),
                   "p99": pct(lat, 99), "codes": dict(counts)}

if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    admin = open("/tmp/admin_token.txt").read().strip()
    testuser = open("/tmp/testuser_token.txt").read().strip()
    if mode in ("storm", "all"):
        scenario_storm(admin)
    if mode in ("oversell", "all"):
        scenario_oversell(admin)
    if mode in ("dupe", "all"):
        scenario_dupe(testuser, admin)
    if mode in ("dupe-rich",):
        scenario_dupe(testuser, admin, stock=1000)
    if mode in ("ramp", "all"):
        scenario_ramp(testuser)
    print(json.dumps(OUT, indent=1))
