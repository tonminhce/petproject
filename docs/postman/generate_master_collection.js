const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

// Load existing files
const flowCol = JSON.parse(fs.readFileSync(path.join(__dirname, "petproject-e2e-business-flow.postman_collection.json")));
let compCol;
try {
  const gitCompColStr = execSync("git show HEAD:docs/postman/petproject-comprehensive.postman_collection.json", { maxBuffer: 10 * 1024 * 1024 }).toString();
  compCol = JSON.parse(gitCompColStr);
} catch (err) {
  compCol = JSON.parse(fs.readFileSync(path.join(__dirname, "petproject-comprehensive.postman_collection.json")));
}

// Define collection variables
const collectionVariables = [
  { key: "gatewayUrl", value: "http://localhost:8080" },
  { key: "authUrl", value: "http://localhost:8088" },
  { key: "favouriteUrl", value: "http://localhost:8081" },
  { key: "favouritesUrl", value: "http://localhost:8081" },
  { key: "inventoryUrl", value: "http://localhost:8082" },
  { key: "mediaUrl", value: "http://localhost:8083" },
  { key: "orderUrl", value: "http://localhost:8084" },
  { key: "paymentUrl", value: "http://localhost:8085" },
  { key: "productUrl", value: "http://localhost:8086" },
  { key: "shippingUrl", value: "http://localhost:8087" },
  { key: "ratingUrl", value: "http://localhost:8089" },
  { key: "notificationUrl", value: "http://localhost:8090" },
  { key: "taxUrl", value: "http://localhost:8091" },
  { key: "promotionUrl", value: "http://localhost:8093" },
  { key: "searchUrl", value: "http://localhost:8094" },
  { key: "adminToken", value: "" },
  { key: "admin_token", value: "" },
  { key: "userToken", value: "" },
  { key: "refreshToken", value: "" },
  { key: "userId", value: "" },
  { key: "adminUser", value: "adminuser" },
  { key: "adminPass", value: "adminpass" },
  { key: "normalUser", value: "testuser" },
  { key: "normalPass", value: "changeme" },
  { key: "catSlug", value: "" },
  { key: "brandSlug", value: "" },
  { key: "prodSlug", value: "" },
  { key: "prodSku", value: "" },
  { key: "categoryId", value: "" },
  { key: "brandId", value: "" },
  { key: "productId", value: "" },
  { key: "orderId", value: "" },
  { key: "paymentId", value: "" },
  { key: "payKey", value: "" },
  { key: "shipmentId", value: "" },
  { key: "taxClassId", value: "" },
  { key: "taxClassName", value: "" },
  { key: "promotionId", value: "" },
  { key: "promoCode", value: "" },
  { key: "reservationId", value: "" },
  { key: "returnId", value: "" },
  { key: "mediaId", value: "" },
  { key: "cartItemId", value: "" },
  { key: "trackingNumber", value: "" },
  { key: "customerPhone", value: "0987654321" },
  { key: "badSku", value: "" },
  { key: "badSlug", value: "" }
];

// Helper to create request
function makeReq(name, method, url, headers = {}, body = null, preScript = null, testScript = null) {
  const reqObj = {
    name,
    request: {
      method,
      header: Object.entries(headers).map(([key, value]) => ({ key, value })),
      url: url
    }
  };

  if (body) {
    reqObj.request.body = {
      mode: "raw",
      raw: typeof body === "string" ? body : JSON.stringify(body, null, 2),
      options: { raw: { language: "json" } }
    };
  }

  reqObj.event = [];
  if (preScript) {
    reqObj.event.push({
      listen: "prerequest",
      script: {
        type: "text/javascript",
        exec: Array.isArray(preScript) ? preScript : preScript.split("\n")
      }
    });
  }
  if (testScript) {
    reqObj.event.push({
      listen: "test",
      script: {
        type: "text/javascript",
        exec: Array.isArray(testScript) ? testScript : testScript.split("\n")
      }
    });
  }

  return reqObj;
}

// ============================================================================
// BUILD PART 1: E2E BUSINESS LIFECYCLE (CHOREOGRAPHED HAPPY PATH)
// ============================================================================
const e2eFolders = [];

