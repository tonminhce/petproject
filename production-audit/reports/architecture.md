# Actual Architecture (reconstructed from source)

Generated: 2026-11-10T10:05:42

## Maven modules (23)

| Module | Artifact | Internal deps | Kind |
|---|---|---|---|
| auth-service | auth-service | 1 | service |
| common-core | common-core | 0 | shared |
| common-kafka | common-kafka | 0 | shared |
| common-keycloak | common-keycloak | 1 | shared |
| common-logging | common-logging | 1 | shared |
| common-security | common-security | 1 | shared |
| common-spring | common-spring | 6 | shared |
| common-storage | common-storage | 0 | shared |
| favourite-service | favourite-service | 1 | service |
| gateway-service | gateway-service | 1 | service |
| inventory-service | inventory-service | 3 | service |
| media-service | media-service | 3 | service |
| notification-service | notification-service | 5 | service |
| order-service | order-service | 3 | service |
| payment-service | payment-service | 7 | service |
| petproject | petproject | 7 | other |
| product-service | product-service | 3 | service |
| promotion-service | promotion-service | 6 | service |
| rating-service | rating-service | 5 | service |
| search-service | search-service | 3 | service |
| shipping-service | shipping-service | 6 | service |
| tax-service | tax-service | 5 | service |
| utils | utils | 0 | shared |

## Internal dependency edges (78)

| From | To |
|---|---|
| auth-service | common-spring |
| common-keycloak | common-core |
| common-logging | common-core |
| common-security | common-core |
| common-spring | common-core |
| common-spring | common-kafka |
| common-spring | common-keycloak |
| common-spring | common-logging |
| common-spring | common-security |
| common-spring | common-storage |
| favourite-service | common-spring |
| gateway-service | common-core |
| inventory-service | common-kafka |
| inventory-service | common-logging |
| inventory-service | common-spring |
| inventory-service | common-spring |
| media-service | common-kafka |
| media-service | common-spring |
| media-service | common-spring |
| media-service | common-storage |
| notification-service | common-core |
| notification-service | common-kafka |
| notification-service | common-keycloak |
| notification-service | common-security |
| notification-service | common-spring |
| notification-service | common-spring |
| order-service | common-kafka |
| order-service | common-logging |
| order-service | common-spring |
| payment-service | common-core |
| payment-service | common-kafka |
| payment-service | common-keycloak |
| payment-service | common-logging |
| payment-service | common-security |
| payment-service | common-spring |
| payment-service | common-spring |
| payment-service | common-storage |
| petproject | common-core |
| petproject | common-kafka |
| petproject | common-keycloak |
| petproject | common-logging |
| petproject | common-security |
| petproject | common-spring |
| petproject | common-storage |
| product-service | common-kafka |
| product-service | common-logging |
| product-service | common-spring |
| product-service | common-spring |
| promotion-service | common-core |
| promotion-service | common-kafka |
| promotion-service | common-keycloak |
| promotion-service | common-logging |
| promotion-service | common-security |
| promotion-service | common-spring |
| promotion-service | common-spring |
| rating-service | common-core |
| rating-service | common-kafka |
| rating-service | common-logging |
| rating-service | common-security |
| rating-service | common-spring |
| rating-service | common-spring |
| search-service | common-kafka |
| search-service | common-logging |
| search-service | common-spring |
| search-service | common-spring |
| shipping-service | common-core |
| shipping-service | common-kafka |
| shipping-service | common-keycloak |
| shipping-service | common-logging |
| shipping-service | common-security |
| shipping-service | common-spring |
| shipping-service | common-spring |
| tax-service | common-core |
| tax-service | common-keycloak |
| tax-service | common-logging |
| tax-service | common-security |
| tax-service | common-spring |
| tax-service | common-spring |

## Kafka topics and consumer groups

| Topic | Consumer groups |
|---|---|
| shop.payment.lifecycle.v1 | order-service-payment-group |

## Service datasource ownership

| application.yml | JDBC URLs |
|---|---|
| auth-service/src/main/resources/application.yml | localhost:5432/authservice} |
| auth-service/target/classes/application.yml | localhost:5432/authservice} |
| favourite-service/src/main/resources/application.yml | localhost:5432/favouriteservice} |
| favourite-service/target/classes/application.yml | localhost:5432/favouriteservice} |
| gateway-service/src/main/resources/application.yml | n/a |
| gateway-service/target/classes/application.yml | n/a |
| inventory-service/src/main/resources/application.yml | localhost:5432/inventoryservice} |
| inventory-service/target/classes/application.yml | localhost:5432/inventoryservice} |
| media-service/src/main/resources/application.yml | localhost:5432/mediaservice} |
| media-service/target/classes/application.yml | localhost:5432/mediaservice} |
| notification-service/src/main/resources/application.yml | localhost:5432/notificationservice} |
| notification-service/target/classes/application.yml | localhost:5432/notificationservice} |
| order-service/src/main/resources/application.yml | localhost:5432/orderservice} |
| order-service/target/classes/application.yml | localhost:5432/orderservice} |
| payment-service/src/main/resources/application.yml | localhost:5432/paymentservice} |
| payment-service/target/classes/application.yml | localhost:5432/paymentservice} |
| product-service/src/main/resources/application.yml | localhost:5432/productservice} |
| product-service/target/classes/application.yml | localhost:5432/productservice} |
| promotion-service/src/main/resources/application.yml | localhost:5432/promotionservice} |
| promotion-service/target/classes/application.yml | localhost:5432/promotionservice} |
| rating-service/src/main/resources/application.yml | localhost:5432/ratingservice} |
| rating-service/target/classes/application.yml | localhost:5432/ratingservice} |
| search-service/src/main/resources/application.yml | n/a |
| search-service/target/classes/application.yml | n/a |
| shipping-service/src/main/resources/application.yml | localhost:5432/shippingservice} |
| shipping-service/target/classes/application.yml | localhost:5432/shippingservice} |
| tax-service/src/main/resources/application.yml | localhost:5432/taxservice} |
| tax-service/target/classes/application.yml | localhost:5432/taxservice} |
| utils/common-spring/src/main/resources/application.yml | n/a |
| utils/common-spring/target/classes/application.yml | n/a |
