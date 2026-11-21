import exec from 'k6/execution';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Trend } from 'k6/metrics';

const scenario = __ENV.SCENARIO || 'browse';
const baseUrl = __ENV.BASE_URL || 'http://gateway-service:8080';
const token = __ENV.TOKEN || '';
function loadCustomerTokens() {
  const inline = (__ENV.CUSTOMER_TOKENS || '').split(',').filter(function (value) { return value.length > 0; });
  if (inline.length > 0) {
    return inline;
  }
  try {
    return open('/results/customer-tokens.txt').split('\n').filter(function (value) { return value.length > 0; });
  } catch (error) {
    return [];
  }
}

const customerTokens = loadCustomerTokens();
const peakIterationRate = parseInt(__ENV.RATE || '20', 10);
const maxVus = parseInt(__ENV.VUS || '50', 10);
const duration = __ENV.DURATION || '2m';
const configuredProductId = __ENV.HOT_PRODUCT_ID || '';
const couponCode = __ENV.COUPON_CODE || '';

const businessSuccess = new Counter('business_success_total');
const businessFailure = new Counter('business_failure_total');
const checkoutLatency = new Trend('business_checkout_latency_ms', true);
const oversellAttempts = new Counter('oversell_attempts_total');
const resolvedProducts = new Counter('resolved_product_total');
const expectedStatuses = [200, 201, 400, 404, 409, 429, 503];
http.setResponseCallback(http.expectedStatuses(200, 201, 400, 404, 409, 429, 503));

function isShedStatus(status) {
  return status === 429 || status === 503;
}

function isServerError(status) {
  return status >= 500 && status !== 503;
}

function isCredentialStatus(status) {
  return status === 401 || status === 403;
}

function isExpectedStatus(status) {
  return expectedStatuses.indexOf(status) >= 0;
}

function isClassifiedStatus(status) {
  return status === 200 || status === 201 || status === 400 || status === 404 || status === 409
    || isCredentialStatus(status) || isServerError(status) || isShedStatus(status);
}

const cartFailure = new Counter('cart_failure_total');
const cartContention = new Counter('cart_contention_total');
const couponContention = new Counter('coupon_contention_total');
const businessRefusal = new Counter('business_refusal_total');
const credentialError = new Counter('credential_error_total');
const shedRequests = new Counter('shed_requests_total');
const catalogFailure = new Counter('catalog_failure_total');
const detailFailure = new Counter('detail_failure_total');
const unresolvedProduct = new Counter('unresolved_product_total');
const unexpectedResponse = new Counter('unexpected_response_total');
const platformError = new Counter('platform_error_total');

let cachedProductId = configuredProductId;
let cartItemId = null;
let resolveFailed = false;

function observe(response) {
  if (!isExpectedStatus(response.status)) {
    unexpectedResponse.add(1);
  }
  if (isServerError(response.status)) {
    platformError.add(1);
  }
}

function stagesFor() {
  if (scenario === 'spike') {
    return [
      { duration: '10s', target: Math.max(1, Math.floor(peakIterationRate / 10)) },
      { duration: '20s', target: peakIterationRate },
      { duration: duration, target: peakIterationRate },
      { duration: '20s', target: 0 },
    ];
  }
  if (scenario === 'ramp') {
    return [
      { duration: '20s', target: Math.max(1, Math.floor(peakIterationRate / 5)) },
      { duration: duration, target: peakIterationRate },
      { duration: '20s', target: 0 },
    ];
  }
  if (scenario === 'soak') {
    return [
      { duration: '30s', target: Math.floor(peakIterationRate / 2) },
      { duration: duration, target: Math.floor(peakIterationRate / 2) },
      { duration: '30s', target: 0 },
    ];
  }
  return [
    { duration: '30s', target: Math.max(1, Math.floor(peakIterationRate / 2)) },
    { duration: duration, target: peakIterationRate },
    { duration: '20s', target: 0 },
  ];
}