// 1. Authentication
e2eFolders.push({
  name: "1. Authentication",
  item: [
    makeReq(
      "1.1 Admin Login",
      "POST",
      "{{authUrl}}/api/v1/auth/login",
      { "Content-Type": "application/json" },
      { username: "{{adminUser}}", password: "{{adminPass}}" },
      null,
      `pm.test("Admin login succeeds", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.accessToken).to.be.a("string");
pm.collectionVariables.set("adminToken", res.data.accessToken);
pm.collectionVariables.set("admin_token", res.data.accessToken);`
    ),
    makeReq(
      "1.2 Customer Login",
      "POST",
      "{{authUrl}}/api/v1/auth/login",
      { "Content-Type": "application/json" },
      { username: "{{normalUser}}", password: "{{normalPass}}" },
      null,
      `pm.test("Customer login succeeds", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.accessToken).to.be.a("string");
pm.collectionVariables.set("userToken", res.data.accessToken);
if (res.data.refreshToken) pm.collectionVariables.set("refreshToken", res.data.refreshToken);
try {
  const parts = res.data.accessToken.split(".");
  if (parts.length === 3) {
    const payload = JSON.parse(Buffer.from(parts[1], "base64").toString());
    if (payload.sub) pm.collectionVariables.set("userId", payload.sub);
  }
} catch (e) {}`
    ),
    makeReq(
      "1.3 Forgot Password Flow",
      "POST",
      "{{authUrl}}/api/v1/auth/forgot-password",
      { "Content-Type": "application/json" },
      { email: "testuser@local.dev" },
      null,
      `pm.test("Forgot password request accepted", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    )
  ]
});

// 2. Catalog & Products
e2eFolders.push({
  name: "2. Catalog & Products",
  item: [
    makeReq(
      "2.1 Create Category (Admin)",
      "POST",
      "{{productUrl}}/api/v1/backoffice/categories",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { title: "Laptops & Computers", slug: "{{catSlug}}" },
      `const ts = Date.now();\npm.collectionVariables.set("catSlug", "cat-" + ts);`,
      `pm.test("Category created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.collectionVariables.set("categoryId", res.data.id);`
    ),
    makeReq(
      "2.2 Create Brand (Admin)",
      "POST",
      "{{productUrl}}/api/v1/backoffice/brands",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { name: "Apple Inc", slug: "{{brandSlug}}", description: "Premium electronics" },
      `const ts = Date.now();\npm.collectionVariables.set("brandSlug", "brand-" + ts);`,
      `pm.test("Brand created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.collectionVariables.set("brandId", res.data.id);`
    ),
    makeReq(
      "2.3 Create Product with SPU/SKU (Admin)",
      "POST",
      "{{productUrl}}/api/v1/backoffice/products",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        title: "MacBook Pro 16 M3 Max",
        slug: "{{prodSlug}}",
        sku: "{{prodSku}}",
        priceUnit: 2499.00,
        quantity: 100,
        status: "ACTIVE",
        description: "Apple silicon high performance workstation",
        categoryId: "{{categoryId}}",
        brandId: "{{brandId}}"
      },
      `const ts = Date.now();\npm.collectionVariables.set("prodSlug", "macbook-" + ts);\npm.collectionVariables.set("prodSku", "SKU-" + ts);`,
      `pm.test("Product created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.collectionVariables.set("productId", res.data.id);`
    ),
    makeReq(
      "2.4 Get Categories Storefront",
      "GET",
      "{{productUrl}}/api/v1/categories",
      {},
      null,
      null,
      `pm.test("Categories retrieved", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "2.5 Get Product by ID Storefront",
      "GET",
      "{{productUrl}}/api/v1/products/{{productId}}",
      {},
      null,
      null,
      `pm.test("Product retrieved", () => pm.expect(pm.response.code).to.eql(200));
const res = pm.response.json();
pm.expect(res.data.id).to.eql(pm.collectionVariables.get("productId"));`
    )
  ]
});

// 3. Inventory
e2eFolders.push({
  name: "3. Inventory",
  item: [
    makeReq(
      "3.1 Seed Product Inventory (Admin)",
      "POST",
      "{{inventoryUrl}}/api/v1/inventory",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { productId: "{{productId}}", availableQuantity: 100 },
      null,
      `pm.test("Inventory seeded", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "3.2 Get Inventory by Product ID (User)",
      "GET",
      "{{inventoryUrl}}/api/v1/inventory/{{productId}}",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Inventory retrieved", () => pm.expect(pm.response.code).to.eql(200));
const res = pm.response.json();
pm.expect(res.data.availableQuantity).to.be.above(0);`
    )
  ]
});

// 4. Cart & Order
e2eFolders.push({
  name: "4. Cart & Order",
  item: [
    makeReq(
      "4.1 Add Product to Cart (User)",
      "POST",
      "{{orderUrl}}/api/v1/carts/me/items",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      { productId: "{{productId}}", quantity: 1 },
      null,
      `pm.test("Product added to cart", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
if (res.data && res.data.id) pm.collectionVariables.set("cartItemId", res.data.id);`
    ),
    makeReq(
      "4.2 View Cart (User)",
      "GET",
      "{{orderUrl}}/api/v1/carts/me",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Cart retrieved", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "4.3 Place Order from Cart (User)",
      "POST",
      "{{orderUrl}}/api/v1/orders",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        recipientName: "Test Customer",
        phoneNumber: "{{customerPhone}}",
        shippingAddress: "123 Commerce St, District 1, HCM City"
      },
      null,
      `pm.test("Order placed successfully", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.id).to.be.a("string");
pm.expect(res.data.status).to.be.oneOf(["PENDING", "PENDING_PAYMENT"]);
pm.collectionVariables.set("orderId", res.data.id);`
    ),
    makeReq(
      "4.4 Get Order Details (User)",
      "GET",
      "{{orderUrl}}/api/v1/orders/{{orderId}}",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Order retrieved", () => pm.expect(pm.response.code).to.eql(200));
const res = pm.response.json();
pm.expect(res.data.id).to.eql(pm.collectionVariables.get("orderId"));`
    )
  ]
});

// 5. Public Guest Tracking (NEW Sprint 4)
e2eFolders.push({
  name: "5. Public Guest Order Tracking",
  item: [
    makeReq(
      "5.1 Guest Order Tracking by Phone (Public)",
      "GET",
      "{{orderUrl}}/api/v1/orders/track?orderId={{orderId}}&phone={{customerPhone}}",
      {},
      null,
      null,
      `pm.test("Guest tracking succeeds without JWT", () => pm.expect(pm.response.code).to.eql(200));
const res = pm.response.json();
pm.expect(res.data.orderId).to.eql(pm.collectionVariables.get("orderId"));
pm.expect(res.data.status).to.be.a("string");`
    )
  ]
});

// 6. Payment (Multi-Gateway)
e2eFolders.push({
  name: "6. Payment (Multi-Gateway)",
  item: [
    makeReq(
      "6.1 Create Payment via Stripe Intent",
      "POST",
      "{{paymentUrl}}/api/v1/payments",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        orderId: "{{orderId}}",
        amount: 2499.00,
        currency: "USD",
        idempotencyKey: "{{payKey}}",
        provider: "STRIPE"
      },
      `const ts = Date.now();\npm.collectionVariables.set("payKey", "PAY-STRIPE-" + ts);`,
      `pm.test("Payment intent created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.collectionVariables.set("paymentId", res.data.id);`
    ),
    makeReq(
      "6.2 Create Payment via VNPay",
      "POST",
      "{{paymentUrl}}/api/v1/payments",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        orderId: "{{orderId}}",
        amount: 2499.00,
        currency: "VND",
        idempotencyKey: "{{payKey}}",
        provider: "VNPAY"
      },
      `const ts = Date.now();\npm.collectionVariables.set("payKey", "PAY-VNPAY-" + ts);`,
      `pm.test("VNPay payment created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "6.3 Create Payment via MoMo",
      "POST",
      "{{paymentUrl}}/api/v1/payments",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        orderId: "{{orderId}}",
        amount: 2499.00,
        currency: "VND",
        idempotencyKey: "{{payKey}}",
        provider: "MOMO"
      },
      `const ts = Date.now();\npm.collectionVariables.set("payKey", "PAY-MOMO-" + ts);`,
      `pm.test("MoMo payment created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "6.4 Create Payment via COD",
      "POST",
      "{{paymentUrl}}/api/v1/payments",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        orderId: "{{orderId}}",
        amount: 2499.00,
        currency: "USD",
        idempotencyKey: "{{payKey}}",
        provider: "COD"
      },
      `const ts = Date.now();\npm.collectionVariables.set("payKey", "PAY-COD-" + ts);`,
      `pm.test("COD payment created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "6.5 Capture Payment (Admin/Service)",
      "POST",
      "{{paymentUrl}}/api/v1/payments/{{paymentId}}/capture",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Payment captured", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    )
  ]
});

// 7. Order Fulfillment Lifecycle
e2eFolders.push({
  name: "7. Order Fulfillment Lifecycle",
  item: [
    makeReq(
      "7.1 Confirm Order (Admin)",
      "POST",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/confirm",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Order confirmed", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.status).to.eql("CONFIRMED");`
    ),
    makeReq(
      "7.2 Ship Order (Admin)",
      "POST",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/ship",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Order shipped", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.status).to.eql("SHIPPED");`
    ),
    makeReq(
      "7.3 Deliver Order (Admin)",
      "POST",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/deliver",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Order delivered", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.status).to.eql("DELIVERED");`
    )
  ]
});

