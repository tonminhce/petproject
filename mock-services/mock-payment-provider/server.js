// Mock payment provider — payment-service spec D2/D10.
// Simulates an external PSP: capture/refund requests trigger a delayed,
// HMAC-SHA256-signed CAPTURED/REFUNDED webhook back to payment-service.
'use strict';

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 3000;
const SECRET = process.env.PAYMENT_WEBHOOK_SECRET || 'local-test-secret';
const TARGET = new URL(process.env.PAYMENT_SERVICE_URL || 'http://payment-service:8085');
const WEBHOOK_PATH = '/api/v1/webhooks/payments/mock';

// paymentId -> { orderId?, amount?, currency? } seeded from capture/refund bodies
const payments = new Map();

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function postWebhook(payload) {
  const body = Buffer.from(JSON.stringify(payload));
  const signature = crypto.createHmac('sha256', SECRET).update(body).digest('hex');
  const req = http.request(
    {
      hostname: TARGET.hostname,
      port: TARGET.port || 80,
      path: WEBHOOK_PATH,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': body.length,
        'X-Webhook-Signature': signature
      }
    },
    (res) => {
      res.resume();
      console.log(`webhook ${payload.eventType} paymentId=${payload.paymentId} -> ${res.statusCode}`);
    }
  );
  req.on('error', (err) => console.error(`webhook ${payload.eventType} failed: ${err.message}`));
  req.end(body);
}

function trigger(paymentId, status, eventType, res) {
  const meta = payments.get(paymentId) || {};
  const payload = {
    eventId: crypto.randomUUID(),
    eventType,
    paymentId,
    orderId: meta.orderId,
    amount: meta.amount,
    currency: meta.currency,
    status,
    providerEventId: `mock-${crypto.randomUUID()}`
  };
  setTimeout(() => postWebhook(payload), 200 + Math.floor(Math.random() * 601));
  send(res, 202, { status: 'accepted', paymentId, webhook: { eventType, status } });
}

http
  .createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    const parts = pathname.split('/').filter(Boolean); // ['mock-payments', ':id', 'action']

    if (req.method === 'GET' && pathname === '/mock-payments/_health') {
      return send(res, 200, { status: 'ok' });
    }
    if (req.method === 'POST' && pathname === '/mock-payments/reset') {
      payments.clear();
      return send(res, 200, { status: 'ok' });
    }

    const match =
      req.method === 'POST' && parts.length === 3 && parts[0] === 'mock-payments'
        ? { id: decodeURIComponent(parts[1]), action: parts[2] }
        : null;
    if (!match || !['capture', 'refund'].includes(match.action)) {
      return send(res, 404, { error: 'not found' });
    }
    if (!match.id) {
      return send(res, 400, { error: 'paymentId is required' });
    }

    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
    });
    req.on('end', () => {
      let seed;
      try {
        seed = raw ? JSON.parse(raw) : {};
      } catch (e) {
        return send(res, 400, { error: 'invalid JSON body' });
      }
      const prev = payments.get(match.id) || {};
      payments.set(match.id, {
        orderId: seed.orderId ?? prev.orderId,
        amount: seed.amount ?? prev.amount,
        currency: seed.currency ?? prev.currency
      });
      const captured = match.action === 'capture';
      trigger(match.id, captured ? 'CAPTURED' : 'REFUNDED', `payment.${captured ? 'captured' : 'refunded'}`, res);
    });
  })
  .listen(PORT, () => console.log(`mock-payment-provider listening on ${PORT}, target=${TARGET.href}`));
