# API endpoint inventory

Generated from `@*Mapping` annotations in every `*Controller.java`.

**Unique endpoints:** 111

| Method | Path | Source |
|---|---|---|
| POST | `/api/v1/auth/login` | `auth-service/src/main/java/com/shop/authservice/controller/AuthController.java` |
| POST | `/api/v1/auth/logout` | `auth-service/src/main/java/com/shop/authservice/controller/AuthController.java` |
| POST | `/api/v1/auth/refresh` | `auth-service/src/main/java/com/shop/authservice/controller/AuthController.java` |
| POST | `/api/v1/auth/sign-up` | `auth-service/src/main/java/com/shop/authservice/controller/AuthController.java` |
| DELETE | `/api/v1/backoffice/brands/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeBrandController.java` |
| PUT | `/api/v1/backoffice/brands/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeBrandController.java` |
| POST | `/api/v1/backoffice/brands` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeBrandController.java` |
| DELETE | `/api/v1/backoffice/categories/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeCategoryController.java` |
| PUT | `/api/v1/backoffice/categories/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeCategoryController.java` |
| POST | `/api/v1/backoffice/categories` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeCategoryController.java` |
| DELETE | `/api/v1/backoffice/medias/{id}` | `media-service/src/main/java/com/shop/mediaservice/controller/BackofficeMediaController.java` |
| POST | `/api/v1/backoffice/medias` | `media-service/src/main/java/com/shop/mediaservice/controller/BackofficeMediaController.java` |
| GET | `/api/v1/backoffice/notifications/{id}` | `notification-service/src/main/java/com/shop/notificationservice/controller/BackofficeNotificationController.java` |
| GET | `/api/v1/backoffice/notifications` | `notification-service/src/main/java/com/shop/notificationservice/controller/BackofficeNotificationController.java` |
| GET | `/api/v1/backoffice/payments/{id}` | `payment-service/src/main/java/com/shop/paymentservice/controller/BackofficePaymentController.java` |
| GET | `/api/v1/backoffice/payments` | `payment-service/src/main/java/com/shop/paymentservice/controller/BackofficePaymentController.java` |
| DELETE | `/api/v1/backoffice/products/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeProductController.java` |
| PUT | `/api/v1/backoffice/products/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeProductController.java` |
| GET | `/api/v1/backoffice/products` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeProductController.java` |
| POST | `/api/v1/backoffice/products` | `product-service/src/main/java/com/shop/productservice/controller/BackofficeProductController.java` |
| GET | `/api/v1/backoffice/promotions/{id}/usages` | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` |
| DELETE | `/api/v1/backoffice/promotions/{id}` | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` |
| GET | `/api/v1/backoffice/promotions/{id}` | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` |
| PUT | `/api/v1/backoffice/promotions/{id}` | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` |
| GET | `/api/v1/backoffice/promotions` | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` |
| POST | `/api/v1/backoffice/promotions` | `promotion-service/src/main/java/com/shop/promotionservice/controller/BackofficeCampaignController.java` |
| POST | `/api/v1/backoffice/ratings/{id}/hide` | `rating-service/src/main/java/com/shop/ratingservice/controller/BackofficeRatingController.java` |
| POST | `/api/v1/backoffice/ratings/{id}/unhide` | `rating-service/src/main/java/com/shop/ratingservice/controller/BackofficeRatingController.java` |
| POST | `/api/v1/backoffice/search/reindex` | `search-service/src/main/java/com/shop/searchservice/controller/BackofficeSearchController.java` |
| POST | `/api/v1/backoffice/shipments/{id}/fail` | `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` |
| POST | `/api/v1/backoffice/shipments/{id}/retry` | `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` |
| POST | `/api/v1/backoffice/shipments/{id}/tracking` | `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` |
| POST | `/api/v1/backoffice/shipments/{id}/transition` | `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` |
| GET | `/api/v1/backoffice/shipments/{id}` | `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` |
| GET | `/api/v1/backoffice/shipments` | `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` |
| DELETE | `/api/v1/backoffice/tax-classes/{id}` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxClassController.java` |
| GET | `/api/v1/backoffice/tax-classes/{id}` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxClassController.java` |
| PUT | `/api/v1/backoffice/tax-classes/{id}` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxClassController.java` |
| GET | `/api/v1/backoffice/tax-classes` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxClassController.java` |
| POST | `/api/v1/backoffice/tax-classes` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxClassController.java` |
| DELETE | `/api/v1/backoffice/tax-rates/{id}` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxRateController.java` |
| GET | `/api/v1/backoffice/tax-rates/{id}` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxRateController.java` |
| PUT | `/api/v1/backoffice/tax-rates/{id}` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxRateController.java` |
| GET | `/api/v1/backoffice/tax-rates` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxRateController.java` |
| POST | `/api/v1/backoffice/tax-rates` | `tax-service/src/main/java/com/shop/taxservice/controller/BackofficeTaxRateController.java` |
| GET | `/api/v1/brands/{id}` | `product-service/src/main/java/com/shop/productservice/controller/BrandController.java` |
| GET | `/api/v1/brands` | `product-service/src/main/java/com/shop/productservice/controller/BrandController.java` |
| DELETE | `/api/v1/carts/me/items/{cartItemId}` | `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` |
| PUT | `/api/v1/carts/me/items/{cartItemId}` | `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` |
| POST | `/api/v1/carts/me/items` | `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` |
| DELETE | `/api/v1/carts/me` | `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` |
| GET | `/api/v1/carts/me` | `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` |
| GET | `/api/v1/categories/{id}` | `product-service/src/main/java/com/shop/productservice/controller/CategoryController.java` |
| GET | `/api/v1/categories/tree` | `product-service/src/main/java/com/shop/productservice/controller/CategoryController.java` |
| GET | `/api/v1/categories` | `product-service/src/main/java/com/shop/productservice/controller/CategoryController.java` |
| DELETE | `/api/v1/favourites/{favouriteId}` | `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` |
| GET | `/api/v1/favourites/{favouriteId}` | `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` |
| DELETE | `/api/v1/favourites/by-product/{productId}` | `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` |
| GET | `/api/v1/favourites` | `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` |
| POST | `/api/v1/favourites` | `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` |
| POST | `/api/v1/inventory/{productId}/reserve` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| DELETE | `/api/v1/inventory/{productId}` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| GET | `/api/v1/inventory/{productId}` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| PUT | `/api/v1/inventory/{productId}` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| POST | `/api/v1/inventory/reservations/{reservationId}/commit` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| POST | `/api/v1/inventory/reservations/{reservationId}/release-committed` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| POST | `/api/v1/inventory/reservations/{reservationId}/release` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| GET | `/api/v1/inventory/reservations/{reservationId}/state` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| GET | `/api/v1/inventory` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| POST | `/api/v1/inventory` | `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` |
| GET | `/api/v1/medias/{id}` | `media-service/src/main/java/com/shop/mediaservice/controller/MediaPublicController.java` |
| HEAD | `/api/v1/medias` | `media-service/src/main/java/com/shop/mediaservice/controller/MediaPublicController.java` |
| POST | `/api/v1/orders/{orderId}/cancel` | `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` |
| POST | `/api/v1/orders/{orderId}/confirm` | `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java` |
| POST | `/api/v1/orders/{orderId}/deliver` | `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java` |
| POST | `/api/v1/orders/{orderId}/ship` | `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java` |
| GET | `/api/v1/orders/{orderId}` | `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` |
| GET | `/api/v1/orders/me` | `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` |
| GET | `/api/v1/orders/verify-purchase` | `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` |
| GET | `/api/v1/orders` | `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` |
| POST | `/api/v1/orders` | `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` |
| POST | `/api/v1/payments/{id}/capture` | `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java` |
| POST | `/api/v1/payments/{id}/refund` | `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java` |
| GET | `/api/v1/payments` | `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java` |
| POST | `/api/v1/payments` | `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java` |
| GET | `/api/v1/products/{id}` | `product-service/src/main/java/com/shop/productservice/controller/ProductController.java` |
| GET | `/api/v1/products/slug/{slug}` | `product-service/src/main/java/com/shop/productservice/controller/ProductController.java` |
| GET | `/api/v1/products` | `product-service/src/main/java/com/shop/productservice/controller/ProductController.java` |
| POST | `/api/v1/promotions/{code}/reserve` | `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` |
| POST | `/api/v1/promotions/reservations/{reservationId}/commit` | `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` |
| POST | `/api/v1/promotions/reservations/{reservationId}/release-committed` | `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` |
| POST | `/api/v1/promotions/reservations/{reservationId}/release` | `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` |
| GET | `/api/v1/promotions/reservations/{reservationId}/state` | `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` |
| PUT | `/api/v1/ratings/{productId}` | `rating-service/src/main/java/com/shop/ratingservice/controller/StorefrontRatingController.java` |
| GET | `/api/v1/ratings` | `rating-service/src/main/java/com/shop/ratingservice/controller/StorefrontRatingController.java` |
| POST | `/api/v1/ratings` | `rating-service/src/main/java/com/shop/ratingservice/controller/StorefrontRatingController.java` |
| POST | `/api/v1/roles/users/{userId}/assign` | `auth-service/src/main/java/com/shop/authservice/controller/RoleController.java` |
| POST | `/api/v1/roles/users/{userId}/revoke` | `auth-service/src/main/java/com/shop/authservice/controller/RoleController.java` |
| GET | `/api/v1/roles/users/{userId}` | `auth-service/src/main/java/com/shop/authservice/controller/RoleController.java` |
| GET | `/api/v1/search` | `search-service/src/main/java/com/shop/searchservice/controller/SearchController.java` |
| POST | `/api/v1/tax/calculate` | `tax-service/src/main/java/com/shop/taxservice/controller/TaxCalculationController.java` |
| PUT | `/api/v1/users/{id}/restore` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| GET | `/api/v1/users/{id}` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| PUT | `/api/v1/users/me/password` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| DELETE | `/api/v1/users/me` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| GET | `/api/v1/users/me` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| PUT | `/api/v1/users/me` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| GET | `/api/v1/users` | `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` |
| POST | `/api/v1/webhooks/payments/{provider}` | `payment-service/src/main/java/com/shop/paymentservice/webhook/PaymentWebhookController.java` |
| POST | `/api/v1/webhooks/shipping` | `shipping-service/src/main/java/com/shop/shippingservice/webhook/CarrierWebhookController.java` |
| GET | `/internal/products/media-references/{mediaId}` | `product-service/src/main/java/com/shop/productservice/controller/InternalProductMediaController.java` |

## Limitations

- Non-login request bodies use `{}` placeholders; populate DTO-required fields before writes.
- Execution requires Docker services, seeded credentials, valid IDs, databases/brokers, and external provider configuration.
- Internal and webhook endpoints may require service signatures/tokens.
- Direct service URLs are used; gateway rewriting/rate limiting can differ.