// 8. Rating & Favourite
e2eFolders.push({
  name: "8. Rating & Favourite",
  item: [
    makeReq(
      "8.1 Submit Rating for Delivered Product (User)",
      "POST",
      "{{ratingUrl}}/api/v1/ratings",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        productId: "{{productId}}",
        rating: 5,
        comment: "Excellent build quality and blazing performance!"
      },
      null,
      `pm.test("Rating submitted by verified buyer", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "8.2 View Ratings for Product",
      "GET",
      "{{ratingUrl}}/api/v1/ratings?productId={{productId}}",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Ratings retrieved", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "8.3 Add Product to Favourites (User)",
      "POST",
      "{{favouriteUrl}}/api/v1/favourites",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      { productId: "{{productId}}" },
      null,
      `pm.test("Favourite added", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "8.4 View User Favourites (User)",
      "GET",
      "{{favouriteUrl}}/api/v1/favourites",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Favourites retrieved", () => pm.expect(pm.response.code).to.eql(200));`
    )
  ]
});

// 9. RMA Order Returns (NEW Sprint 4)
e2eFolders.push({
  name: "9. RMA Order Returns Workflow",
  item: [
    makeReq(
      "9.1 Request Return on Delivered Order (User)",
      "POST",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/returns",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        reason: "DEFECTIVE",
        description: "Screen cracked during shipping",
        refundAmount: 2499.00
      },
      null,
      `pm.test("Return requested successfully", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.status).to.eql("REQUESTED");
pm.collectionVariables.set("returnId", res.data.id);`
    ),
    makeReq(
      "9.2 View Customer Returns (User)",
      "GET",
      "{{orderUrl}}/api/v1/orders/returns/me",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("My returns retrieved", () => pm.expect(pm.response.code).to.eql(200));
const res = pm.response.json();
pm.expect(res.data.content).to.be.an("array");`
    ),
    makeReq(
      "9.3 View Returns for Specific Order (User)",
      "GET",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/returns",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Order returns retrieved", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "9.4 Backoffice Review & Approve Return (Admin)",
      "PUT",
      "{{orderUrl}}/api/v1/backoffice/returns/{{returnId}}/review",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        status: "APPROVED",
        adminNotes: "Physical defect confirmed. Auto-refund triggered."
      },
      null,
      `pm.test("Return reviewed and processed", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.expect(res.data.status).to.be.oneOf(["APPROVED", "REFUNDED"]);`
    )
  ]
});

// 10. Search & Notification
e2eFolders.push({
  name: "10. Search & Notification",
  item: [
    makeReq(
      "10.1 Reindex Search (Admin)",
      "POST",
      "{{searchUrl}}/api/v1/backoffice/search/reindex",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Search reindex triggered", () => pm.expect(pm.response.code).to.be.oneOf([200, 202]));`
    ),
    makeReq(
      "10.2 Search for Product in Elasticsearch",
      "GET",
      "{{searchUrl}}/api/v1/search?q=MacBook",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Search returns products", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "10.3 View Notifications for Order (Admin)",
      "GET",
      "{{notificationUrl}}/api/v1/backoffice/notifications?orderId={{orderId}}",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Notifications retrieved", () => pm.expect(pm.response.code).to.eql(200));`
    )
  ]
});

