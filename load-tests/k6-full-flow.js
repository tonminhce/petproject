import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '30s', target: 20 },   // Ramp up to 20 users
    { duration: '1m', target: 50 },    // Ramp up to 50 users
    { duration: '2m', target: 100 },   // High load: 100 concurrent checkout users
    { duration: '30s', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],    // Error rate must be less than 1%
    http_req_duration: ['p(95)<500'],  // 95% of requests must complete below 500ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  };

  // 1. Browse Catalog
  const catalogRes = http.get(`${BASE_URL}/api/v1/products?page=0&size=10`, params);
  check(catalogRes, {
    'catalog status is 200': (r) => r.status === 200,
  });

  sleep(1);

  // 2. Add item to cart
  const cartPayload = JSON.stringify({
    productId: '00000000-0000-0000-0000-000000000001',
    quantity: 1,
  });

  const cartRes = http.post(`${BASE_URL}/api/v1/cart/items`, cartPayload, params);
  check(cartRes, {
    'add cart status is 200 or 201': (r) => r.status === 200 || r.status === 201,
  });

  sleep(1);

  // 3. Checkout (Order Create Saga with Isolated DB Transactions)
  const idempotencyKey = `k6-${uuidv4()}`;
  const checkoutPayload = JSON.stringify({
    recipientName: 'Load Tester',
    phoneNumber: '0987654321',
    shippingAddress: '123 Enterprise Blvd, HCMC',
    couponCode: 'SAVE10',
  });

  const orderParams = {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };

  const orderRes = http.post(`${BASE_URL}/api/v1/orders`, checkoutPayload, orderParams);
  const orderSuccess = check(orderRes, {
    'order created status is 201 or 200': (r) => r.status === 201 || r.status === 200,
  });

  if (orderSuccess && orderRes.status === 201) {
    const orderData = orderRes.json('data');
    const orderId = orderData ? orderData.id : null;

    if (orderId) {
      // 4. Create Payment via Multi-Provider Factory (e.g. COD / VNPay)
      const paymentPayload = JSON.stringify({
        orderId: orderId,
        amount: orderData.total || 90.0,
        currency: 'USD',
        idempotencyKey: `pay-${uuidv4()}`,
        provider: 'cod',
      });

      const paymentRes = http.post(`${BASE_URL}/api/v1/payments`, paymentPayload, params);
      check(paymentRes, {
        'payment created status is 200 or 201': (r) => r.status === 200 || r.status === 201,
      });
    }
  }

  sleep(2);
}