function plannedStages() {
  const raw = (__ENV.STAGES || '').trim();
  if (raw.length === 0) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed) || parsed.length === 0) {
      return null;
    }
    return parsed.map(function (stage) {
      return {
        duration: String(stage.duration),
        target: Math.max(0, Math.floor(Number(stage.target) || 0)),
      };
    });
  } catch (error) {
    return null;
  }
}

const arrivalStages = plannedStages() || stagesFor();
const peakStageRate = arrivalStages.reduce(function (highest, stage) {
  return Math.max(highest, stage.target);
}, 0);

export const options = {
  scenarios: {
    default: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.floor(peakStageRate / 2)),
      timeUnit: '1s',
      preAllocatedVUs: maxVus,
      maxVUs: maxVus,
      gracefulStop: '30s',
      stages: arrivalStages,
    },
  },
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
  resolveFailed = false;
  const response = http.get(baseUrl + '/api/v1/products?page=0&size=5', { headers: authHeaders(), tags: { call: 'resolve' } });
  observe(response);
  const id = firstProductId(response.body);
  if (id) {
    cachedProductId = id;
    resolvedProducts.add(1);
  } else if (isCredentialStatus(response.status)) {
    credentialError.add(1);
    resolveFailed = true;
  } else if (!isExpectedStatus(response.status)) {
    businessFailure.add(1);
    resolveFailed = true;
  }
  return cachedProductId;
}

function browse() {
  const response = http.get(baseUrl + '/api/v1/products?page=0&size=10', { headers: authHeaders(), tags: { call: 'catalog' } });
  observe(response);
  check(response, { 'the catalog answers with a status the scenario can classify': (r) => isClassifiedStatus(r.status) });
  if (response.status === 200) {
    businessSuccess.add(1);
  } else if (isCredentialStatus(response.status)) {
    credentialError.add(1);
  } else if (isShedStatus(response.status)) {
    shedRequests.add(1);
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
    unresolvedProduct.add(1);
    if (!resolveFailed) {
      businessFailure.add(1);
    }
    return;
  }
  const response = http.get(baseUrl + '/api/v1/products/' + productId, { headers: authHeaders(), tags: { call: 'detail' } });
  observe(response);
  check(response, { 'the product detail answers with a status the scenario can classify': (r) => isClassifiedStatus(r.status) });
  if (response.status === 200) {
    businessSuccess.add(1);
  } else if (isCredentialStatus(response.status)) {
    credentialError.add(1);
  } else if (isShedStatus(response.status)) {
    shedRequests.add(1);
  } else {
    businessFailure.add(1);
    detailFailure.add(1);
  }
}

function ensureCart(productId) {
  if (cartItemId) {
    const update = http.put(baseUrl + '/api/v1/carts/me/items/' + cartItemId,
      JSON.stringify({ quantity: 1 }),
      { headers: authHeaders(), tags: { call: 'cart-update' } });
    observe(update);
    if (update.status === 200 || update.status === 201) {
      return 'ready';
    }
    if (isCredentialStatus(update.status)) {
      credentialError.add(1);
      return 'credential';
    }
    if (isShedStatus(update.status)) {
      shedRequests.add(1);
      return 'shed';
    }
    businessFailure.add(1);
    cartFailure.add(1);
    cartItemId = null;
  }
  const response = http.post(baseUrl + '/api/v1/carts/me/items',
    JSON.stringify({ productId: productId, quantity: 1 }),
    { headers: authHeaders(), tags: { call: 'cart' } });
  observe(response);
  check(response, { 'the cart answers with a status the scenario can classify': (r) => isClassifiedStatus(r.status) });
  if (response.status === 200 || response.status === 201) {
    try {
      const parsed = JSON.parse(response.body);
      const data = parsed.data || {};
      const items = data.items || [];
      for (const candidate of items) {
        if (candidate.productId === productId) {
          cartItemId = candidate.id;
          break;
        }
      }
    } catch (error) {
      cartItemId = null;
    }
    return 'ready';
  }
  if (isCredentialStatus(response.status)) {
    credentialError.add(1);
    return 'credential';
  }
  if (isShedStatus(response.status)) {
    shedRequests.add(1);
    return 'shed';
  }
  cartFailure.add(1);
  return 'failure';
}

