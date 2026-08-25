# Service Catalogue

> Endpoints, request/response bodies, auth requirements, and dependencies for
> every microservice in the platform. All entries mirror the reference repo
> [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices);
> the workspace should reproduce them 1:1, with `com.ecommerce.*` renamed to
> `com.shop.*`.
>
> Convention: **M** = method · **Path** = full path served by the service —
> every service maps `/api/v1/...` (the gateway forwards the full path with no
> rewrite; see [ROADMAP §3.3](./ROADMAP.md)) · **Auth** = required JWT
> scope/role · **Body** = request DTO · **Resp** = response envelope
> (`ApiResponse<T>`) · **Source** = reference URL.

---

## 0. Conventions

| Field | Value |
|-------|-------|
| Base path prefix | `/api/v1` (pinned in [`ApiPaths.java`](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib/constants/ApiPaths.java)) |
| Auth header | `Authorization: Bearer <jwt>` (validated by gateway) |
| Roles | `ADMIN`, `USER` (Keycloak realm roles) |
| Response envelope | `ApiResponse<T> = { success, code, message, data, errors, path, traceId, timestamp }` |
| Errors | 4xx/5xx carry `ApiResponse.error(code, message, errors[], path)` |
| Date format | ISO-8601 (`LOCAL_DATE_TIME_FORMAT` constant) |
| Pagination | `?page=0&size=10&sortBy=id&sortOrder=ASC` |
| Idempotency | POST /api/v1/orders uses `Idempotency-Key` header (recommended) |

---

## 1. auth-service `:8088`

### 1.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `User` | `users` | `user_id`, `user_name` (unique), `email` (unique, NaturalId), `phone_number` (unique), `full_name`, `gender`, `image_url`, `keycloak_user_id` (unique) |
| `Role` | `roles` | `role_id`, `role_name` (enum `USER`/`ADMIN`) |
| `RoleName` (enum) | — | `USER`, `ADMIN` |
| Join table | `user_role` | `user_id`, `role_id` |

Source: [User.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/entity/User.java),
[Role.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/entity/Role.java).

### 1.2 Endpoints — `/api/v1/auth` (AuthController) ✅ implemented