// 11. Tax & Promotion
e2eFolders.push({
  name: "11. Tax & Promotion",
  item: [
    makeReq(
      "11.1 Create Tax Class (Admin)",
      "POST",
      "{{taxUrl}}/api/v1/backoffice/tax-classes",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { name: "{{taxClassName}}", defaultRatePct: 10.0 },
      `const ts = Date.now();\npm.collectionVariables.set("taxClassName", "Electronics-" + ts);`,
      `pm.test("Tax class created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));
const res = pm.response.json();
pm.collectionVariables.set("taxClassId", res.data.id);`
    ),
    makeReq(
      "11.2 Calculate Tax",
      "POST",
      "{{taxUrl}}/api/v1/tax/calculate",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { taxClassId: "{{taxClassId}}", country: "US", amount: 100.00 },
      null,
      `pm.test("Tax calculated", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    ),
    makeReq(
      "11.3 Create Promotion Campaign (Admin)",
      "POST",
      "{{promotionUrl}}/api/v1/backoffice/promotions",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        name: "Grand Opening Promotion",
        code: "{{promoCode}}",
        discountType: "PERCENT",
        discountValue: 10.0,
        startsAt: "2026-01-01T00:00:00Z",
        endsAt: "2026-12-31T23:59:59Z",
        status: "ACTIVE"
      },
      `const ts = Date.now();\npm.collectionVariables.set("promoCode", "SALE" + ts);`,
      `pm.test("Promotion created", () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));`
    )
  ]
});

// 12. Gateway E2E Routing
e2eFolders.push({
  name: "12. Gateway E2E Routing",
  item: [
    makeReq(
      "12.1 Products via Gateway :8080",
      "GET",
      "{{gatewayUrl}}/api/v1/products",
      {},
      null,
      null,
      `pm.test("Gateway routes products", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "12.2 Favourites via Gateway :8080",
      "GET",
      "{{gatewayUrl}}/api/v1/favourites",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Gateway routes favourites with JWT", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "12.3 Search via Gateway :8080",
      "GET",
      "{{gatewayUrl}}/api/v1/search?q=MacBook",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Gateway routes search", () => pm.expect(pm.response.code).to.eql(200));`
    ),
    makeReq(
      "12.4 Anonymous Public Catalog Access via Gateway",
      "GET",
      "{{gatewayUrl}}/api/v1/products",
      {},
      null,
      null,
      `pm.test("Public catalog accessible without auth", () => pm.expect(pm.response.code).to.eql(200));`
    )
  ]
});