function resetCart() {
  cartItemId = null;
  const response = http.del(baseUrl + '/api/v1/carts/me', null, { headers: authHeaders(), tags: { call: 'cart-reset' } });
  observe(response);
  if (isCredentialStatus(response.status)) {
    credentialError.add(1);
  } else if (!isExpectedStatus(response.status)) {
    businessFailure.add(1);
    cartFailure.add(1);
  }
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

function placeOrder(productId) {
  const payload = JSON.stringify({
    recipientName: 'Load Tester',
    phoneNumber: '0987654321',
    shippingAddress: '123 Enterprise Blvd, HCMC',
    couponCode: couponCode,
    items: [{ productId: productId, quantity: 1 }],
  });
  const started = Date.now();
  const response = http.post(baseUrl + '/api/v1/orders', payload, { headers: authHeaders(), tags: { call: 'checkout' } });
  observe(response);
  checkoutLatency.add(Date.now() - started);
  return response;
}

function checkout() {
  const productId = resolveProductId();
  if (!productId) {
    unresolvedProduct.add(1);
    if (!resolveFailed) {
      businessFailure.add(1);
    }
    return;
  }
  const cart = ensureCart(productId);
  if (cart !== 'ready') {
    if (cart === 'shed') {
      cartItemId = null;
      return;
    }
    if (cart === 'credential') {
      credentialError.add(1);
      return;
    }
    businessFailure.add(1);
    return;
  }
  let response = placeOrder(productId);
  if (response.status === 409 && conflictReason(response) === 'ORD-4005') {
    resetCart();
    cartContention.add(1);
    const retry = ensureCart(productId);
    if (retry === 'shed') {
      cartItemId = null;
      return;
    }
    if (retry === 'credential') {
      credentialError.add(1);
      return;
    }
    if (retry !== 'ready') {
      businessFailure.add(1);
      return;
    }
    response = placeOrder(productId);
  }
  const accepted = response.status === 200 || response.status === 201;
  const reason = conflictReason(response);
  const refused = response.status === 409 || response.status === 400;
  const couponRejected = refused && (reason === 'ORD-4008' || reason === 'PRO-7006' || reason === 'PRO-7007' || reason === 'PRO-7005');
  const stockRefused = refused && (reason === 'ORD-4007' || reason === 'ORD-4009' || reason === 'INV-4001');
  const shed = isShedStatus(response.status);
  check(response, {
    'the checkout answers with a status the scenario can classify': (r) => isClassifiedStatus(r.status),
  });
  if (accepted) {
    cartItemId = null;
    businessSuccess.add(1);
  } else if (couponRejected) {
    resetCart();
    couponContention.add(1);
  } else if (stockRefused) {
    resetCart();
    oversellAttempts.add(1);
  } else if (refused) {
    resetCart();
    cartFailure.add(1);
    businessRefusal.add(1);
  } else if (isCredentialStatus(response.status)) {
    credentialError.add(1);
    resetCart();
  } else if (shed) {
    shedRequests.add(1);
    resetCart();
  } else {
    resetCart();
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
    peak_iteration_rate_per_second: peakStageRate,
    max_vus: maxVus,
    duration: duration,
    arrival_stages: arrivalStages,
    customer_tokens: customerTokens.length,
    metrics: data.metrics,
    root_group: data.root_group,
  };
  return {
    'summary.json': JSON.stringify(summary, null, 2),
    stdout: JSON.stringify({ scenario: scenario, metrics: data.metrics }, null, 2),
  };
}