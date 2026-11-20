import exec from 'k6/execution';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Trend } from 'k6/metrics';

const scenario = __ENV.SCENARIO || 'browse';
const baseUrl = __ENV.BASE_URL || 'http://gateway-service:8080';
const token = __ENV.TOKEN || '';
const customerTokens = (__ENV.CUSTOMER_TOKENS || '').split(',').filter(function (value) { return value.length > 0; });
const peakRate = parseInt(__ENV.RATE || '20', 10);
const maxVus = parseInt(__ENV.VUS || '50', 10);
const duration = __ENV.DURATION || '2m';
const configuredProductId = __ENV.HOT_PRODUCT_ID || '';
const couponCode = __ENV.COUPON_CODE || '';

const businessSuccess = new Counter('business_success_total');
const businessFailure = new Counter('business_failure_total');
const checkoutLatency = new Trend('business_checkout_latency_ms', true);
const oversellAttempts = new Counter('oversell_attempts_total');
const resolvedProducts = new Counter('resolved_product_total');
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const cartFailure = new Counter('cart_failure_total');
const catalogFailure = new Counter('catalog_failure_total');
const detailFailure = new Counter('detail_failure_total');
const unresolvedProduct = new Counter('unresolved_product_total');

let cachedProductId = configuredProductId;
let cartItemPresent = false;

function stagesFor() {
  if (scenario === 'spike') {
    return [
      { duration: '10s', target: Math.max(1, Math.floor(peakRate / 10)) },
      { duration: '20s', target: peakRate },
      { duration: duration, target: peakRate },
      { duration: '20s', target: 0 },
    ];
  }
  if (scenario === 'ramp') {
    return [
      { duration: '20s', target: Math.max(1, Math.floor(peakRate / 5)) },
      { duration: duration, target: peakRate },
      { duration: '20s', target: 0 },
    ];
  }
  if (scenario === 'soak') {
    return [
      { duration: '30s', target: Math.floor(peakRate / 2) },
      { duration: duration, target: Math.floor(peakRate / 2) },
      { duration: '30s', target: 0 },
    ];
  }
  return [
    { duration: '30s', target: Math.max(1, Math.floor(peakRate / 2)) },
    { duration: duration, target: peakRate },
    { duration: '20s', target: 0 },
  ];
}

export const options = {
  stages: stagesFor(),
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.90'],
  },
};

const headers = {
  'Content-Type': 'application/json',
  'Accept': 'application/json',
};

function customerToken() {
  if (customerTokens.length === 0) {
    return token;
  }
  const index = exec.vu.idInTest % customerTokens.length;
  return customerTokens[index];
}

function authHeaders() {
  const result = Object.assign({}, headers);
  const current = customerToken();
  if (current) {
    result.Authorization = 'Bearer ' + current;
  }
  result['Idempotency-Key'] = 'k6-' + uuidv4();
  return result;
}

function firstProductId(payload) {
  try {
    const parsed = JSON.parse(payload);
    const page = parsed.content || parsed.data || parsed.items || parsed;
    if (Array.isArray(page) && page.length > 0) {
      return page[0].id || page[0].productId || null;
    }
    if (page && Array.isArray(page.content) && page.content.length > 0) {
      return page.content[0].id || null;
    }
  } catch (error) {
    return null;
  }
  return null;
}

function resolveProductId() {
  if (cachedProductId) {
    return cachedProductId;
  }
  const response = http.get(baseUrl + '/api/v1/products?page=0&size=5', { headers: authHeaders(), tags: { call: 'resolve' } });
  const id = firstProductId(response.body);
  if (id) {
    cachedProductId = id;
    resolvedProducts.add(1);
  }
  return cachedProductId;
}

function browse() {
  const response = http.get(baseUrl + '/api/v1/products?page=0&size=10', { headers: authHeaders(), tags: { call: 'catalog' } });
  check(response, { 'catalog returns 200': (r) => r.status === 200 });
  if (response.status === 200) {
    businessSuccess.add(1);
  } else {
    businessFailure.add(1);
    catalogFailure.add(1);
  }
  sleep(0.2);
}

function productDetail() {
  const productId = resolveProductId();
  if (!productId) {
    check(null, { 'a product id is resolvable': () => false });
    businessFailure.add(1);
    unresolvedProduct.add(1);
    return;
  }
  const response = http.get(baseUrl + '/api/v1/products/' + productId, { headers: authHeaders(), tags: { call: 'detail' } });
  check(response, { 'product detail returns 200': (r) => r.status === 200 });
  if (response.status === 200) {
    businessSuccess.add(1);
  } else {
    businessFailure.add(1);
    detailFailure.add(1);
  }
}

function ensureCart(productId) {
  if (cartItemPresent) {
    return true;
  }
  const response = http.post(baseUrl + '/api/v1/carts/me/items',
    JSON.stringify({ productId: productId, quantity: 1 }),
    { headers: authHeaders(), tags: { call: 'cart' } });
  if (response.status === 200 || response.status === 201) {
    cartItemPresent = true;
    return true;
  }
  cartFailure.add(1);
  return false;
}

function conflictReason(response) {
  try {
    const parsed = JSON.parse(response.body);
    if (parsed && parsed.code) {
      return parsed.code;
    }
  } catch (error) {
    return '';
  }
  return '';
}

function checkout() {
  const productId = resolveProductId();
  if (!productId) {
    businessFailure.add(1);
    return;
  }
  if (!ensureCart(productId)) {
    businessFailure.add(1);
    return;
  }
  const payload = JSON.stringify({
    recipientName: 'Load Tester',
    phoneNumber: '0987654321',
    shippingAddress: '123 Enterprise Blvd, HCMC',
    couponCode: couponCode,
    items: [{ productId: productId, quantity: 1 }],
  });
  const started = Date.now();
  const response = http.post(baseUrl + '/api/v1/orders', payload, { headers: authHeaders(), tags: { call: 'checkout' } });
  checkoutLatency.add(Date.now() - started);
  const accepted = response.status === 200 || response.status === 201;
  const soldOut = response.status === 409 && conflictReason(response) !== 'ORD-4005';
  check(response, {
    'checkout returns an accepted or conflict status': (r) => r.status === 200 || r.status === 201 || r.status === 409,
  });
  if (accepted) {
    cartItemPresent = false;
    businessSuccess.add(1);
  } else if (soldOut) {
    oversellAttempts.add(1);
  } else {
    if (response.status === 409) {
      cartItemPresent = false;
      cartFailure.add(1);
    }
    businessFailure.add(1);
  }
}

export default function () {
  if (scenario === 'browse') {
    browse();
    productDetail();
    return;
  }
  if (scenario === 'flash-sale') {
    productDetail();
    checkout();
    return;
  }
  if (scenario === 'promotion-storm') {
    checkout();
    return;
  }
  browse();
    productDetail();
  checkout();
}

export function handleSummary(data) {
  const summary = {
    scenario: scenario,
    base_url: baseUrl,
    peak_rate: peakRate,
    max_vus: maxVus,
    duration: duration,
    metrics: data.metrics,
    root_group: data.root_group,
  };
  return {
    'summary.json': JSON.stringify(summary, null, 2),
    stdout: JSON.stringify({ scenario: scenario, metrics: data.metrics }, null, 2),
  };
}