// ============================================================================
// BUILD PART 2: COMPREHENSIVE EDGE CASES & NEGATIVE TESTING
// ============================================================================
const edgeFolders = [];

// 13. Edge Cases - Auth & Security
edgeFolders.push({
  name: "13. Edge Cases - Auth & Security",
  item: [
    makeReq(
      "13.1 Login with Invalid Password (expect 401)",
      "POST",
      "{{authUrl}}/api/v1/auth/login",
      { "Content-Type": "application/json" },
      { username: "adminuser", password: "WRONG_PASSWORD_XYZ" },
      null,
      `pm.test("Rejects with 401 Unauthorized", () => pm.expect(pm.response.code).to.eql(401));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0401");`
    ),
    makeReq(
      "13.2 Access Protected Route Without Token (expect 401)",
      "GET",
      "{{authUrl}}/api/v1/users/me",
      {},
      null,
      null,
      `pm.test("Rejects with 401 Unauthorized", () => pm.expect(pm.response.code).to.eql(401));`
    ),
    makeReq(
      "13.3 Regular User Accesses Admin Endpoint (expect 403)",
      "GET",
      "{{authUrl}}/api/v1/users",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Rejects with 403 Forbidden", () => pm.expect(pm.response.code).to.eql(403));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0403");`
    ),
    makeReq(
      "13.4 Malformed Bearer Token (expect 401)",
      "GET",
      "{{authUrl}}/api/v1/users/me",
      { "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.INVALID_TOKEN.XYZ" },
      null,
      null,
      `pm.test("Rejects malformed token with 401", () => pm.expect(pm.response.code).to.eql(401));`
    )
  ]
});

// 14. Edge Cases - Catalog & Products
edgeFolders.push({
  name: "14. Edge Cases - Catalog & Products",
  item: [
    makeReq(
      "14.1 Create Product with Negative Price (expect 400/422)",
      "POST",
      "{{productUrl}}/api/v1/backoffice/products",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        title: "Negative Price Item",
        slug: "{{badSlug}}",
        sku: "{{badSku}}",
        priceUnit: -99.99,
        quantity: 10,
        status: "ACTIVE",
        description: "Invalid item",
        categoryId: "{{categoryId}}"
      },
      `const ts = Date.now();\npm.collectionVariables.set("badSku", "BAD-" + ts);\npm.collectionVariables.set("badSlug", "bad-" + ts);`,
      `pm.test("Rejects negative price with 400/422", () => pm.expect(pm.response.code).to.be.oneOf([400, 422]));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0422-V");`
    ),
    makeReq(
      "14.2 Regular User Tries to Create Product (expect 403)",
      "POST",
      "{{productUrl}}/api/v1/backoffice/products",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        title: "Unauthorized Product",
        slug: "unauth-prod",
        sku: "UNAUTH-SKU",
        priceUnit: 10.00,
        quantity: 10,
        status: "ACTIVE"
      },
      null,
      `pm.test("Rejects non-admin with 403 Forbidden", () => pm.expect(pm.response.code).to.eql(403));`
    ),
    makeReq(
      "14.3 Get Non-Existent Product (expect 404)",
      "GET",
      "{{productUrl}}/api/v1/products/00000000-0000-0000-0000-000000000000",
      {},
      null,
      null,
      `pm.test("Returns 404 for missing product", () => pm.expect(pm.response.code).to.eql(404));`
    )
  ]
});

