import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  scenarios: {
    hikari_pool_stress: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<800'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

http.setResponseCallback(http.expectedStatuses(201, 400, 409));

export default function () {
  const idempotencyKey = `saga-stress-${uuidv4()}`;
  const payload = JSON.stringify({
    recipientName: 'Stress Runner',
    phoneNumber: '0901234567',
    shippingAddress: '456 High Scale Avenue',
    couponCode: '',
  });

  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'Idempotency-Key': idempotencyKey,
  };
  if (TOKEN) {
    headers['Authorization'] = `Bearer ${TOKEN}`;
  }

  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, { headers });

  check(res, {
    'order created status is 201 or 400 (expected business limits)': (r) =>
      r.status === 201 || r.status === 400 || r.status === 409,
    'hikari pool not exhausted (no 500)': (r) => r.status !== 500,
  });
}