| M | Path | Auth | Body | Resp | Notes |
|---|------|------|------|------|-------|
| `POST` | `/api/v1/auth/sign-up` | public | `RegisterRequest { username, email, fullName, password, phone, gender, roles? }` | `ApiResponse<Void>` | Creates user in Keycloak (admin API) then mirrors local DB. Compensating delete on failure |
| `POST` | `/api/v1/auth/login` | public | `LoginRequest { username, password }` | `ApiResponse<TokenResponse>` | **ROPC** grant via `KeycloakTokenClient` (workspace SSO-lite, not the reference's redirect SSO) |
| `POST` | `/api/v1/auth/refresh` | public | `RefreshTokenRequest { refreshToken }` | `ApiResponse<TokenResponse>` | Forward to Keycloak |
| `POST` | `/api/v1/auth/logout` | public | `RefreshTokenRequest { refreshToken }` | `ApiResponse<Void>` | Revoke refresh token |

**Deferred (reference SSO)**: `GET /api/v1/auth/login` (302), `GET /callback`,
`GET /session` — not implemented; workspace uses ROPC instead.

Source: [AuthController.java](../auth-service/src/main/java/com/shop/authservice/controller/AuthController.java).

### 1.3 Endpoints — `/api/v1/users` (UserController) ✅ implemented

| M | Path | Auth | Body | Resp | Notes |
|---|------|------|------|------|-------|
| `GET` | `/api/v1/users/me` | authenticated | — | `ApiResponse<UserResponse>` | Current user from JWT subject |
| `PUT` | `/api/v1/users/me` | authenticated | `UpdateUserRequest { fullName?, email?, gender?, phone?, avatar? }` | `ApiResponse<UserResponse>` | |
| `PUT` | `/api/v1/users/me/password` | authenticated | `ChangePasswordRequest { oldPassword, newPassword, confirmPassword }` | `ApiResponse<Void>` | Verifies old password via Keycloak before reset |
| `DELETE` | `/api/v1/users/me` | authenticated | — | `ApiResponse<Void>` | Soft delete current user |
| `GET` | `/api/v1/users/{id}` | ADMIN | — | `ApiResponse<UserResponse>` | Lookup by id |
| `GET` | `/api/v1/users?page=&size=&sortBy=&sortOrder=` | ADMIN | — | `ApiResponse<Page<UserResponse>>` | Paginated list |
| `PUT` | `/api/v1/users/{id}/restore` | ADMIN | — | `ApiResponse<Void>` | Restore soft-deleted user |

Source: [UserController.java](../auth-service/src/main/java/com/shop/authservice/controller/UserController.java).

### 1.4 Endpoints — `/api/v1/roles` (RoleController) ⏳ NOT implemented

`RoleService` + `RoleServiceImpl` exist (`findByName` / `assignRole` /
`revokeRole` / `getUserRoles`) but no controller exposes them yet. Planned
endpoints (reference):

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `POST` | `/api/v1/roles/users/{userId}/assign` | ADMIN | `"USER"` (raw role name) | `ApiResponse<Void>` |
| `POST` | `/api/v1/roles/users/{userId}/revoke` | ADMIN | `"USER"` | `ApiResponse<Void>` |
| `GET` | `/api/v1/roles/users/{userId}` | ADMIN | — | `ApiResponse<List<String>>` |

Source: [RoleController.java (reference)](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/controller/RoleController.java).

### 1.5 Service dependencies

| Calls | Direction | Purpose |
|-------|-----------|---------|
| Keycloak `admin/realms/{realm}/users` | outbound (REST) | Create / delete user in Keycloak |
| Keycloak `protocol/openid-connect/token` | outbound (REST) | Refresh + logout |
| Keycloak `admin/realms/{realm}/roles` | outbound (REST) | Assign / revoke realm roles |
| Postgres `authservice` | inbound | Persist user mirror, roles |

### 1.6 Changelog

`db/changelog/db.changelog-master.yaml` (ref):
```yaml
databaseChangeLog:
  - includeAll:
      path: ddl
      relativeToChangelogFile: true
```

`db/changelog/ddl/001-init.yaml` will create:

```sql
CREATE TABLE users (
  user_id          BIGSERIAL PRIMARY KEY,
  user_name        VARCHAR(100) NOT NULL UNIQUE,
  email            VARCHAR(50)  NOT NULL UNIQUE,
  phone_number     VARCHAR(11)  UNIQUE,
  full_name        VARCHAR(100),
  gender           VARCHAR(20)  NOT NULL,
  image_url        TEXT,
  keycloak_user_id VARCHAR(36)  UNIQUE
);

CREATE TABLE roles (
  role_id   BIGSERIAL PRIMARY KEY,
  role_name VARCHAR(20) NOT NULL UNIQUE
);

CREATE TABLE user_role (
  user_id BIGINT NOT NULL REFERENCES users(user_id),
  role_id BIGINT NOT NULL REFERENCES roles(role_id),
  PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_users_username ON users(user_name);
CREATE INDEX idx_users_email    ON users(email);
CREATE INDEX idx_users_keycloak ON users(keycloak_user_id);
```

---

## 2. product-service `:8086`

### 2.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Category` | `categories` | `category_id`, `category_title`, `image_url`, `parent_id` (self-FK, optional) |
| `Product` | `products` | `product_id`, `product_title`, `image_url`, `sku` (unique), `price_unit` (decimal), `quantity`, `category_id` (FK) |

Source: [Product.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/product-service/src/main/java/com/ecommerce/productservice/entity/Product.java),
[Category.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/product-service/src/main/java/com/ecommerce/productservice/entity/Category.java),
[AbstractMappedEntity.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/product-service/src/main/java/com/ecommerce/productservice/entity/AbstractMappedEntity.java) (created/updated/last-modified).

### 2.2 Endpoints — `/api/v1/products` (workspace path; reference uses `/api/products`)

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/products` | USER/ADMIN | — | `ResponseEntity<List<ProductDto>>` |
| `GET` | `/api/v1/products/{productId}` | USER/ADMIN | — | `ResponseEntity<ProductDto>` |
| `POST` | `/api/v1/products` | ADMIN | `ProductDto` | `ResponseEntity<ProductDto>` |
| `PUT` | `/api/v1/products` | ADMIN | `ProductDto` | `ResponseEntity<ProductDto>` |
| `PUT` | `/api/v1/products/{productId}` | ADMIN | `ProductDto` | `ResponseEntity<ProductDto>` |
| `DELETE` | `/api/v1/products/{productId}` | ADMIN | — | `ResponseEntity<Boolean>` |

Source: [ProductController.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/product-service/src/main/java/com/ecommerce/productservice/controller/ProductController.java).

### 2.3 Endpoints — `/api/v1/categories` (workspace path; reference uses `/api/categories`)

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/categories` | USER/ADMIN | — | `List<CategoryDto>` |
| `GET` | `/api/v1/categories/{categoryId}` | USER/ADMIN | — | `CategoryDto` |
| `POST` | `/api/v1/categories` | ADMIN | `CategoryDto` | `CategoryDto` |
| `PUT` | `/api/v1/categories` | ADMIN | `CategoryDto` | `CategoryDto` |
| `PUT` | `/api/v1/categories/{categoryId}` | ADMIN | `CategoryDto` | `CategoryDto` |
| `DELETE` | `/api/v1/categories/{categoryId}` | ADMIN | — | `Boolean` |

### 2.4 Kafka events

| Topic | Event | Payload |
|-------|-------|---------|
| `product.indexed.v1` | `ProductIndexedEvent { productId, action: CREATED\|UPDATED\|DELETED, snapshot }` | sent by product-service, consumed by search-service |

### 2.5 Service dependencies

| Calls | Direction |
|-------|-----------|
| Kafka `product.indexed.v1` | outbound |
| Postgres `productservice` | inbound |

---

## 3. order-service `:8084`

### 3.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Order` | `orders` | `order_id`, `order_date`, `order_desc`, `order_fee` (decimal), `product_id`, `cart_id` (FK) |
| `Cart` | `carts` | `cart_id`, `user_id`, `cart_date`, `cart_total` (decimal) |
| `CartItem` | `cart_items` | `cart_item_id`, `cart_id` (FK), `product_id`, `quantity`, `price` |

Source: [Order.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/order-service/src/main/java/com/ecommerce/orderservice/entity/Order.java),
[Cart.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/order-service/src/main/java/com/ecommerce/orderservice/entity/Cart.java).

### 3.2 Endpoints — `/api/v1/orders` (workspace path; reference uses `/api/orders`)

| M | Path | Auth | Body | Resp | Notes |
|---|------|------|------|------|-------|
| `GET` | `/api/v1/orders` | USER/ADMIN | — | `List<OrderDto>` | All orders (filter on userId in body in real impl) |
| `GET` | `/api/v1/orders/all?page=&size=&sortBy=&sortOrder=` | USER/ADMIN | — | `Page<OrderDto>` | Paginated |
| `GET` | `/api/v1/orders/{orderId}` | USER/ADMIN | — | `OrderDto` | |
| `POST` | `/api/v1/orders` | USER | `OrderDto` | `OrderDto` | Reserve stock + publish `OrderCreated` |
| `PUT` | `/api/v1/orders` | ADMIN | `OrderDto` | `OrderDto` | Force update |
| `PUT` | `/api/v1/orders/{orderId}` | USER | `OrderDto` | `OrderDto` | Update with id |
| `DELETE` | `/api/v1/orders/{orderId}` | USER/ADMIN | — | `Boolean` | |
| `GET` | `/api/v1/orders/existOrderId?orderId=` | any | — | `Boolean` | Existence check |

Source: [OrderController.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/order-service/src/main/java/com/ecommerce/orderservice/controller/OrderController.java).

### 3.3 Endpoints — `/api/v1/carts` (workspace path; reference uses `/api/carts`)

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/carts` | USER | — | `List<CartDto>` |
| `GET` | `/api/v1/carts/{cartId}` | USER | — | `CartDto` |
| `POST` | `/api/v1/carts` | USER | `CartDto` | `CartDto` |
| `PUT` | `/api/v1/carts` | USER | `CartDto` | `CartDto` |
| `DELETE` | `/api/v1/carts/{cartId}` | USER | — | `Boolean` |

### 3.4 Kafka events

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `order.created.v1` | order-service | payment, search, notification | `OrderCreatedEvent { orderId, userId, items[], totalAmount }` |
| `order.updated.v1` | order-service | notification | `OrderUpdatedEvent { orderId, status, updatedAt }` |

### 3.5 Service dependencies

| Calls | Direction |
|-------|-----------|
| product-service `GET /api/v1/products/{id}` | outbound (Feign client `CallAPI.java`) |
| inventory-service `GET /api/v1/inventory/{productId}` | outbound |
| tax-service `GET /api/v1/backoffice/tax-rates/{id}` | outbound |
| promotion-service `POST /api/v1/backoffice/promotions/apply` | outbound |
| Kafka `order.created.v1`, `order.updated.v1` | outbound |
| Postgres `orderservice` | inbound |

---

## 4. payment-service `:8085`

### 4.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Payment` | `payments` | `payment_id`, `order_id` (FK), `user_id`, `amount` (decimal), `currency`, `status` (PENDING/SUCCESS/FAILED/REFUNDED), `payment_method` (CARD/PAYPAL/STRIPE), `transaction_id`, `created_at`, `paid_at` |
| `Refund` | `refunds` | `refund_id`, `payment_id`, `amount`, `reason`, `status` |

### 4.2 Endpoints — `/api/v1/payments`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `POST` | `/api/v1/payments` | USER | `PaymentRequest { orderId, paymentMethod, returnUrl? }` | `ApiResponse<PaymentResponse>` |
| `GET` | `/api/v1/payments/{paymentId}` | USER/ADMIN | — | `ApiResponse<PaymentDto>` |
| `GET` | `/api/v1/payments/order/{orderId}` | USER/ADMIN | — | `ApiResponse<List<PaymentDto>>` |
| `POST` | `/api/v1/payments/{paymentId}/refund` | ADMIN | `RefundRequest { amount, reason }` | `ApiResponse<RefundDto>` |
| `POST` | `/api/v1/payments/webhook/stripe` | public (signature verify) | Stripe event | `ApiResponse<Void>` |
| `GET` | `/api/v1/payments/admin/all?page=&size=` | ADMIN | — | `ApiResponse<Page<PaymentDto>>` |

### 4.3 Kafka events

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `payment.success.v1` | payment-service | order-service, notification | `PaymentSuccessEvent { paymentId, orderId, amount, paidAt }` |
| `payment.failed.v1` | payment-service | order-service | `PaymentFailedEvent { paymentId, orderId, reason, retryable }` |
| `order.created.v1` | (consumer) | payment-service | triggers checkout |

### 4.4 External integrations

| Vendor | SDK | Reference |
|--------|-----|-----------|
| Stripe | `com.stripe:stripe-java:24.x` | [ref code](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/payment-service/src/main/java/com/ecommerce/paymentservice/http) (`http/StripeClient.java`) |
| PayPal | `com.paypal.sdk:paypal-checkout-servers-sdk:2.x` | optional |

---

## 5. inventory-service `:8082`

### 5.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Inventory` | `inventory` | `inventory_id`, `product_id` (unique), `available_quantity`, `reserved_quantity`, `last_updated` |

Note: ref uses `model/` not `entity/` — workspace should keep `entity/` for consistency.

### 5.2 Endpoints — `/api/v1/inventory`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/inventory` | USER/ADMIN | — | `List<InventoryDto>` |
| `GET` | `/api/v1/inventory/{productId}` | USER/ADMIN | — | `InventoryDto` |
| `POST` | `/api/v1/inventory` | ADMIN | `InventoryDto { productId, availableQuantity }` | `InventoryDto` |
| `PUT` | `/api/v1/inventory/{productId}` | ADMIN | `InventoryDto` | `InventoryDto` |
| `POST` | `/api/v1/inventory/{productId}/reserve` | internal (order-service) | `ReserveRequest { quantity }` | `ReserveResponse { reservationId, expiresAt }` |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/commit` | internal | — | `Void` |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/release` | internal | — | `Void` |
| `DELETE` | `/api/v1/inventory/{productId}` | ADMIN | — | `Boolean` |

---

## 6. shipping-service `:8087`

### 6.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Shipping` | `shippings` | `shipping_id`, `order_id`, `user_id`, `address`, `city`, `country`, `zip_code`, `phone`, `carrier` (DHL/FEDEX/…), `tracking_number`, `status` (PENDING/SHIPPED/IN_TRANSIT/DELIVERED), `estimated_delivery` |

### 6.2 Endpoints — `/api/v1/shippings`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/shippings` | USER/ADMIN | — | `List<ShippingDto>` |
| `GET` | `/api/v1/shippings/{shippingId}` | USER/ADMIN | — | `ShippingDto` |
| `GET` | `/api/v1/shippings/order/{orderId}` | USER/ADMIN | — | `ShippingDto` |
| `POST` | `/api/v1/shippings` | USER | `ShippingDto` | `ShippingDto` |
| `PUT` | `/api/v1/shippings/{shippingId}` | ADMIN | `ShippingDto` | `ShippingDto` |
| `POST` | `/api/v1/shippings/{shippingId}/track` | USER/ADMIN | — | `TrackingDto { status, lastEvent, history[] }` |
| `DELETE` | `/api/v1/shippings/{shippingId}` | ADMIN | — | `Boolean` |

---

## 7. favourite-service `:8081`

### 7.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Favourite` | `favourites` | `favourite_id`, `user_id`, `product_id`, `created_at` |

### 7.2 Endpoints — `/api/v1/favourites`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/favourites` | USER | — | `List<FavouriteDto>` (current user's favourites) |
| `GET` | `/api/v1/favourites/{favouriteId}` | USER | — | `FavouriteDto` |
| `POST` | `/api/v1/favourites` | USER | `FavouriteCreateRequest { productId }` | `FavouriteDto` |
| `DELETE` | `/api/v1/favourites/{favouriteId}` | USER | — | `Boolean` |
| `DELETE` | `/api/v1/favourites/by-product/{productId}` | USER | — | `Boolean` |

Source: [favourite-service tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/favourite-service/src/main/java/com/ecommerce/favouriteservice).

---

## 8. rating-service `:8089`

Split base paths — storefront is for end users, backoffice is for admins.

### 8.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Rating` | `ratings` | `rating_id`, `user_id`, `product_id`, `score` (1–5), `comment`, `created_at`, `status` (PENDING/APPROVED/REJECTED) |

### 8.2 Endpoints — `/api/v1/storefront/ratings`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/storefront/ratings/product/{productId}?page=&size=` | USER | — | `Page<RatingDto>` |
| `GET` | `/api/v1/storefront/ratings/me` | USER | — | `List<RatingDto>` |
| `POST` | `/api/v1/storefront/ratings` | USER | `RatingCreateRequest { productId, score, comment }` | `RatingDto` |
| `PUT` | `/api/v1/storefront/ratings/{ratingId}` | USER (owner) | `RatingUpdateRequest { score?, comment? }` | `RatingDto` |
| `DELETE` | `/api/v1/storefront/ratings/{ratingId}` | USER (owner) | — | `Boolean` |

### 8.3 Endpoints — `/api/v1/backoffice/ratings`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/backoffice/ratings?status=&page=&size=` | ADMIN | — | `Page<RatingDto>` |
| `POST` | `/api/v1/backoffice/ratings/{ratingId}/approve` | ADMIN | — | `RatingDto` |
| `POST` | `/api/v1/backoffice/ratings/{ratingId}/reject` | ADMIN | `RejectRequest { reason }` | `RatingDto` |
| `DELETE` | `/api/v1/backoffice/ratings/{ratingId}` | ADMIN | — | `Boolean` |

---

## 9. media-service `:8083`

### 9.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Media` | `medias` | `media_id`, `object_key` (S3 key in RustFS), `content_type`, `size_bytes`, `uploaded_by`, `created_at` |

### 9.2 Endpoints — `/api/v1/medias`

| M | Path | Auth | Body | Resp | Notes |
|---|------|------|------|------|-------|
| `POST` | `/api/v1/medias/upload` | USER/ADMIN | `multipart/form-data` file | `MediaDto { mediaId, objectKey, presignedUrl }` | Streams to RustFS via `common-storage` |
| `POST` | `/api/v1/medias/upload/presigned` | USER/ADMIN | `PresignRequest { filename, contentType }` | `PresignResponse { uploadUrl, objectKey }` | Returns presigned PUT URL — frontend uploads directly |
| `GET` | `/api/v1/medias/{mediaId}` | USER/ADMIN | — | `302` → presigned GET URL (TTL 5 min) | |
| `GET` | `/api/v1/medias/{mediaId}/metadata` | USER/ADMIN | — | `MediaDto` | |
| `DELETE` | `/api/v1/medias/{mediaId}` | ADMIN | — | `Boolean` | Deletes from RustFS + DB |

### 9.3 Storage stack

- `common-storage/ObjectStorageService` — interface
- `S3ObjectStorageService` — `software.amazon.awssdk:s3:2.31.x` impl
- Endpoint: `http://rustfs:9000` (dev) / `https://s3.amazonaws.com` (prod)

---

## 10. tax-service `:8091`

### 10.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `TaxClass` | `tax_classes` | `tax_class_id`, `name`, `description` |
| `TaxRate` | `tax_rates` | `tax_rate_id`, `tax_class_id` (FK), `country`, `state`, `postal_code`, `rate` (decimal), `priority` |

### 10.2 Endpoints — `/api/v1/backoffice/tax-classes`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/backoffice/tax-classes` | ADMIN | — | `List<TaxClassDto>` |
| `GET` | `/api/v1/backoffice/tax-classes/{id}` | ADMIN | — | `TaxClassDto` |
| `POST` | `/api/v1/backoffice/tax-classes` | ADMIN | `TaxClassDto` | `TaxClassDto` |
| `PUT` | `/api/v1/backoffice/tax-classes/{id}` | ADMIN | `TaxClassDto` | `TaxClassDto` |
| `DELETE` | `/api/v1/backoffice/tax-classes/{id}` | ADMIN | — | `Boolean` |

### 10.3 Endpoints — `/api/v1/backoffice/tax-rates`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/backoffice/tax-rates` | ADMIN | — | `List<TaxRateDto>` |
| `GET` | `/api/v1/backoffice/tax-rates/{id}` | ADMIN | — | `TaxRateDto` |
| `POST` | `/api/v1/backoffice/tax-rates/calculate` | internal (order-service) | `CalculateRequest { taxClassId, country, postalCode, amount }` | `CalculateResponse { taxAmount, appliedRate }` |
| `POST` | `/api/v1/backoffice/tax-rates` | ADMIN | `TaxRateDto` | `TaxRateDto` |
| `PUT` | `/api/v1/backoffice/tax-rates/{id}` | ADMIN | `TaxRateDto` | `TaxRateDto` |
| `DELETE` | `/api/v1/backoffice/tax-rates/{id}` | ADMIN | — | `Boolean` |

---

## 11. promotion-service `:8093`

### 11.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Promotion` | `promotions` | `promotion_id`, `code` (unique), `name`, `type` (PERCENTAGE/FIXED_AMOUNT), `value` (decimal), `min_order_amount`, `max_discount_amount`, `start_date`, `end_date`, `usage_limit`, `usage_count`, `status` (ACTIVE/INACTIVE/EXPIRED) |
| `PromotionUsage` | `promotion_usage` | `usage_id`, `promotion_id` (FK), `user_id`, `order_id`, `used_at` |

### 11.2 Endpoints — `/api/v1/backoffice/promotions`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/backoffice/promotions` | ADMIN | — | `List<PromotionDto>` |
| `GET` | `/api/v1/backoffice/promotions/{id}` | ADMIN | — | `PromotionDto` |
| `GET` | `/api/v1/backoffice/promotions/by-code/{code}` | USER | — | `PromotionDto` |
| `POST` | `/api/v1/backoffice/promotions` | ADMIN | `PromotionDto` | `PromotionDto` |
| `PUT` | `/api/v1/backoffice/promotions/{id}` | ADMIN | `PromotionDto` | `PromotionDto` |
| `DELETE` | `/api/v1/backoffice/promotions/{id}` | ADMIN | — | `Boolean` |
| `POST` | `/api/v1/backoffice/promotions/apply` | internal (order-service) | `ApplyRequest { code, orderAmount, userId }` | `ApplyResponse { discountAmount, finalAmount }` |

---

## 12. search-service `:8094`

### 12.1 Domain (no SQL — ES only)

| Document | ES index | Fields |
|----------|---------|--------|
| `ProductDoc` | `products` | `productId (keyword)`, `title (text + keyword)`, `description (text)`, `category (keyword)`, `price (double)`, `imageUrl (keyword)`, `score (float, BM25)`, `createdAt (date)` |
| `RatingDoc` | `ratings` | `ratingId`, `productId`, `userId`, `score`, `comment`, `createdAt`, `status` |

### 12.2 Endpoints — `/api/v1/storefront/search`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/storefront/search/products?q=&page=&size=&category=` | USER/ADMIN | — | `SearchResponse<ProductDoc> { total, hits[] }` |
| `GET` | `/api/v1/storefront/search/products/suggest?prefix=` | USER | — | `List<String>` (autocomplete) |
| `GET` | `/api/v1/storefront/search/ratings/product/{productId}` | USER | — | `List<RatingDoc>` |

### 12.3 Kafka consumers

| Topic | Consumer | Action |
|-------|----------|--------|
| `product.indexed.v1` | `ProductIndexListener` | ES `index/update/delete` on `products` index |
| `order.created.v1` | `OrderIndexListener` | update product popularity counter in ES |

### 12.4 ES client

- `co.elastic.clients:elasticsearch-java:9.4.x` (workspace target)
- Ref uses 8.15; bump documented in [ROADMAP §5 R4](./ROADMAP.md)

---

## 13. notification-service `:8090`

### 13.1 Domain model

| Entity | Table | Key fields |
|--------|-------|------------|
| `Notification` | `notifications` | `notification_id`, `user_id`, `type` (ORDER/PAYMENT/SYSTEM), `title`, `body`, `read`, `created_at` |
| `Email` | `emails` | `email_id`, `to_address`, `subject`, `body`, `status` (QUEUED/SENT/FAILED), `sent_at`, `retry_count` |

### 13.2 Endpoints — `/api/v1/notifications`

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/notifications/me?page=` | USER | — | `Page<NotificationDto>` |
| `GET` | `/api/v1/notifications/me/unread-count` | USER | — | `long` |
| `POST` | `/api/v1/notifications/me/{id}/read` | USER | — | `Void` |
| `POST` | `/api/v1/notifications/me/read-all` | USER | — | `Void` |

### 13.3 Endpoints — `/api/v1/emails` (backoffice / debug)

| M | Path | Auth | Body | Resp |
|---|------|------|------|------|
| `GET` | `/api/v1/emails?status=&page=` | ADMIN | — | `Page<EmailDto>` |
| `POST` | `/api/v1/emails/{id}/retry` | ADMIN | — | `EmailDto` |

### 13.4 Kafka consumers

| Topic | Listener | Action |
|-------|----------|--------|
| `order.created.v1` | `OrderCreatedEmailListener` | Send order confirmation email |
| `order.updated.v1` | `OrderUpdatedEmailListener` | Send status change email |
| `payment.success.v1` | `PaymentSuccessEmailListener` | Send payment receipt |
| `payment.failed.v1` | `PaymentFailedEmailListener` | Send payment failure alert |

### 13.5 SMTP

- `spring-boot-starter-mail` (JavaMailSender)
- Config: `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_HOST` (smtp.gmail.com dev)

---

## 14. Quick reference — all routes through the gateway

```
POST   /api/v1/auth/sign-up
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout

GET    /api/v1/users/me
PUT    /api/v1/users/me
PUT    /api/v1/users/me/password
DELETE /api/v1/users/me
GET    /api/v1/users/{id}
GET    /api/v1/users?page=&size=&sortBy=&sortOrder=
PUT    /api/v1/users/{id}/restore

POST   /api/v1/roles/users/{userId}/assign
POST   /api/v1/roles/users/{userId}/revoke
GET    /api/v1/roles/users/{userId}

GET    /api/v1/products
GET    /api/v1/products/{id}
POST   /api/v1/products
PUT    /api/v1/products
PUT    /api/v1/products/{id}
DELETE /api/v1/products/{id}

GET    /api/v1/categories
GET    /api/v1/categories/{id}
POST   /api/v1/categories
PUT    /api/v1/categories
PUT    /api/v1/categories/{id}
DELETE /api/v1/categories/{id}

GET    /api/v1/orders
GET    /api/v1/orders/all
GET    /api/v1/orders/{id}
POST   /api/v1/orders
PUT    /api/v1/orders
PUT    /api/v1/orders/{id}
DELETE /api/v1/orders/{id}
GET    /api/v1/orders/existOrderId

GET    /api/v1/carts
GET    /api/v1/carts/{id}
POST   /api/v1/carts
PUT    /api/v1/carts
DELETE /api/v1/carts/{id}

POST   /api/v1/payments
GET    /api/v1/payments/{id}
GET    /api/v1/payments/order/{orderId}
POST   /api/v1/payments/{id}/refund
POST   /api/v1/payments/webhook/stripe
GET    /api/v1/payments/admin/all

GET    /api/v1/inventory
GET    /api/v1/inventory/{productId}
POST   /api/v1/inventory
PUT    /api/v1/inventory/{productId}
POST   /api/v1/inventory/{productId}/reserve
POST   /api/v1/inventory/reservations/{id}/commit
POST   /api/v1/inventory/reservations/{id}/release
DELETE /api/v1/inventory/{productId}

GET    /api/v1/shippings
GET    /api/v1/shippings/{id}
GET    /api/v1/shippings/order/{orderId}
POST   /api/v1/shippings
PUT    /api/v1/shippings/{id}
POST   /api/v1/shippings/{id}/track
DELETE /api/v1/shippings/{id}

GET    /api/v1/favourites
GET    /api/v1/favourites/{id}
POST   /api/v1/favourites
DELETE /api/v1/favourites/{id}
DELETE /api/v1/favourites/by-product/{productId}

GET    /api/v1/storefront/ratings/product/{productId}
GET    /api/v1/storefront/ratings/me
POST   /api/v1/storefront/ratings
PUT    /api/v1/storefront/ratings/{id}
DELETE /api/v1/storefront/ratings/{id}

GET    /api/v1/backoffice/ratings
POST   /api/v1/backoffice/ratings/{id}/approve
POST   /api/v1/backoffice/ratings/{id}/reject
DELETE /api/v1/backoffice/ratings/{id}

POST   /api/v1/medias/upload
POST   /api/v1/medias/upload/presigned
GET    /api/v1/medias/{id}
GET    /api/v1/medias/{id}/metadata
DELETE /api/v1/medias/{id}

GET    /api/v1/backoffice/tax-classes
GET    /api/v1/backoffice/tax-classes/{id}
POST   /api/v1/backoffice/tax-classes
PUT    /api/v1/backoffice/tax-classes/{id}
DELETE /api/v1/backoffice/tax-classes/{id}

GET    /api/v1/backoffice/tax-rates
GET    /api/v1/backoffice/tax-rates/{id}
POST   /api/v1/backoffice/tax-rates/calculate
POST   /api/v1/backoffice/tax-rates
PUT    /api/v1/backoffice/tax-rates/{id}
DELETE /api/v1/backoffice/tax-rates/{id}

GET    /api/v1/backoffice/promotions
GET    /api/v1/backoffice/promotions/{id}
GET    /api/v1/backoffice/promotions/by-code/{code}
POST   /api/v1/backoffice/promotions
PUT    /api/v1/backoffice/promotions/{id}
DELETE /api/v1/backoffice/promotions/{id}
POST   /api/v1/backoffice/promotions/apply

GET    /api/v1/storefront/search/products?q=
GET    /api/v1/storefront/search/products/suggest?prefix=
GET    /api/v1/storefront/search/ratings/product/{productId}

GET    /api/v1/notifications/me
GET    /api/v1/notifications/me/unread-count
POST   /api/v1/notifications/me/{id}/read
POST   /api/v1/notifications/me/read-all

GET    /api/v1/emails
POST   /api/v1/emails/{id}/retry
```

---

See also — [`ROADMAP.md`](./ROADMAP.md) · [`ARCHITECTURE.md`](./ARCHITECTURE.md) · [`COMMON-LIB-REFERENCE.md`](./COMMON-LIB-REFERENCE.md)