// 15. Edge Cases - Cart & Inventory (2PC / Over-reservation)
edgeFolders.push({
  name: "15. Edge Cases - Cart & Inventory",
  item: [
    makeReq(
      "15.1 Add Zero Quantity to Cart (expect 400/422)",
      "POST",
      "{{orderUrl}}/api/v1/carts/me/items",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      { productId: "{{productId}}", quantity: 0 },
      null,
      `pm.test("Rejects zero quantity with 400/422", () => pm.expect(pm.response.code).to.be.oneOf([400, 422]));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0422-V");`
    ),
    makeReq(
      "15.2 Add Non-Existent Product to Cart (expect 404)",
      "POST",
      "{{orderUrl}}/api/v1/carts/me/items",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      { productId: "00000000-0000-0000-0000-000000000000", quantity: 1 },
      null,
      `pm.test("Rejects non-existent product with 404", () => pm.expect(pm.response.code).to.eql(404));`
    ),
    makeReq(
      "15.3 Seed Inventory with Negative Stock (expect 400/422)",
      "POST",
      "{{inventoryUrl}}/api/v1/inventory",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { productId: "{{productId}}", availableQuantity: -10 },
      null,
      `pm.test("Rejects negative stock with 400/422", () => pm.expect(pm.response.code).to.be.oneOf([400, 422]));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0422-V");`
    ),
    makeReq(
      "15.4 Reserve Stock Exceeding Available Capacity (expect 400/409)",
      "POST",
      "{{inventoryUrl}}/api/v1/inventory/{{productId}}/reserve",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { quantity: 999999, orderId: "00000000-0000-0000-0000-000000000001" },
      null,
      `pm.test("Rejects over-reservation with 400/409", () => pm.expect(pm.response.code).to.be.oneOf([400, 409]));
const res = pm.response.json();
pm.expect(res.code).to.eql("INV-3002");`
    )
  ]
});

// 16. Edge Cases - Payment & Webhooks
edgeFolders.push({
  name: "16. Edge Cases - Payment & Webhooks",
  item: [
    makeReq(
      "16.1 Payment Webhook with Invalid Signature (expect 400/401)",
      "POST",
      "{{paymentUrl}}/api/v1/webhooks/payments/vnpay",
      {
        "Content-Type": "application/json",
        "X-Webhook-Signature": "invalid_signature_hex_0123456789abcdef0123456789abcdef0123456789abcdef"
      },
      { event: "payment.succeeded", orderId: "{{orderId}}" },
      null,
      `pm.test("Rejects invalid webhook signature with 400/401", () => pm.expect(pm.response.code).to.be.oneOf([400, 401]));
const res = pm.response.json();
pm.expect(res.code).to.be.oneOf(["PAY-5005", "WEBHOOK_SIGNATURE_INVALID"]);`
    ),
    makeReq(
      "16.2 Payment Webhook Missing Signature (expect 400/401)",
      "POST",
      "{{paymentUrl}}/api/v1/webhooks/payments/vnpay",
      { "Content-Type": "application/json" },
      { event: "payment.succeeded", orderId: "{{orderId}}" },
      null,
      `pm.test("Rejects unsigned webhook with 400/401", () => pm.expect(pm.response.code).to.be.oneOf([400, 401]));`
    ),
    makeReq(
      "16.3 Capture Payment with Non-Existent ID (expect 404)",
      "POST",
      "{{paymentUrl}}/api/v1/payments/00000000-0000-0000-0000-000000000000/capture",
      { "Authorization": "Bearer {{adminToken}}" },
      null,
      null,
      `pm.test("Returns 404 for non-existent payment", () => pm.expect(pm.response.code).to.eql(404));`
    )
  ]
});

// 17. Edge Cases - Shipping Carrier Webhooks
edgeFolders.push({
  name: "17. Edge Cases - Shipping Carrier Webhooks",
  item: [
    makeReq(
      "17.1 Shipping Webhook without Signature (expect 401)",
      "POST",
      "{{shippingUrl}}/api/v1/webhooks/shipping/ghn",
      { "Content-Type": "application/json" },
      { trackingNumber: "GHN999", status: "DELIVERED" },
      null,
      `pm.test("Rejects unsigned carrier webhook with 401", () => pm.expect(pm.response.code).to.eql(401));
const res = pm.response.json();
pm.expect(res.code).to.be.oneOf(["SHP-10004", "SHIPPING_WEBHOOK_SIGNATURE_INVALID"]);`
    ),
    makeReq(
      "17.2 Shipping Webhook for Unknown Carrier (expect 400/404)",
      "POST",
      "{{shippingUrl}}/api/v1/webhooks/shipping/unknown_carrier_xyz",
      { "Content-Type": "application/json" },
      { trackingNumber: "UNKNOWN", status: "DELIVERED" },
      null,
      `pm.test("Rejects unknown carrier with 400/401/404", () => pm.expect(pm.response.code).to.be.oneOf([400, 401, 404]));`
    )
  ]
});

// 18. Edge Cases - RMA Returns & Guest Tracking
edgeFolders.push({
  name: "18. Edge Cases - RMA Returns & Guest Tracking",
  item: [
    makeReq(
      "18.1 Guest Tracking with Wrong Phone (expect 404)",
      "GET",
      "{{orderUrl}}/api/v1/orders/track?orderId={{orderId}}&phone=0000000000",
      {},
      null,
      null,
      `pm.test("Returns 404 for non-matching phone", () => pm.expect(pm.response.code).to.eql(404));
const res = pm.response.json();
pm.expect(res.code).to.eql("ORD-4001");`
    ),
    makeReq(
      "18.2 Request Return with Excessive Amount (expect 400)",
      "POST",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/returns",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        reason: "DEFECTIVE",
        description: "Asking for excessive refund",
        refundAmount: 9999999.00
      },
      null,
      `pm.test("Rejects excessive refund with 400", () => pm.expect(pm.response.code).to.eql(400));
const res = pm.response.json();
pm.expect(res.message).to.include("order.return");`
    ),
    makeReq(
      "18.3 Customer Attempts Admin Return Review (expect 403)",
      "PUT",
      "{{orderUrl}}/api/v1/backoffice/returns/{{returnId}}/review",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      { status: "APPROVED", adminNotes: "Unauthorized review" },
      null,
      `pm.test("Rejects customer review with 403 Forbidden", () => pm.expect(pm.response.code).to.eql(403));`
    )
  ]
});

// 19. Edge Cases - Rating & Order & Media
edgeFolders.push({
  name: "19. Edge Cases - Rating & Order & Media",
  item: [
    makeReq(
      "19.1 Unverified Purchaser Rates Unpurchased Product (expect 400/403)",
      "POST",
      "{{ratingUrl}}/api/v1/ratings",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        productId: "00000000-0000-0000-0000-000000000000",
        rating: 5,
        comment: "Fake spam review"
      },
      null,
      `pm.test("Rejects unverified purchaser with 400/403", () => pm.expect(pm.response.code).to.be.oneOf([400, 403]));
const res = pm.response.json();
pm.expect(res.code).to.eql("RTG-11001");`
    ),
    makeReq(
      "19.2 Submit Rating with Invalid Star Count 10 (expect 400/422)",
      "POST",
      "{{ratingUrl}}/api/v1/ratings",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      {
        productId: "{{productId}}",
        rating: 10,
        comment: "Invalid star rating"
      },
      null,
      `pm.test("Rejects 10 stars with 400/422", () => pm.expect(pm.response.code).to.be.oneOf([400, 422]));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0422-V");`
    ),
    makeReq(
      "19.3 Create Tax Rate with Invalid Country Code (expect 400/422)",
      "POST",
      "{{taxUrl}}/api/v1/backoffice/tax-rates",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      {
        taxClassId: "{{taxClassId}}",
        country: "INVALID_COUNTRY",
        ratePct: 10.0
      },
      null,
      `pm.test("Rejects invalid country with 400/422", () => pm.expect(pm.response.code).to.be.oneOf([400, 422]));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0422-V");`
    ),
    makeReq(
      "19.4 Non-Multipart Payload to Media Upload (expect 400)",
      "POST",
      "{{mediaUrl}}/api/v1/backoffice/medias",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { file: "plain text instead of multipart binary" },
      null,
      `pm.test("Rejects non-multipart with 400", () => pm.expect(pm.response.code).to.eql(400));
const res = pm.response.json();
pm.expect(res.code).to.eql("ERR-0400");`
    )
  ]
});

// ============================================================================
// BUILD PART 3: MICROSERVICES API CATALOG (SERVICE-BY-SERVICE REFERENCE)
// ============================================================================
// We extract the service-by-service catalog items from compCol
const catalogFolders = compCol.item.filter(f => f.name.endsWith("service"));

// Update service catalog endpoints with Authorization and valid headers
catalogFolders.forEach(serviceFolder => {
  if (serviceFolder.item) {
    serviceFolder.item.forEach(req => {
      // Ensure Bearer auth header exists
      const isBackoffice = req.name.includes("backoffice") || req.name.includes("Admin");
      const tokenVar = isBackoffice ? "{{adminToken}}" : "{{userToken}}";
      if (!req.request.header) req.request.header = [];
      const hasAuth = req.request.header.some(h => h.key === "Authorization");
      if (!hasAuth) {
        req.request.header.push({ key: "Authorization", value: `Bearer ${tokenVar}` });
      }
    });
  }
});

// Add new endpoints to order-service in catalog:
const orderServiceFolder = catalogFolders.find(f => f.name === "order service");
if (orderServiceFolder) {
  orderServiceFolder.item.push(
    makeReq(
      "GET /api/v1/orders/track (Public Guest Tracking)",
      "GET",
      "{{orderUrl}}/api/v1/orders/track?orderId={{orderId}}&phone={{customerPhone}}",
      {},
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "POST /api/v1/orders/{orderId}/returns (Customer RMA Return)",
      "POST",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/returns",
      { "Content-Type": "application/json", "Authorization": "Bearer {{userToken}}" },
      { reason: "DEFECTIVE", description: "Product defective upon arrival", refundAmount: 10.00 },
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "GET /api/v1/orders/{orderId}/returns (Order Returns)",
      "GET",
      "{{orderUrl}}/api/v1/orders/{{orderId}}/returns",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "GET /api/v1/orders/returns/me (Customer Returns)",
      "GET",
      "{{orderUrl}}/api/v1/orders/returns/me",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "PUT /api/v1/backoffice/returns/{returnId}/review (Admin Review Return)",
      "PUT",
      "{{orderUrl}}/api/v1/backoffice/returns/{{returnId}}/review",
      { "Content-Type": "application/json", "Authorization": "Bearer {{adminToken}}" },
      { status: "APPROVED", adminNotes: "Approved by administrator" },
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    )
  );
}

// Add gateway service to catalogFolders
catalogFolders.push({
  name: "gateway service",
  item: [
    makeReq(
      "GET /actuator/health (Gateway Health)",
      "GET",
      "{{gatewayUrl}}/actuator/health",
      {},
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "GET /api/v1/products (Gateway Products Routing)",
      "GET",
      "{{gatewayUrl}}/api/v1/products",
      {},
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "GET /api/v1/favourites (Gateway Favourites Routing)",
      "GET",
      "{{gatewayUrl}}/api/v1/favourites",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "GET /api/v1/search (Gateway Search Routing)",
      "GET",
      "{{gatewayUrl}}/api/v1/search?q=MacBook",
      { "Authorization": "Bearer {{userToken}}" },
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    ),
    makeReq(
      "GET /api/v1/orders/track (Gateway Guest Tracking Routing)",
      "GET",
      "{{gatewayUrl}}/api/v1/orders/track?orderId={{orderId}}&phone={{customerPhone}}",
      {},
      null,
      null,
      `pm.test("Status is below 500", () => pm.expect(pm.response.code).to.be.below(500));`
    )
  ]
});

// Combine all into the Master Collection
const masterCollection = {
  info: {
    _postman_id: "7d0d0be4-d7b1-4bb2-b5e0-master-petproject",
    name: "Petproject API — Master Comprehensive & E2E Suite",
    description: "Single Definitive Postman Collection for Petproject Microservices Platform.\n\n" +
      "Contains:\n" +
      "1. PART 1: End-to-End Choreographed Business Lifecycle (Happy Path with real data chaining)\n" +
      "2. PART 2: Comprehensive Edge Cases & Negative Security Audits (Auth, Catalog, 2PC Inventory, Webhooks, Verified Buyer Gate, RMA Returns)\n" +
      "3. PART 3: Complete Microservices API Endpoint Catalog (All 14 microservices and 115+ endpoints)",
    schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  variable: collectionVariables,
  item: [
    {
      name: "=== PART 1: E2E BUSINESS LIFECYCLE ===",
      description: "Sequential happy path executing the complete business workflow from Auth to Fulfillment, Reviews, and RMA Returns.",
      item: e2eFolders
    },
    {
      name: "=== PART 2: EDGE CASES & SECURITY AUDITS ===",
      description: "Negative testing verifying boundaries, invalid payloads, signature tamperings, rate limits, and security constraints.",
      item: edgeFolders
    },
    {
      name: "=== PART 3: FLEET SERVICE CATALOG (14 SERVICES) ===",
      description: "Service-by-service catalog of all controller endpoints across all 14 microservices.",
      item: catalogFolders
    }
  ]
};

// Write out to petproject-comprehensive.postman_collection.json
const targetPath = path.join(__dirname, "petproject-comprehensive.postman_collection.json");
fs.writeFileSync(targetPath, JSON.stringify(masterCollection, null, 2), "utf8");
console.log("Successfully generated Master Collection at:", targetPath);

// Also sync petproject-e2e-business-flow.postman_collection.json so E2E-only runners stay in sync!
const flowSyncCollection = {
  info: {
    _postman_id: "c48f8a91-b13a-4cd5-839e-flow-petproject",
    name: "Petproject - Full End-to-End Business Flow",
    description: "Dedicated End-to-End Business Flow and Edge Cases Suite for Petproject Microservices Platform.",
    schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  variable: collectionVariables,
  item: [...e2eFolders, ...edgeFolders]
};
const flowTargetPath = path.join(__dirname, "petproject-e2e-business-flow.postman_collection.json");
fs.writeFileSync(flowTargetPath, JSON.stringify(flowSyncCollection, null, 2), "utf8");
console.log("Successfully synced E2E flow collection at:", flowTargetPath);
