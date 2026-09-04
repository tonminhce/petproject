#!/usr/bin/env python3
"""
Architecture Diagram Generator for Petproject Microservices Platform
Generates 5 comprehensive architecture diagrams in both Draw.io XML and Mermaid (.mmd) formats:
1. System Landscape & High-Level Architecture (C4 Context & Container)
2. Asynchronous Event-Driven Messaging & Transactional Outbox Architecture
3. End-to-End Order Fulfillment Saga Lifecycle (Sequence Flow)
4. Edge Security, Gateway Filter Chain & Dual-Layer Rate Limiting
5. Data Architecture, Storage & Deployment Topology
"""

import os
import xml.sax.saxutils as saxutils

OUTPUT_DIR = "docs/architecture"
IMAGES_DIR = "docs/images"

os.makedirs(OUTPUT_DIR, exist_ok=True)
os.makedirs(IMAGES_DIR, exist_ok=True)

def esc(text):
    return saxutils.escape(str(text), {'"': "&quot;", "'": "&apos;"}).replace("\n", "&#xa;")

# -------------------------------------------------------------------------
# DIAGRAM 1: SYSTEM LANDSCAPE & HIGH-LEVEL ARCHITECTURE (.mmd & .drawio)
# -------------------------------------------------------------------------

def generate_landscape_mermaid():
    mmd = """%% System Landscape Architecture — Petproject Microservices Platform
flowchart TB
    %% Styling Classes
    classDef clientClass fill:#dae8fc,stroke:#6c8ebf,stroke-width:2px,color:#000000;
    classDef gatewayClass fill:#ffe6cc,stroke:#d79b00,stroke-width:2px,color:#000000;
    classDef authClass fill:#e1d5e7,stroke:#9673a6,stroke-width:2px,color:#000000;
    classDef coreServiceClass fill:#d5e8d4,stroke:#82b366,stroke-width:2px,color:#000000;
    classDef cxServiceClass fill:#b1ddf0,stroke:#10739e,stroke-width:2px,color:#000000;
    classDef pricingServiceClass fill:#fad7ac,stroke:#b46504,stroke-width:2px,color:#000000;
    classDef fulfillServiceClass fill:#d0cee2,stroke:#56517e,stroke-width:2px,color:#000000;
    classDef busClass fill:#fff2cc,stroke:#d6b656,stroke-width:2px,color:#000000;
    classDef storeClass fill:#f5f5f5,stroke:#666666,stroke-width:2px,color:#000000;
    classDef extClass fill:#e6e6e6,stroke:#808080,stroke-dasharray: 5 5,color:#000000;

    subgraph CLIENTS ["Client Layer & External Actors"]
        WEB["Customer Web / Mobile App"]:::clientClass
        ADMIN_UI["Backoffice Admin UI"]:::clientClass
        STRIPE_EXT["Stripe Payment Gateway"]:::extClass
        CARRIER_EXT["Logistics Carrier Webhook (DHL/FedEx)"]:::extClass
    end

    subgraph EDGE ["Edge & Gateway Tier"]
        GW["Spring Cloud Gateway :8080<br/>• Dual-Layer Rate Limiting (Redis)<br/>• OAuth2 JWT Validation<br/>• X-Correlation-Id & Traceparent<br/>• Dynamic CORS & Error Enveloping"]:::gatewayClass
    end

    subgraph IDENTITY ["Identity & Access Tier"]
        KEYCLOAK["Keycloak 26 (IAM :8080/:8180)<br/>Realm: ecommerce<br/>OAuth2.0 / OIDC / JWKS"]:::authClass
        AUTH_SVC["auth-service :8088<br/>ROPC Login, Refresh, User & Role Facade"]:::authClass
    end

    subgraph CORE_COMMERCE ["Core Commerce Domain"]
        PRODUCT_SVC["product-service :8086<br/>Catalog, Brands, Categories, Variants<br/>Redis Cache + Outbox Relay"]:::coreServiceClass
        INVENTORY_SVC["inventory-service :8082<br/>Stock Reservations & Commit / Release"]:::coreServiceClass
        ORDER_SVC["order-service :8084<br/>Carts, Order Checkout Saga & Outbox Relay"]:::coreServiceClass
    end

    subgraph CUSTOMER_EXP ["Customer Experience Domain"]
        SEARCH_SVC["search-service :8094<br/>Elasticsearch 8.15 Full-Text & Auto-suggest"]:::cxServiceClass
        RATING_SVC["rating-service :8089<br/>Storefront Reviews & Verified Purchase Gate"]:::cxServiceClass
        FAV_SVC["favourite-service :8081<br/>Customer Wishlists & Saved Items"]:::cxServiceClass
    end

    subgraph PRICING_MEDIA ["Pricing, Tax & Media Domain"]
        TAX_SVC["tax-service :8091<br/>Tax Classes & Geo-Rates Calculator"]:::pricingServiceClass
        PROMO_SVC["promotion-service :8093<br/>Coupons, Campaigns & Discount Calculator"]:::pricingServiceClass
        MEDIA_SVC["media-service :8083<br/>MIME Verification & 6 WebP Variants"]:::pricingServiceClass
    end

    subgraph FULFILLMENT ["Fulfillment & Communications Domain"]
        PAYMENT_SVC["payment-service :8085<br/>Payment Capture, Refund, Stripe Client"]:::fulfillServiceClass
        SHIPPING_SVC["shipping-service :8087<br/>Shipment Tracking, Carrier Webhooks"]:::fulfillServiceClass
        NOTIF_SVC["notification-service :8090<br/>In-App Alerts & SMTP Email Dispatch"]:::fulfillServiceClass
    end

    subgraph BUS ["Event Streaming Backbone"]
        KAFKA["Apache Kafka 3.9 (KRaft Cluster :9092)<br/>Topics: shop.order.lifecycle.v1, shop.product.lifecycle.v1, shop.inventory.events.v1,<br/>shop.payment.events.v1, shop.shipping.events.v1, shop.rating.events.v1"]:::busClass
    end

    subgraph DATA_TIER ["Persistence & Distributed Storage"]
        PG[("PostgreSQL 16 (:5432)<br/>12 Isolated Service DBs + Keycloak DB")]:::storeClass
        REDIS[("Redis 7.4 (:6379)<br/>Token Buckets, Product/Taxonomy Cache")]:::storeClass
        ES[("Elasticsearch 8.15 (:9200)<br/>products & ratings indices")]:::storeClass
        RUSTFS[("RustFS S3 (:9000/:9001)<br/>ecommerce-media bucket")]:::storeClass
    end

    %% Client traffic
    WEB & ADMIN_UI -->|"HTTP /api/v1/*"| GW
    CARRIER_EXT -->|"Webhook POST"| GW
    STRIPE_EXT -->|"Webhook POST"| GW

    %% Edge to IAM
    GW -.->|"JWKS Verification"| KEYCLOAK
    GW -->|"Forward /api/v1/auth/*"| AUTH_SVC
    AUTH_SVC <-->|"REST Admin API"| KEYCLOAK

    %% Edge to Services
    GW --> PRODUCT_SVC & INVENTORY_SVC & ORDER_SVC
    GW --> SEARCH_SVC & RATING_SVC & FAV_SVC
    GW --> TAX_SVC & PROMO_SVC & MEDIA_SVC
    GW --> PAYMENT_SVC & SHIPPING_SVC & NOTIF_SVC

    %% Synchronous Orchestration (REST Feign / RestClient)
    ORDER_SVC -->|"REST Product Verification"| PRODUCT_SVC
    ORDER_SVC -->|"REST /reserve & /commit"| INVENTORY_SVC
    ORDER_SVC -->|"REST /calculate"| TAX_SVC
    ORDER_SVC -->|"REST /apply"| PROMO_SVC
    ORDER_SVC -->|"REST Verify Captured"| PAYMENT_SVC
    RATING_SVC -->|"REST /verify-purchase (RTG-11001)"| ORDER_SVC
    MEDIA_SVC <-->|"REST Verify Media Reference"| PRODUCT_SVC

    %% Asynchronous Event Flows
    PRODUCT_SVC & INVENTORY_SVC & ORDER_SVC -->|"Transactional Outbox"| KAFKA
    PAYMENT_SVC & SHIPPING_SVC & RATING_SVC -->|"Transactional Outbox"| KAFKA
    KAFKA -->|"Consume Events"| SEARCH_SVC
    KAFKA -->|"Consume Events"| SHIPPING_SVC
    KAFKA -->|"Consume Events"| ORDER_SVC
    KAFKA -->|"Consume Events"| NOTIF_SVC
    KAFKA -->|"Consume Events"| PRODUCT_SVC

    %% Storage connections
    AUTH_SVC & PRODUCT_SVC & INVENTORY_SVC & ORDER_SVC & PAYMENT_SVC & SHIPPING_SVC & FAV_SVC & RATING_SVC & TAX_SVC & PROMO_SVC & NOTIF_SVC & MEDIA_SVC --> PG
    GW & PRODUCT_SVC <--> REDIS
    SEARCH_SVC <--> ES
    MEDIA_SVC <--> RUSTFS
"""
    with open(f"{OUTPUT_DIR}/system-landscape-architecture.mmd", "w") as f:
        f.write(mmd.strip())
    print("Generated system-landscape-architecture.mmd")

def generate_landscape_drawio():
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<mxfile host="Electron" version="26.0.0">
  <diagram id="landscape" name="System-Landscape">
    <mxGraphModel dx="2400" dy="1800" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2400" pageHeight="1800">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />

        <!-- Title Banner -->
        <mxCell id="title" value="Petproject Microservices Platform — System Landscape Architecture" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=24;fontStyle=1;fontColor=#1a365d;" vertex="1" parent="1">
          <mxGeometry x="600" y="20" width="1200" height="40" as="geometry" />
        </mxCell>
        <mxCell id="subtitle" value="Production-Grade Spring Boot 4.1.1 + Java 25 | 14 Microservices, Keycloak 26, Redis 7.4, Kafka 3.9 KRaft, Postgres 16, Elasticsearch 8.15, RustFS S3" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=12;fontColor=#4a5568;" vertex="1" parent="1">
          <mxGeometry x="600" y="60" width="1200" height="20" as="geometry" />
        </mxCell>

        <!-- Container: Client Layer -->
        <mxCell id="c_clients" value="Client Layer &amp; External Integrations" style="swimlane;startSize=30;fillColor=#f8f9fa;strokeColor=#6c757d;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="100" y="100" width="2200" height="110" as="geometry" />
        </mxCell>
        <mxCell id="cli_web" value="&lt;b&gt;Storefront Web / Mobile&lt;/b&gt;&lt;br/&gt;Vue / React / Mobile App&lt;br/&gt;(PKCE Authorization Code Flow)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="c_clients">
          <mxGeometry x="60" y="40" width="220" height="55" as="geometry" />
        </mxCell>
        <mxCell id="cli_admin" value="&lt;b&gt;Backoffice Admin Console&lt;/b&gt;&lt;br/&gt;Catalog &amp; Operations Portal&lt;br/&gt;(Role: ADMIN / MANAGER)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="c_clients">
          <mxGeometry x="340" y="40" width="220" height="55" as="geometry" />
        </mxCell>
        <mxCell id="cli_swagger" value="&lt;b&gt;Swagger UI / OpenAPI 3.1&lt;/b&gt;&lt;br/&gt;API Developer Portal&lt;br/&gt;(:8080/swagger-ui.html)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="c_clients">
          <mxGeometry x="620" y="40" width="200" height="55" as="geometry" />
        </mxCell>
        <mxCell id="ext_stripe" value="&lt;b&gt;Stripe Payment Gateway&lt;/b&gt;&lt;br/&gt;Checkout, Webhook, Refunds&lt;br/&gt;(External Vendor)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=11;strokeDasharray=3 3;" vertex="1" parent="c_clients">
          <mxGeometry x="1380" y="40" width="220" height="55" as="geometry" />
        </mxCell>
        <mxCell id="ext_carrier" value="&lt;b&gt;Logistics Carrier Webhook&lt;/b&gt;&lt;br/&gt;DHL / FedEx / VNPost&lt;br/&gt;(Dispatched, Delivered updates)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fad7ac;strokeColor=#b46504;fontSize=11;strokeDasharray=3 3;" vertex="1" parent="c_clients">
          <mxGeometry x="1660" y="40" width="220" height="55" as="geometry" />
        </mxCell>
        <mxCell id="ext_smtp" value="&lt;b&gt;External SMTP Relay&lt;/b&gt;&lt;br/&gt;Gmail SMTP / AWS SES&lt;br/&gt;(Notification Delivery)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;fontSize=11;strokeDasharray=3 3;" vertex="1" parent="c_clients">
          <mxGeometry x="1940" y="40" width="200" height="55" as="geometry" />
        </mxCell>

        <!-- Container: Edge & Identity Tier -->
        <mxCell id="c_edge" value="Edge &amp; Identity Tier" style="swimlane;startSize=30;fillColor=#fff2cc;strokeColor=#d6b656;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="100" y="240" width="2200" height="150" as="geometry" />
        </mxCell>
        <mxCell id="gw" value="&lt;b&gt;Spring Cloud Gateway (:8080)&lt;/b&gt;&lt;br/&gt;• Reactive Netty Reverse Proxy (No-rewrite full path /api/v1/*)&lt;br/&gt;• Security: OAuth2 Resource Server &amp; JWT Verification Filter&lt;br/&gt;• Two-Layer Token Bucket Limiter: Global (2k req/s) &amp; Per-Route (100 req/s)&lt;br/&gt;• MDC Correlation: X-Correlation-Id &amp; W3C Traceparent Header Propagation" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;align=left;spacingLeft=15;" vertex="1" parent="c_edge">
          <mxGeometry x="40" y="40" width="680" height="90" as="geometry" />
        </mxCell>
        <mxCell id="redis_gw" value="&lt;b&gt;Redis 7.4 Cluster (:6379)&lt;/b&gt;&lt;br/&gt;• Atomic Lua Token Bucket&lt;br/&gt;• Global System Bucket&lt;br/&gt;• Route × (User | IP) Buckets" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=11;" vertex="1" parent="c_edge">
          <mxGeometry x="780" y="40" width="220" height="90" as="geometry" />
        </mxCell>
        <mxCell id="keycloak" value="&lt;b&gt;Keycloak 26 IAM (:8080 int / :8180 host)&lt;/b&gt;&lt;br/&gt;• Realm: ecommerce&lt;br/&gt;• Clients: ecommerce-client, swagger-ui, service-accounts&lt;br/&gt;• Roles: ADMIN, USER, SERVICE&lt;br/&gt;• JWKS Endpoint: /protocol/openid-connect/certs" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=11;align=left;spacingLeft=15;" vertex="1" parent="c_edge">
          <mxGeometry x="1060" y="40" width="400" height="90" as="geometry" />
        </mxCell>
        <mxCell id="auth_svc" value="&lt;b&gt;auth-service (:8088)&lt;/b&gt;&lt;br/&gt;• ROPC Login &amp; Token Refresh Facade&lt;br/&gt;• Keycloak Admin REST Client&lt;br/&gt;• User Profile &amp; Role Management&lt;br/&gt;• Shadow DB Mirror (users, roles)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=11;align=left;spacingLeft=15;" vertex="1" parent="c_edge">
          <mxGeometry x="1520" y="40" width="300" height="90" as="geometry" />
        </mxCell>
        <mxCell id="common_sec" value="&lt;b&gt;utils / common-security&lt;/b&gt;&lt;br/&gt;common-spring &amp; common-core&lt;br/&gt;Auto-config, JWT Decoder, CORS" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e2e3e5;strokeColor=#6c757d;fontSize=10;" vertex="1" parent="c_edge">
          <mxGeometry x="1880" y="45" width="260" height="80" as="geometry" />
        </mxCell>

        <!-- Container: 13 Business Microservices -->
        <mxCell id="c_services" value="Core Microservices Layer (Java 25 + Spring Boot 4.1.1 + Liquibase + Docker Compose)" style="swimlane;startSize=30;fillColor=#f8f9fa;strokeColor=#6c757d;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="100" y="420" width="2200" height="520" as="geometry" />
        </mxCell>

        <!-- Row 1: Core Commerce -->
        <mxCell id="svc_product" value="&lt;b&gt;product-service (:8086)&lt;/b&gt;&lt;br/&gt;• Catalog, Categories, Brands, Variants&lt;br/&gt;• Redis Cache (@Cacheable)&lt;br/&gt;• Outbox: shop.product.lifecycle.v1&lt;br/&gt;• Consumer: Rating &amp; Media Lifecycle" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="40" y="50" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_inventory" value="&lt;b&gt;inventory-service (:8082)&lt;/b&gt;&lt;br/&gt;• Stock Management &amp; Reservations&lt;br/&gt;• Reserve / Commit / Release APIs&lt;br/&gt;• Scheduled Auto-Release Sweeper&lt;br/&gt;• Outbox: shop.inventory.events.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="390" y="50" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_order" value="&lt;b&gt;order-service (:8084)&lt;/b&gt;&lt;br/&gt;• Cart &amp; Order Lifecycle Coordinator&lt;br/&gt;• RestClients: Inventory, Promo, Tax, Pay&lt;br/&gt;• Outbox: shop.order.lifecycle.v1&lt;br/&gt;• Consumer: ShippingDeliveredConsumer" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="740" y="50" width="330" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_payment" value="&lt;b&gt;payment-service (:8085)&lt;/b&gt;&lt;br/&gt;• Payment Creation, Capture, Refund&lt;br/&gt;• Stripe SDK v24 Integration&lt;br/&gt;• Idempotency-Key Enforcement&lt;br/&gt;• Outbox: shop.payment.events.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d0cee2;strokeColor=#56517e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="1110" y="50" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_shipping" value="&lt;b&gt;shipping-service (:8087)&lt;/b&gt;&lt;br/&gt;• Shipment Creation &amp; Tracking Codes&lt;br/&gt;• Carrier Webhook Ingestion&lt;br/&gt;• Consumer: OrderEventConsumer&lt;br/&gt;• Outbox: shop.shipping.events.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d0cee2;strokeColor=#56517e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="1460" y="50" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_notif" value="&lt;b&gt;notification-service (:8090)&lt;/b&gt;&lt;br/&gt;• Notification History &amp; Unread Counts&lt;br/&gt;• SMTP Email Delivery &amp; Retry Table&lt;br/&gt;• Consumer: OrderEventConsumer&lt;br/&gt;• Consumer: PaymentSuccess/Failed" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d0cee2;strokeColor=#56517e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="1810" y="50" width="330" height="100" as="geometry" />
        </mxCell>

        <!-- Row 2: Customer Experience & Pricing -->
        <mxCell id="svc_search" value="&lt;b&gt;search-service (:8094)&lt;/b&gt;&lt;br/&gt;• Elasticsearch 8.15 Indexer &amp; Query&lt;br/&gt;• Autocomplete, Suggest &amp; BM25 Full-text&lt;br/&gt;• Consumer: Product &amp; Rating Events&lt;br/&gt;• Backoffice Manual Reindex API" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#b1ddf0;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="40" y="190" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_rating" value="&lt;b&gt;rating-service (:8089)&lt;/b&gt;&lt;br/&gt;• Storefront Reviews (1-5 Stars)&lt;br/&gt;• Verified Purchase Gate (RTG-11001)&lt;br/&gt;• Backoffice Review Approval Workflow&lt;br/&gt;• Outbox: shop.rating.events.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#b1ddf0;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="390" y="190" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_favourite" value="&lt;b&gt;favourite-service (:8081)&lt;/b&gt;&lt;br/&gt;• Customer Wishlists (Add/Remove)&lt;br/&gt;• Per-User Product Favorites List&lt;br/&gt;• Soft-Delete Cleanups" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#b1ddf0;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="740" y="190" width="330" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_tax" value="&lt;b&gt;tax-service (:8091)&lt;/b&gt;&lt;br/&gt;• Tax Classes (Standard, Reduced, Zero)&lt;br/&gt;• Geo-specific Tax Rates (Country, State)&lt;br/&gt;• Real-time Tax Calculation API" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fad7ac;strokeColor=#b46504;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="1110" y="190" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_promo" value="&lt;b&gt;promotion-service (:8093)&lt;/b&gt;&lt;br/&gt;• Discount Campaigns &amp; Coupon Codes&lt;br/&gt;• PERCENTAGE &amp; FIXED_AMOUNT rules&lt;br/&gt;• Coupon Reservation &amp; Commit Usage&lt;br/&gt;• Outbox: shop.promotion.lifecycle.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fad7ac;strokeColor=#b46504;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="1460" y="190" width="310" height="100" as="geometry" />
        </mxCell>
        <mxCell id="svc_media" value="&lt;b&gt;media-service (:8083)&lt;/b&gt;&lt;br/&gt;• Multipart Upload with Magic-Byte MIME&lt;br/&gt;• Auto-Generates 6 WebP Variants&lt;br/&gt;• RustFS S3 SDK Storage &amp; Presigned URLs&lt;br/&gt;• Outbox: shop.media.lifecycle.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fad7ac;strokeColor=#b46504;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_services">
          <mxGeometry x="1810" y="190" width="330" height="100" as="geometry" />
        </mxCell>

        <!-- Sub-box: Shared Platform Libraries (utils/) -->
        <mxCell id="sub_utils" value="&lt;b&gt;Shared Platform Modules (utils/)&lt;/b&gt; — common-spring (Meta-Starter) transitively provides: common-core (ApiResponse, ErrorCode), common-security (JWT/OAuth2), common-logging (@LogPerformance, MDC), common-keycloak (Admin/Token Client), common-kafka (Transactional Outbox, BaseKafkaConsumer), common-storage (S3 SDK)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e2e3e5;strokeColor=#495057;fontSize=11;align=center;" vertex="1" parent="c_services">
          <mxGeometry x="40" y="320" width="2100" height="40" as="geometry" />
        </mxCell>

        <!-- Synchronous REST Links between services -->
        <mxCell id="e_ord_prd" value="Verify SKU/Price" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#2b6cb0;strokeColor=#2b6cb0;strokeWidth=2;exitX=0;exitY=0.5;entryX=1;entryY=0.5;" edge="1" parent="c_services" source="svc_order" target="svc_inventory">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e_ord_inv" value="Reserve / Commit" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#2b6cb0;strokeColor=#2b6cb0;strokeWidth=2;exitX=0;exitY=0.5;entryX=1;entryY=0.5;" edge="1" parent="c_services" source="svc_inventory" target="svc_product">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e_ord_pay" value="Verify Captured" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#2b6cb0;strokeColor=#2b6cb0;strokeWidth=2;exitX=1;exitY=0.5;entryX=0;entryY=0.5;" edge="1" parent="c_services" source="svc_order" target="svc_payment">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e_ord_tax" value="Calculate Tax" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#2b6cb0;strokeColor=#2b6cb0;strokeWidth=2;exitX=0.75;exitY=1;entryX=0.25;entryY=0;" edge="1" parent="c_services" source="svc_order" target="svc_tax">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e_ord_prm" value="Apply Coupon" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#2b6cb0;strokeColor=#2b6cb0;strokeWidth=2;exitX=0.9;exitY=1;entryX=0.2;entryY=0;" edge="1" parent="c_services" source="svc_order" target="svc_promo">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e_rtg_ord" value="Verify Purchase (RTG-11001)" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#9c4221;strokeColor=#9c4221;strokeWidth=2;exitX=0.75;exitY=0;entryX=0.25;entryY=1;" edge="1" parent="c_services" source="svc_rating" target="svc_order">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e_med_prd" value="Verify References" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;fontColor=#2b6cb0;strokeColor=#2b6cb0;strokeWidth=2;exitX=0;exitY=0.5;entryX=1;entryY=0.5;" edge="1" parent="c_services" source="svc_media" target="svc_product">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

        <!-- Container: Event Streaming Backbone -->
        <mxCell id="c_kafka" value="Event Streaming Backbone (Apache Kafka 3.9 KRaft Mode :9092 | Cluster ID: MkU3OEVBNTcwNTJENDM2Qk)" style="swimlane;startSize=30;fillColor=#fff2cc;strokeColor=#d6b656;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="100" y="970" width="2200" height="130" as="geometry" />
        </mxCell>
        <mxCell id="k_top1" value="&lt;b&gt;shop.order.lifecycle.v1&lt;/b&gt;&lt;br/&gt;order.created.v1&lt;br/&gt;order.updated.v1&lt;br/&gt;order.cancelled.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="40" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_top2" value="&lt;b&gt;shop.product.lifecycle.v1&lt;/b&gt;&lt;br/&gt;ProductCreated&lt;br/&gt;ProductUpdated&lt;br/&gt;ProductDeleted" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="290" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_top3" value="&lt;b&gt;shop.inventory.events.v1&lt;/b&gt;&lt;br/&gt;inventory.reserved.v1&lt;br/&gt;inventory.committed.v1&lt;br/&gt;inventory.released.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="540" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_top4" value="&lt;b&gt;shop.payment.events.v1&lt;/b&gt;&lt;br/&gt;payment.succeeded.v1&lt;br/&gt;payment.failed.v1&lt;br/&gt;payment.refunded.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="790" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_top5" value="&lt;b&gt;shop.shipping.events.v1&lt;/b&gt;&lt;br/&gt;shipping.dispatched.v1&lt;br/&gt;shipping.delivered.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="1040" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_top6" value="&lt;b&gt;shop.rating.events.v1&lt;/b&gt;&lt;br/&gt;RatingCreated / Updated&lt;br/&gt;RatingApproved / Rejected" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="1290" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_top7" value="&lt;b&gt;Taxonomy / Media Topics&lt;/b&gt;&lt;br/&gt;shop.category.lifecycle.v1&lt;br/&gt;shop.brand.lifecycle.v1&lt;br/&gt;shop.media.lifecycle.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="1540" y="40" width="220" height="70" as="geometry" />
        </mxCell>
        <mxCell id="k_pat" value="&lt;b&gt;Transactional Outbox Pattern&lt;/b&gt;&lt;br/&gt;• DB writes outbox_events in same TX&lt;br/&gt;• @Scheduled Relay polls every 5s&lt;br/&gt;• CloudEvents Envelope &amp; Partition Key" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=10;" vertex="1" parent="c_kafka">
          <mxGeometry x="1790" y="40" width="370" height="70" as="geometry" />
        </mxCell>

        <!-- Container: Persistence & Infrastructure Tier -->
        <mxCell id="c_infra" value="Distributed Data &amp; Infrastructure Tier" style="swimlane;startSize=30;fillColor=#e9ecef;strokeColor=#495057;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="100" y="1130" width="2200" height="150" as="geometry" />
        </mxCell>
        <mxCell id="db_pg" value="&lt;b&gt;PostgreSQL 16 (:5432)&lt;/b&gt;&lt;br/&gt;max_connections=300 | 12 Isolated DBs:&lt;br/&gt;authservice, productservice, orderservice, paymentservice,&lt;br/&gt;shippingservice, inventoryservice, favouriteservice, ratingservice,&lt;br/&gt;mediaservice, taxservice, promotionservice, notificationservice + keycloak" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=15;" vertex="1" parent="c_infra">
          <mxGeometry x="40" y="40" width="700" height="90" as="geometry" />
        </mxCell>
        <mxCell id="db_redis" value="&lt;b&gt;Redis 7.4 (:6379)&lt;/b&gt;&lt;br/&gt;• Token Bucket Lua&lt;br/&gt;• product::{id} (10m TTL)&lt;br/&gt;• category/brand (30m)&lt;br/&gt;• Cart &amp; SSO Tickets" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=11;" vertex="1" parent="c_infra">
          <mxGeometry x="780" y="40" width="250" height="90" as="geometry" />
        </mxCell>
        <mxCell id="db_es" value="&lt;b&gt;Elasticsearch 8.15 (:9200)&lt;/b&gt;&lt;br/&gt;• Index: products (BM25, text, facets)&lt;br/&gt;• Index: ratings (comments, scores)&lt;br/&gt;• Autocomplete completion suggester" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#b1ddf0;strokeColor=#10739e;fontSize=11;" vertex="1" parent="c_infra">
          <mxGeometry x="1070" y="40" width="310" height="90" as="geometry" />
        </mxCell>
        <mxCell id="db_rustfs" value="&lt;b&gt;RustFS S3 (:9000/:9001)&lt;/b&gt;&lt;br/&gt;• Bucket: ecommerce-media&lt;br/&gt;• S3 API v2 Compatible&lt;br/&gt;• WebP 6 Responsive Resolutions" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#fad7ac;strokeColor=#b46504;fontSize=11;" vertex="1" parent="c_infra">
          <mxGeometry x="1420" y="40" width="290" height="90" as="geometry" />
        </mxCell>
        <mxCell id="infra_obs" value="&lt;b&gt;Observability &amp; Metrics&lt;/b&gt;&lt;br/&gt;• Micrometer + Prometheus Actuator&lt;br/&gt;• /actuator/health (Liveness/Readiness)&lt;br/&gt;• @LogPerformance AOP Threshold" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e2e3e5;strokeColor=#6c757d;fontSize=11;" vertex="1" parent="c_infra">
          <mxGeometry x="1750" y="40" width="390" height="90" as="geometry" />
        </mxCell>

        <!-- Inter-tier Edges -->
        <!-- Clients to Gateway -->
        <mxCell id="e1" value="HTTPS /api/v1/*" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=11;strokeWidth=2;strokeColor=#4a5568;" edge="1" parent="1" source="cli_web" target="gw">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e2" value="Admin REST" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=11;strokeWidth=2;strokeColor=#4a5568;" edge="1" parent="1" source="cli_admin" target="gw">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

        <!-- Gateway to Keycloak & Redis -->
        <mxCell id="e3" value="JWT JWKS" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;dashed=1;strokeColor=#9673a6;strokeWidth=2;" edge="1" parent="1" source="gw" target="keycloak">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e4" value="Atomic Rate Limit" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;strokeColor=#b85450;strokeWidth=2;" edge="1" parent="1" source="gw" target="redis_gw">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

        <!-- Gateway to Services (Bus forwarding) -->
        <mxCell id="e5" value="Reverse Proxy Forward (/api/v1/*)" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=12;fontStyle=1;strokeColor=#d79b00;strokeWidth=3;" edge="1" parent="1" source="gw" target="c_services">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

        <!-- Services to Kafka -->
        <mxCell id="e6" value="Publish Outbox Events" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=11;fontStyle=1;strokeColor=#d79b00;strokeWidth=2;" edge="1" parent="1" source="c_services" target="c_kafka">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

        <!-- Services to Persistence -->
        <mxCell id="e7" value="JPA / JDBC Connection" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=11;strokeColor=#82b366;strokeWidth=2;" edge="1" parent="1" source="c_services" target="db_pg">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e8" value="Elasticsearch REST Client" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;strokeColor=#10739e;strokeWidth=2;" edge="1" parent="1" source="svc_search" target="db_es">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="e9" value="S3 SDK v2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;fontSize=10;strokeColor=#b46504;strokeWidth=2;" edge="1" parent="1" source="svc_media" target="db_rustfs">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
"""
    with open(f"{OUTPUT_DIR}/system-landscape-architecture.drawio", "w") as f:
        f.write(xml.strip())
    print("Generated system-landscape-architecture.drawio")

# -------------------------------------------------------------------------
# DIAGRAM 2: ASYNCHRONOUS EVENT-DRIVEN & TRANSACTIONAL OUTBOX ARCHITECTURE
# -------------------------------------------------------------------------

def generate_event_driven_mermaid():
    mmd = """%% Event-Driven Architecture & Transactional Outbox Pattern
flowchart TB
    classDef pubClass fill:#d5e8d4,stroke:#82b366,stroke-width:2px,color:#000000;
    classDef topicClass fill:#ffe6cc,stroke:#d79b00,stroke-width:2px,color:#000000;
    classDef subClass fill:#b1ddf0,stroke:#10739e,stroke-width:2px,color:#000000;
    classDef outboxClass fill:#fff2cc,stroke:#d6b656,stroke-width:1px,stroke-dasharray: 4 4,color:#000000;

    subgraph PRODUCERS ["Event Producers & Transactional Outbox"]
        subgraph P1 ["product-service (:8086)"]
            PRD_TX["Business TX<br/>(Create/Update/Delete)"]
            PRD_BOX[("outbox_events table")]:::outboxClass
            PRD_RELAY["OutboxRelay (@Scheduled 5s)"]
            PRD_TX -->|"Same ACID TX"| PRD_BOX
            PRD_BOX -->|"Poll PENDING"| PRD_RELAY
        end

        subgraph P2 ["order-service (:8084)"]
            ORD_TX["Order TX<br/>(Create/Confirm/Cancel)"]
            ORD_BOX[("outbox_events table")]:::outboxClass
            ORD_RELAY["OrderOutboxRelay"]
            ORD_TX -->|"Same ACID TX"| ORD_BOX
            ORD_BOX -->|"Poll PENDING"| ORD_RELAY
        end

        subgraph P3 ["inventory-service (:8082)"]
            INV_TX["Inventory TX<br/>(Reserve/Commit/Adjust)"]
            INV_BOX[("outbox_events table")]:::outboxClass
            INV_RELAY["InventoryOutboxRelay"]
            INV_TX -->|"Same ACID TX"| INV_BOX
            INV_BOX -->|"Poll PENDING"| INV_RELAY
        end

        subgraph P4 ["payment-service (:8085)"]
            PAY_TX["Payment TX<br/>(Succeeded/Failed/Refund)"]
            PAY_BOX[("outbox_events table")]:::outboxClass
            PAY_RELAY["PaymentOutboxRelay"]
            PAY_TX -->|"Same ACID TX"| PAY_BOX
            PAY_BOX -->|"Poll PENDING"| PAY_RELAY
        end

        subgraph P5 ["shipping-service (:8087)"]
            SHP_TX["Shipping TX<br/>(Dispatched/Delivered)"]
            SHP_BOX[("outbox_events table")]:::outboxClass
            SHP_RELAY["ShippingOutboxRelay"]
            SHP_TX -->|"Same ACID TX"| SHP_BOX
            SHP_BOX -->|"Poll PENDING"| SHP_RELAY
        end

        subgraph P6 ["rating-service (:8089)"]
            RTG_TX["Rating TX<br/>(Created/Approved)"]
            RTG_BOX[("outbox_events table")]:::outboxClass
            RTG_RELAY["RatingOutboxRelay"]
            RTG_TX -->|"Same ACID TX"| RTG_BOX
            RTG_BOX -->|"Poll PENDING"| RTG_RELAY
        end
    end

    subgraph KAFKA_BUS ["Apache Kafka 3.9 KRaft Topics"]
        T_PRD["shop.product.lifecycle.v1<br/>• ProductCreated<br/>• ProductUpdated<br/>• ProductDeleted"]:::topicClass
        T_ORD["shop.order.lifecycle.v1<br/>• order.created.v1<br/>• order.updated.v1<br/>• order.cancelled.v1"]:::topicClass
        T_INV["shop.inventory.events.v1<br/>• inventory.reserved.v1<br/>• inventory.committed.v1<br/>• inventory.released.v1"]:::topicClass
        T_PAY["shop.payment.events.v1<br/>• payment.succeeded.v1<br/>• payment.failed.v1<br/>• payment.refunded.v1"]:::topicClass
        T_SHP["shop.shipping.events.v1<br/>• shipping.dispatched.v1<br/>• shipping.delivered.v1"]:::topicClass
        T_RTG["shop.rating.events.v1<br/>• RatingCreated<br/>• RatingApproved"]:::topicClass
    end

    subgraph CONSUMERS ["Kafka Consumers & Business Reactions"]
        subgraph C_SEARCH ["search-service (:8094)"]
            C_SEARCH_LST["ProductSearchConsumer<br/>Elasticsearch Upsert / Delete ProductDoc"]:::subClass
        end

        subgraph C_SHIPPING ["shipping-service (:8087)"]
            C_SHP_LST["OrderEventConsumer<br/>Create Shipment on Order Confirmed"]:::subClass
        end

        subgraph C_ORDER ["order-service (:8084)"]
            C_ORD_LST["ShippingDeliveredConsumer<br/>Auto-Transition Order to DELIVERED"]:::subClass
        end

        subgraph C_NOTIF ["notification-service (:8090)"]
            C_NOTIF_LST["OrderEventConsumer & PaymentEventConsumer<br/>Dispatch Order Confirmation & Payment Receipts"]:::subClass
        end

        subgraph C_PRODUCT ["product-service (:8086)"]
            C_PRD_LST["ProductRatingConsumer<br/>Update Average Rating & Review Count"]:::subClass
        end
    end

    %% Publishing
    PRD_RELAY -->|"Publish (key=productId)"| T_PRD
    ORD_RELAY -->|"Publish (key=orderId)"| T_ORD
    INV_RELAY -->|"Publish (key=productId)"| T_INV
    PAY_RELAY -->|"Publish (key=orderId)"| T_PAY
    SHP_RELAY -->|"Publish (key=orderId)"| T_SHP
    RTG_RELAY -->|"Publish (key=productId)"| T_RTG

    %% Consuming
    T_PRD -->|"BaseKafkaConsumer"| C_SEARCH_LST
    T_ORD -->|"Consume Order Events"| C_SHP_LST
    T_ORD -->|"Consume Order Events"| C_NOTIF_LST
    T_PAY -->|"Consume Payment Events"| C_NOTIF_LST
    T_SHP -->|"Consume shipping.delivered.v1"| C_ORD_LST
    T_RTG -->|"Consume Rating Events"| C_PRD_LST
    T_RTG -->|"Sync Rating to ES"| C_SEARCH_LST
"""
    with open(f"{OUTPUT_DIR}/event-driven-messaging-architecture.mmd", "w") as f:
        f.write(mmd.strip())
    print("Generated event-driven-messaging-architecture.mmd")

def generate_event_driven_drawio():
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<mxfile host="Electron" version="26.0.0">
  <diagram id="event-bus" name="Event-Driven-Architecture">
    <mxGraphModel dx="2200" dy="1600" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2200" pageHeight="1600">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />

        <mxCell id="t1" value="Petproject Microservices — Event-Driven Messaging &amp; Transactional Outbox Pattern" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=22;fontStyle=1;fontColor=#1a365d;" vertex="1" parent="1">
          <mxGeometry x="500" y="20" width="1200" height="40" as="geometry" />
        </mxCell>
        <mxCell id="t2" value="Zero Data Loss At-Least-Once Delivery via outbox_events Table Polling Relay, Partitioned KRaft Topics, and Typed BaseKafkaConsumers" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=12;fontColor=#4a5568;" vertex="1" parent="1">
          <mxGeometry x="500" y="60" width="1200" height="20" as="geometry" />
        </mxCell>

        <!-- Column 1: Outbox Producers -->
        <mxCell id="col_pub" value="Microservice Event Producers (Transactional Outbox Pattern)" style="swimlane;startSize=30;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="80" y="110" width="560" height="1350" as="geometry" />
        </mxCell>

        <!-- Product Outbox -->
        <mxCell id="p_prd" value="&lt;b&gt;product-service (:8086)&lt;/b&gt;&lt;br/&gt;1. Save Product/Category/Brand to PostgreSQL&lt;br/&gt;2. Insert into outbox_events in SAME transaction&lt;br/&gt;3. OutboxRelay polls PENDING records every 5s&lt;br/&gt;4. KafkaMessagePublisher sends with key=productId" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="50" width="500" height="100" as="geometry" />
        </mxCell>

        <!-- Order Outbox -->
        <mxCell id="p_ord" value="&lt;b&gt;order-service (:8084)&lt;/b&gt;&lt;br/&gt;1. Create Order / Transition Order Status&lt;br/&gt;2. Writes outbox_events (order.created.v1 / order.updated.v1)&lt;br/&gt;3. OrderOutboxRelay polls and publishes to Kafka&lt;br/&gt;4. Key = orderId (guarantees per-order FIFO ordering)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="180" width="500" height="100" as="geometry" />
        </mxCell>

        <!-- Inventory Outbox -->
        <mxCell id="p_inv" value="&lt;b&gt;inventory-service (:8082)&lt;/b&gt;&lt;br/&gt;1. Reserve / Commit / Release / Adjust Stock&lt;br/&gt;2. Writes outbox_events with CloudEvents dot-case format&lt;br/&gt;3. InventoryOutboxRelay sends with key=productId&lt;br/&gt;4. Events: inventory.reserved.v1, committed.v1, released.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="310" width="500" height="100" as="geometry" />
        </mxCell>

        <!-- Payment Outbox -->
        <mxCell id="p_pay" value="&lt;b&gt;payment-service (:8085)&lt;/b&gt;&lt;br/&gt;1. Capture Stripe Payment / Process Refund&lt;br/&gt;2. Writes outbox_events (payment.succeeded.v1)&lt;br/&gt;3. PaymentOutboxRelay sends with key=orderId&lt;br/&gt;4. Idempotency Key guarantees exactly-once processing" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="440" width="500" height="100" as="geometry" />
        </mxCell>

        <!-- Shipping Outbox -->
        <mxCell id="p_shp" value="&lt;b&gt;shipping-service (:8087)&lt;/b&gt;&lt;br/&gt;1. Carrier Webhook marks parcel DELIVERED&lt;br/&gt;2. Writes outbox_events (shipping.delivered.v1)&lt;br/&gt;3. ShippingOutboxRelay sends with key=orderId" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="570" width="500" height="100" as="geometry" />
        </mxCell>

        <!-- Rating Outbox -->
        <mxCell id="p_rtg" value="&lt;b&gt;rating-service (:8089)&lt;/b&gt;&lt;br/&gt;1. Review Approved / Rejected by Backoffice Admin&lt;br/&gt;2. Writes outbox_events (RatingCreated / RatingApproved)&lt;br/&gt;3. RatingOutboxRelay sends with key=productId" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="700" width="500" height="100" as="geometry" />
        </mxCell>

        <!-- Outbox Schema Box -->
        <mxCell id="p_schema" value="&lt;b&gt;Standard outbox_events Table Schema (Postgres)&lt;/b&gt;&lt;br/&gt;&lt;code&gt;• id BIGSERIAL PRIMARY KEY&lt;br/&gt;• event_id UUID NOT NULL UNIQUE&lt;br/&gt;• aggregate_type VARCHAR(50) (e.g. Order, Product)&lt;br/&gt;• aggregate_id VARCHAR(100)&lt;br/&gt;• event_type VARCHAR(100) (e.g. order.created.v1)&lt;br/&gt;• topic VARCHAR(100) NOT NULL&lt;br/&gt;• payload TEXT NOT NULL (JSON)&lt;br/&gt;• status VARCHAR(20) (PENDING, SENT, FAILED)&lt;br/&gt;• retry_count INT DEFAULT 0&lt;br/&gt;• created_at TIMESTAMP NOT NULL&lt;/code&gt;" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=10;align=left;spacingLeft=15;" vertex="1" parent="col_pub">
          <mxGeometry x="30" y="840" width="500" height="200" as="geometry" />
        </mxCell>

        <!-- Column 2: Kafka Bus Topics -->
        <mxCell id="col_bus" value="Apache Kafka 3.9 KRaft Cluster (:9092)" style="swimlane;startSize=30;fillColor=#fff2cc;strokeColor=#d6b656;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="720" y="110" width="520" height="1350" as="geometry" />
        </mxCell>

        <mxCell id="top_prd" value="&lt;b&gt;shop.product.lifecycle.v1&lt;/b&gt;&lt;br/&gt;Key: productId&lt;br/&gt;Payload: ProductCreated, ProductUpdated, ProductDeleted" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="50" width="460" height="80" as="geometry" />
        </mxCell>

        <mxCell id="top_ord" value="&lt;b&gt;shop.order.lifecycle.v1&lt;/b&gt;&lt;br/&gt;Key: orderId&lt;br/&gt;Payload: order.created.v1, order.updated.v1, order.cancelled.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="180" width="460" height="80" as="geometry" />
        </mxCell>

        <mxCell id="top_inv" value="&lt;b&gt;shop.inventory.events.v1&lt;/b&gt;&lt;br/&gt;Key: productId&lt;br/&gt;Payload: inventory.reserved.v1, committed.v1, released.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="310" width="460" height="80" as="geometry" />
        </mxCell>

        <mxCell id="top_pay" value="&lt;b&gt;shop.payment.events.v1&lt;/b&gt;&lt;br/&gt;Key: orderId&lt;br/&gt;Payload: payment.succeeded.v1, payment.failed.v1, payment.refunded.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="440" width="460" height="80" as="geometry" />
        </mxCell>

        <mxCell id="top_shp" value="&lt;b&gt;shop.shipping.events.v1&lt;/b&gt;&lt;br/&gt;Key: orderId&lt;br/&gt;Payload: shipping.dispatched.v1, shipping.delivered.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="570" width="460" height="80" as="geometry" />
        </mxCell>

        <mxCell id="top_rtg" value="&lt;b&gt;shop.rating.events.v1&lt;/b&gt;&lt;br/&gt;Key: productId&lt;br/&gt;Payload: RatingCreated, RatingUpdated, RatingApproved" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="700" width="460" height="80" as="geometry" />
        </mxCell>

        <mxCell id="top_tax" value="&lt;b&gt;shop.category.lifecycle.v1 &amp; shop.brand.lifecycle.v1&lt;/b&gt;&lt;br/&gt;Taxonomy updates for downstream cache invalidators" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="col_bus">
          <mxGeometry x="30" y="830" width="460" height="70" as="geometry" />
        </mxCell>

        <!-- Column 3: Consumers -->
        <mxCell id="col_sub" value="Subscribed Consumers &amp; Downstream Workflows" style="swimlane;startSize=30;fillColor=#b1ddf0;strokeColor=#10739e;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="1320" y="110" width="600" height="1350" as="geometry" />
        </mxCell>

        <mxCell id="c_es" value="&lt;b&gt;search-service (:8094) — ProductSearchConsumer&lt;/b&gt;&lt;br/&gt;• Listens to: shop.product.lifecycle.v1&lt;br/&gt;• Upserts / updates / deletes documents in Elasticsearch index products&lt;br/&gt;• Synchronizes taxonomy and updates BM25 scoring" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_sub">
          <mxGeometry x="30" y="50" width="540" height="90" as="geometry" />
        </mxCell>

        <mxCell id="c_shp" value="&lt;b&gt;shipping-service (:8087) — OrderEventConsumer&lt;/b&gt;&lt;br/&gt;• Listens to: shop.order.lifecycle.v1&lt;br/&gt;• When order status transitions to CONFIRMED:&lt;br/&gt;  Creates shipment package, generates tracking ID, sets status PENDING" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_sub">
          <mxGeometry x="30" y="180" width="540" height="90" as="geometry" />
        </mxCell>

        <mxCell id="c_notif" value="&lt;b&gt;notification-service (:8090) — Order &amp; Payment Consumers&lt;/b&gt;&lt;br/&gt;• Listens to: shop.order.lifecycle.v1 &amp; shop.payment.events.v1&lt;br/&gt;• Sends Order Placed Email, Payment Receipt, Dispatch Tracking Email&lt;br/&gt;• Stores in notifications history &amp; emails retry audit table" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_sub">
          <mxGeometry x="30" y="310" width="540" height="90" as="geometry" />
        </mxCell>

        <mxCell id="c_ord" value="&lt;b&gt;order-service (:8084) — ShippingDeliveredConsumer&lt;/b&gt;&lt;br/&gt;• Listens to: shop.shipping.events.v1 (shipping.delivered.v1)&lt;br/&gt;• Auto-transitions order status from SHIPPED to DELIVERED&lt;br/&gt;• Enables Verified Purchase review gate for customer" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_sub">
          <mxGeometry x="30" y="560" width="540" height="90" as="geometry" />
        </mxCell>

        <mxCell id="c_prd_rtg" value="&lt;b&gt;product-service (:8086) — ProductRatingConsumer&lt;/b&gt;&lt;br/&gt;• Listens to: shop.rating.events.v1 (RatingApproved)&lt;br/&gt;• Recalculates average star rating and review count in PostgreSQL&lt;br/&gt;• Invalidates Redis product cache" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_sub">
          <mxGeometry x="30" y="700" width="540" height="90" as="geometry" />
        </mxCell>

        <mxCell id="c_es_rtg" value="&lt;b&gt;search-service (:8094) — RatingSyncListener&lt;/b&gt;&lt;br/&gt;• Listens to: shop.rating.events.v1&lt;br/&gt;• Updates product review score in Elasticsearch products index&lt;br/&gt;• Indexes full review into Elasticsearch ratings index" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="col_sub">
          <mxGeometry x="30" y="820" width="540" height="90" as="geometry" />
        </mxCell>

        <!-- Edges from Producers to Topics -->
        <mxCell id="ep1" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="p_prd" target="top_prd">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ep2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="p_ord" target="top_ord">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ep3" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="p_inv" target="top_inv">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ep4" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="p_pay" target="top_pay">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ep5" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="p_shp" target="top_shp">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ep6" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="p_rtg" target="top_rtg">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

        <!-- Edges from Topics to Consumers -->
        <mxCell id="ec1" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_prd" target="c_es">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ec2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_ord" target="c_shp">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ec3" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_ord" target="c_notif">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ec4" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_pay" target="c_notif">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ec5" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_shp" target="c_ord">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ec6" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_rtg" target="c_prd_rtg">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>
        <mxCell id="ec7" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#10739e;" edge="1" parent="1" source="top_rtg" target="c_es_rtg">
          <mxGeometry relative="1" as="geometry" />
        </mxCell>

      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
"""
    with open(f"{OUTPUT_DIR}/event-driven-messaging-architecture.drawio", "w") as f:
        f.write(xml.strip())
    print("Generated event-driven-messaging-architecture.drawio")

# -------------------------------------------------------------------------
# DIAGRAM 3: END-TO-END ORDER FULFILLMENT SAGA (SEQUENCE DIAGRAM)
# -------------------------------------------------------------------------

def generate_order_saga_mermaid():
    mmd = """%% End-to-End E-Commerce Order Fulfillment Saga Lifecycle
sequenceDiagram
    autonumber
    actor Customer as Customer (Browser/App)
    participant GW as API Gateway :8080
    participant ORD as order-service :8084
    participant PRD as product-service :8086
    participant PRM as promotion-service :8093
    participant TAX as tax-service :8091
    participant INV as inventory-service :8082
    participant BUS as Kafka Bus (KRaft)
    participant PAY as payment-service :8085
    participant SHP as shipping-service :8087
    participant NTF as notification-service :8090
    participant RTG as rating-service :8089
    participant SRH as search-service :8094

    Note over Customer,GW: Step 1: Cart Checkout & Order Placement
    Customer->>GW: POST /api/v1/orders {items, address, coupon}
    GW->>ORD: Forward with JWT & X-Correlation-Id
    ORD->>PRD: REST GET /api/v1/products/{id} (Validate SKU, price, status)
    PRD-->>ORD: ProductDetailResponse (ACTIVE)
    ORD->>PRM: REST POST /api/v1/backoffice/promotions/apply {code, amount}
    PRM-->>ORD: ApplyResponse {discountAmount, finalAmount}
    ORD->>TAX: REST POST /api/v1/backoffice/tax-rates/calculate {classId, country, amount}
    TAX-->>ORD: CalculateResponse {taxAmount, appliedRate}
    ORD->>INV: REST POST /api/v1/inventory/{id}/reserve {quantity}
    INV-->>ORD: ReserveResponse {reservationId, expiresAt}
    ORD->>ORD: Save Order (status: PENDING) + Save Outbox Event
    ORD-->>Customer: 201 Created (OrderDto)

    Note over ORD,NTF: Step 2: Order Placed Event & Notification
    ORD->>BUS: Publish shop.order.lifecycle.v1 (order.created.v1)
    BUS-->>NTF: Consume order.created.v1
    NTF->>NTF: Send Order Confirmation Email to Customer

    Note over Customer,PAY: Step 3: Payment Capture via Stripe
    Customer->>GW: POST /api/v1/payments {orderId, method: STRIPE}
    GW->>PAY: Forward payment initiation
    PAY->>PAY: Create Payment Record (PENDING)
    PAY-->>Customer: PaymentResponse {checkoutUrl, clientSecret}
    Customer->>PAY: Complete Payment / Stripe Webhook callback
    PAY->>PAY: Capture Payment (status: CAPTURED) + Save Outbox
    PAY->>BUS: Publish shop.payment.events.v1 (payment.succeeded.v1)

    Note over ORD,SHP: Step 4: Order Confirmation & Fulfillment Dispatch
    Customer->>ORD: Confirm Order / System Confirm Hook
    ORD->>PAY: REST GET /api/v1/payments/order/{orderId} (Verify CAPTURED)
    PAY-->>ORD: PaymentStatusSnapshot (CAPTURED)
    ORD->>INV: REST POST /api/v1/inventory/reservations/{id}/commit
    INV-->>ORD: Stock Deducted
    ORD->>PRM: REST Commit Promotion Usage
    ORD->>ORD: Update Order Status -> CONFIRMED + Save Outbox
    ORD->>BUS: Publish shop.order.lifecycle.v1 (order.updated.v1 - CONFIRMED)
    BUS-->>SHP: Consume order.updated.v1 (CONFIRMED)
    SHP->>SHP: Create Shipment, Assign Tracking Number, Status: PENDING
    SHP->>BUS: Publish shop.shipping.events.v1 (shipping.dispatched.v1)
    BUS-->>NTF: Consume shipping.dispatched.v1 -> Send Tracking Email

    Note over SHP,RTG: Step 5: Carrier Delivery & Order Completion
    SHP->>SHP: Carrier Webhook -> Status updated to DELIVERED
    SHP->>BUS: Publish shop.shipping.events.v1 (shipping.delivered.v1)
    BUS-->>ORD: Consume shipping.delivered.v1
    ORD->>ORD: Auto-transition Order status -> DELIVERED

    Note over Customer,SRH: Step 6: Verified Review & Catalog Reindexing
    Customer->>GW: POST /api/v1/storefront/ratings {productId, score: 5, comment}
    GW->>RTG: Forward review submission
    RTG->>ORD: REST GET /api/v1/orders/verify-purchase?userId=&productId=
    ORD-->>RTG: 200 OK (Has DELIVERED Order Item)
    RTG->>RTG: Save Rating (APPROVED) + Save Outbox
    RTG-->>Customer: 201 Created (RatingDto)
    RTG->>BUS: Publish shop.rating.events.v1 (RatingApproved)
    BUS-->>PRD: Consume RatingApproved -> Update Product Avg Rating & Count
    BUS-->>SRH: Consume RatingApproved -> Update Elasticsearch products & ratings index
"""
    with open(f"{OUTPUT_DIR}/e2e-order-fulfillment-saga.mmd", "w") as f:
        f.write(mmd.strip())
    print("Generated e2e-order-fulfillment-saga.mmd")

def generate_order_saga_drawio():
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<mxfile host="Electron" version="26.0.0">
  <diagram id="saga" name="Order-Fulfillment-Saga">
    <mxGraphModel dx="2200" dy="1600" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2200" pageHeight="1600">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />

        <mxCell id="t1" value="End-to-End E-Commerce Order Fulfillment Saga Architecture" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=22;fontStyle=1;fontColor=#1a365d;" vertex="1" parent="1">
          <mxGeometry x="500" y="20" width="1200" height="40" as="geometry" />
        </mxCell>
        <mxCell id="t2" value="Hybrid Orchestration &amp; Choreography: Synchronous Two-Phase Stock Reservation, Outbox Event Relay, and Verified Buyer Review Gate" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=12;fontColor=#4a5568;" vertex="1" parent="1">
          <mxGeometry x="500" y="60" width="1200" height="20" as="geometry" />
        </mxCell>

        <!-- Phase 1: Order Checkout & Validation -->
        <mxCell id="box_phase1" value="Phase 1: Synchronous Checkout &amp; Two-Phase Inventory Reservation" style="swimlane;startSize=30;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="80" y="110" width="2040" height="220" as="geometry" />
        </mxCell>
        <mxCell id="step1_1" value="&lt;b&gt;1. Customer Checkout&lt;/b&gt;&lt;br/&gt;POST /api/v1/orders&lt;br/&gt;Payload: items, address, coupon&lt;br/&gt;Header: Authorization Bearer JWT" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="box_phase1">
          <mxGeometry x="30" y="50" width="260" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step1_2" value="&lt;b&gt;2. Product Validation&lt;/b&gt;&lt;br/&gt;order-service calls product-service&lt;br/&gt;GET /api/v1/products/{id}&lt;br/&gt;Guards: Status == ACTIVE, SKU match" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="box_phase1">
          <mxGeometry x="340" y="50" width="280" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step1_3" value="&lt;b&gt;3. Pricing, Tax &amp; Discount&lt;/b&gt;&lt;br/&gt;Calls promotion-service (/apply)&lt;br/&gt;Calls tax-service (/calculate)&lt;br/&gt;Computes subtotal, discount, tax, total" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="box_phase1">
          <mxGeometry x="670" y="50" width="280" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step1_4" value="&lt;b&gt;4. Two-Phase Stock Reservation&lt;/b&gt;&lt;br/&gt;Calls inventory-service&lt;br/&gt;POST /api/v1/inventory/{id}/reserve&lt;br/&gt;Holds stock (INV-3002 conflict if full)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="box_phase1">
          <mxGeometry x="1000" y="50" width="280" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step1_5" value="&lt;b&gt;5. Order Created &amp; Outbox&lt;/b&gt;&lt;br/&gt;Order saved with status: PENDING&lt;br/&gt;Writes outbox_events in same TX&lt;br/&gt;Returns 201 Created (OrderDto)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="box_phase1">
          <mxGeometry x="1330" y="50" width="280" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step1_6" value="&lt;b&gt;6. Kafka Event &amp; Email&lt;/b&gt;&lt;br/&gt;Relay sends order.created.v1&lt;br/&gt;notification-service dispatches&lt;br/&gt;order confirmation email" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=11;" vertex="1" parent="box_phase1">
          <mxGeometry x="1660" y="50" width="340" height="80" as="geometry" />
        </mxCell>

        <!-- Connectors Phase 1 -->
        <mxCell id="s1e1" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#6c8ebf;" edge="1" parent="box_phase1" source="step1_1" target="step1_2"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s1e2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#6c8ebf;" edge="1" parent="box_phase1" source="step1_2" target="step1_3"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s1e3" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#6c8ebf;" edge="1" parent="box_phase1" source="step1_3" target="step1_4"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s1e4" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#6c8ebf;" edge="1" parent="box_phase1" source="step1_4" target="step1_5"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s1e5" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#6c8ebf;" edge="1" parent="box_phase1" source="step1_5" target="step1_6"><mxGeometry relative="1" as="geometry" /></mxCell>

        <!-- Phase 2: Payment & Order Confirmation -->
        <mxCell id="box_phase2" value="Phase 2: Payment Processing &amp; Commit Coordinator" style="swimlane;startSize=30;fillColor=#d0cee2;strokeColor=#56517e;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="80" y="360" width="2040" height="220" as="geometry" />
        </mxCell>
        <mxCell id="step2_1" value="&lt;b&gt;7. Initiate Payment&lt;/b&gt;&lt;br/&gt;POST /api/v1/payments&lt;br/&gt;orderId, paymentMethod=STRIPE&lt;br/&gt;Creates payment record PENDING" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#56517e;fontSize=11;" vertex="1" parent="box_phase2">
          <mxGeometry x="30" y="50" width="280" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step2_2" value="&lt;b&gt;8. Stripe Gateway Capture&lt;/b&gt;&lt;br/&gt;User pays via Stripe Checkout&lt;br/&gt;Stripe Webhook calls payment-service&lt;br/&gt;Status updated to CAPTURED" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#56517e;fontSize=11;" vertex="1" parent="box_phase2">
          <mxGeometry x="360" y="50" width="290" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step2_3" value="&lt;b&gt;9. Payment Event Emitted&lt;/b&gt;&lt;br/&gt;Outbox emits payment.succeeded.v1&lt;br/&gt;Key = orderId&lt;br/&gt;notification-service sends receipt" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=11;" vertex="1" parent="box_phase2">
          <mxGeometry x="700" y="50" width="290" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step2_4" value="&lt;b&gt;10. Order Confirm Gate&lt;/b&gt;&lt;br/&gt;order-service checks PaymentServiceClient&lt;br/&gt;Verifies CAPTURED (fails closed ORD-4012)&lt;br/&gt;Commits reservation in inventory-service" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#56517e;fontSize=11;" vertex="1" parent="box_phase2">
          <mxGeometry x="1040" y="50" width="310" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step2_5" value="&lt;b&gt;11. Order Status CONFIRMED&lt;/b&gt;&lt;br/&gt;Status transitions to CONFIRMED&lt;br/&gt;Outbox emits order.updated.v1&lt;br/&gt;shipping-service consumes event" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#56517e;fontSize=11;" vertex="1" parent="box_phase2">
          <mxGeometry x="1400" y="50" width="310" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step2_6" value="&lt;b&gt;12. Shipment Created&lt;/b&gt;&lt;br/&gt;shipping-service generates package&lt;br/&gt;Assigns carrier tracking code&lt;br/&gt;Emits shipping.dispatched.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=11;" vertex="1" parent="box_phase2">
          <mxGeometry x="1750" y="50" width="260" height="80" as="geometry" />
        </mxCell>

        <!-- Connectors Phase 2 -->
        <mxCell id="s2e1" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#56517e;" edge="1" parent="box_phase2" source="step2_1" target="step2_2"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s2e2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#56517e;" edge="1" parent="box_phase2" source="step2_2" target="step2_3"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s2e3" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#56517e;" edge="1" parent="box_phase2" source="step2_3" target="step2_4"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s2e4" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#56517e;" edge="1" parent="box_phase2" source="step2_4" target="step2_5"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s2e5" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#56517e;" edge="1" parent="box_phase2" source="step2_5" target="step2_6"><mxGeometry relative="1" as="geometry" /></mxCell>

        <!-- Phase 3: Delivery & Verified Review -->
        <mxCell id="box_phase3" value="Phase 3: Delivery Completion, Verified Buyer Review Gate &amp; Search Sync" style="swimlane;startSize=30;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="80" y="610" width="2040" height="220" as="geometry" />
        </mxCell>
        <mxCell id="step3_1" value="&lt;b&gt;13. Carrier Webhook Delivery&lt;/b&gt;&lt;br/&gt;Carrier calls shipping-service webhook&lt;br/&gt;Parcel marked as DELIVERED&lt;br/&gt;Outbox emits shipping.delivered.v1" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="box_phase3">
          <mxGeometry x="30" y="50" width="310" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step3_2" value="&lt;b&gt;14. Order DELIVERED Status&lt;/b&gt;&lt;br/&gt;order-service ShippingDeliveredConsumer&lt;br/&gt;auto-transitions order status to DELIVERED&lt;br/&gt;Enables purchase verification" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="box_phase3">
          <mxGeometry x="390" y="50" width="320" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step3_3" value="&lt;b&gt;15. Customer Posts Review&lt;/b&gt;&lt;br/&gt;POST /api/v1/storefront/ratings&lt;br/&gt;rating-service EligibilityClient calls&lt;br/&gt;order-service /verify-purchase" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="box_phase3">
          <mxGeometry x="760" y="50" width="320" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step3_4" value="&lt;b&gt;16. Verified Purchase Gate&lt;/b&gt;&lt;br/&gt;Returns true ONLY if DELIVERED order item&lt;br/&gt;Fails closed: throws RTG-11001 if unverified&lt;br/&gt;Review saved and approved" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="box_phase3">
          <mxGeometry x="1130" y="50" width="330" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step3_5" value="&lt;b&gt;17. Product Rating Recalculation&lt;/b&gt;&lt;br/&gt;RatingApproved event published&lt;br/&gt;product-service recalculates average stars&lt;br/&gt;Updates PostgreSQL &amp; evicts Redis cache" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="box_phase3">
          <mxGeometry x="1510" y="50" width="240" height="80" as="geometry" />
        </mxCell>
        <mxCell id="step3_6" value="&lt;b&gt;18. Elasticsearch Catalog Sync&lt;/b&gt;&lt;br/&gt;search-service updates products doc&lt;br/&gt;Indexes review into ratings index&lt;br/&gt;Storefront search reflects new rating" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#b1ddf0;strokeColor=#10739e;fontSize=11;" vertex="1" parent="box_phase3">
          <mxGeometry x="1790" y="50" width="220" height="80" as="geometry" />
        </mxCell>

        <!-- Connectors Phase 3 -->
        <mxCell id="s3e1" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="box_phase3" source="step3_1" target="step3_2"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s3e2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="box_phase3" source="step3_2" target="step3_3"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s3e3" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="box_phase3" source="step3_3" target="step3_4"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s3e4" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="box_phase3" source="step3_4" target="step3_5"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="s3e5" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="box_phase3" source="step3_5" target="step3_6"><mxGeometry relative="1" as="geometry" /></mxCell>

      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
"""
    with open(f"{OUTPUT_DIR}/e2e-order-fulfillment-saga.drawio", "w") as f:
        f.write(xml.strip())
    print("Generated e2e-order-fulfillment-saga.drawio")

# -------------------------------------------------------------------------
# DIAGRAM 4: GATEWAY SECURITY & TWO-LAYER RATE LIMITING
# -------------------------------------------------------------------------

def generate_gateway_security_mermaid():
    mmd = """%% Gateway Security, Filter Chain & Two-Layer Rate Limiting Architecture
flowchart TD
    classDef clientClass fill:#dae8fc,stroke:#6c8ebf,stroke-width:2px,color:#000000;
    classDef filterClass fill:#ffe6cc,stroke:#d79b00,stroke-width:2px,color:#000000;
    classDef redisClass fill:#f8cecc,stroke:#b85450,stroke-width:2px,color:#000000;
    classDef keycloakClass fill:#e1d5e7,stroke:#9673a6,stroke-width:2px,color:#000000;
    classDef decisionClass fill:#fff2cc,stroke:#d6b656,stroke-width:2px,color:#000000;
    classDef rejectClass fill:#f8cecc,stroke:#c0392b,stroke-width:2px,color:#ffffff,font-weight:bold;
    classDef backendClass fill:#d5e8d4,stroke:#82b366,stroke-width:2px,color:#000000;

    CLI["Inbound Client Request<br/>(Browser / Mobile / Webhook)"]:::clientClass
    GW_ENTRY["Spring Cloud Gateway :8080<br/>Netty Non-Blocking Event Loop"]:::filterClass

    subgraph SEC_CHAIN ["Reactive Security & Tracing Filter Chain"]
        direction TB
        CORS_FILTER["1. CorsWebFilter<br/>Validate Origin, Headers, Credentials"]:::filterClass
        MDC_FILTER["2. Traceparent / MDC Filter<br/>Extract or Generate X-Correlation-Id"]:::filterClass
        AUTH_FILTER["3. JwtAuthenticationFilter<br/>Decode & Validate Bearer Token against Keycloak JWKS"]:::filterClass
    end

    KC[("Keycloak 26 JWKS<br/>/realms/ecommerce/protocol/openid-connect/certs")]:::keycloakClass

    subgraph L1 ["Layer 1: Global System Rate Limiter (HIGHEST_PRECEDENCE)"]
        direction TB
        G_FILTER["GlobalRateLimitFilter<br/>Guards Total Cluster Capacity (Flash-Sale Guard)"]:::filterClass
        G_BUCKET["Fixed Key: gateway-system / system<br/>Rate: 2,000 req/s | Burst: 4,000"]:::filterClass
        G_DEC{"Global Bucket<br/>Has Tokens?"}:::decisionClass
    end

    subgraph L2 ["Layer 2: Per-Client Per-Route Rate Limiter"]
        direction TB
        R_RESOLVER["RateLimitKeyResolver<br/>Principal Authenticated? -> user:<sub><br/>Anonymous? -> ip:<client>"]:::filterClass
        R_FILTER["RequestRateLimiterGatewayFilterFactory<br/>Compound Key: {routeId, clientKey}"]:::filterClass
        R_BUCKET["Per-Route Limits<br/>Rate: 100 req/s | Burst: 200"]:::filterClass
        R_DEC{"Route Bucket<br/>Has Tokens?"}:::decisionClass
    end

    REDIS[("Redis 7.4 Instance (:6379)<br/>Atomic Lua Script execution<br/>request_rate_limiter.lua")]:::redisClass

    REJECT_429["HTTP 429 Too Many Requests<br/>Set X-RateLimit-Remaining: 0<br/>Drop Connection immediately"]:::rejectClass
    FORWARD["Forward to Downstream Microservices<br/>Full Path /api/v1/* (No prefix stripping)"]:::backendClass

    CLI -->|"HTTP Request"| GW_ENTRY
    GW_ENTRY --> CORS_FILTER
    CORS_FILTER --> MDC_FILTER
    MDC_FILTER --> AUTH_FILTER
    AUTH_FILTER -.->|"Verify Token"| KC
    AUTH_FILTER --> G_FILTER

    G_FILTER --> G_BUCKET
    G_BUCKET <-->|"Atomic INCR/EXPIRE Lua"| REDIS
    G_BUCKET --> G_DEC
    G_DEC -->|"NO (Capacity Exceeded)"| REJECT_429
    G_DEC -->|"YES (Passed Layer 1)"| R_RESOLVER

    R_RESOLVER --> R_FILTER
    R_FILTER --> R_BUCKET
    R_BUCKET <-->|"Atomic INCR/EXPIRE Lua"| REDIS
    R_BUCKET --> R_DEC
    R_DEC -->|"NO (Quota Exceeded)"| REJECT_429
    R_DEC -->|"YES (Passed Layer 2)"| FORWARD
"""
    with open(f"{OUTPUT_DIR}/gateway-security-rate-limit.mmd", "w") as f:
        f.write(mmd.strip())
    print("Generated gateway-security-rate-limit.mmd")

def generate_gateway_security_drawio():
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<mxfile host="Electron" version="26.0.0">
  <diagram id="gateway-sec" name="Gateway-Security-Rate-Limiting">
    <mxGraphModel dx="2000" dy="1400" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2000" pageHeight="1400">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />

        <mxCell id="t1" value="Spring Cloud Gateway — Edge Security &amp; Dual-Layer Rate Limiting Architecture" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=22;fontStyle=1;fontColor=#1a365d;" vertex="1" parent="1">
          <mxGeometry x="400" y="20" width="1200" height="40" as="geometry" />
        </mxCell>
        <mxCell id="t2" value="Two Independent Redis Token Buckets: Global Flash-Sale Guard (2,000 req/s) &amp; Per-Client Route Guard (100 req/s)" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=12;fontColor=#4a5568;" vertex="1" parent="1">
          <mxGeometry x="400" y="60" width="1200" height="20" as="geometry" />
        </mxCell>

        <!-- Client Node -->
        <mxCell id="gw_cli" value="&lt;b&gt;Client Request&lt;/b&gt;&lt;br/&gt;Web / Mobile / Webhook&lt;br/&gt;HTTPS :8080" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1">
          <mxGeometry x="80" y="140" width="180" height="70" as="geometry" />
        </mxCell>

        <!-- Container: Reactive Filter Chain -->
        <mxCell id="box_sec" value="Stage 1: Reactive Security &amp; Context Initialization" style="swimlane;startSize=30;fillColor=#e1d5e7;strokeColor=#9673a6;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="320" y="110" width="700" height="270" as="geometry" />
        </mxCell>
        <mxCell id="sec_cors" value="&lt;b&gt;CorsWebFilter&lt;/b&gt;&lt;br/&gt;Validates Origin, Allowed Headers, Methods, Credentials" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#9673a6;fontSize=11;" vertex="1" parent="box_sec">
          <mxGeometry x="30" y="50" width="300" height="60" as="geometry" />
        </mxCell>
        <mxCell id="sec_mdc" value="&lt;b&gt;MDC &amp; Traceparent Filter&lt;/b&gt;&lt;br/&gt;Extracts/Generates X-Correlation-Id into Reactor Context" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#9673a6;fontSize=11;" vertex="1" parent="box_sec">
          <mxGeometry x="360" y="50" width="310" height="60" as="geometry" />
        </mxCell>
        <mxCell id="sec_jwt" value="&lt;b&gt;JwtAuthenticationFilter (OAuth2 Resource Server)&lt;/b&gt;&lt;br/&gt;• Verifies JWT signature against Keycloak JWKS endpoint&lt;br/&gt;• Extracts realm_access roles: ADMIN, USER, SERVICE&lt;br/&gt;• Populates ReactiveSecurityContext with AuthenticatedUser" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#9673a6;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="box_sec">
          <mxGeometry x="30" y="130" width="640" height="70" as="geometry" />
        </mxCell>
        <mxCell id="sec_kc" value="&lt;b&gt;Keycloak 26 JWKS&lt;/b&gt;&lt;br/&gt;/realms/ecommerce/certs" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=10;dashed=1;" vertex="1" parent="box_sec">
          <mxGeometry x="470" y="210" width="200" height="40" as="geometry" />
        </mxCell>

        <!-- Container: Layer 1 Global Limiter -->
        <mxCell id="box_l1" value="Stage 2: Layer 1 — Global System Limiter (HIGHEST_PRECEDENCE)" style="swimlane;startSize=30;fillColor=#fff2cc;strokeColor=#d6b656;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="320" y="420" width="700" height="230" as="geometry" />
        </mxCell>
        <mxCell id="l1_filter" value="&lt;b&gt;GlobalRateLimitFilter (Ordered.HIGHEST_PRECEDENCE)&lt;/b&gt;&lt;br/&gt;• Single shared bucket for entire gateway cluster&lt;br/&gt;• Flash-sale surge protector (stops overload before backends touch)&lt;br/&gt;• Bucket Key: gateway-system / system" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#d6b656;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="box_l1">
          <mxGeometry x="30" y="50" width="640" height="60" as="geometry" />
        </mxCell>
        <mxCell id="l1_knob" value="&lt;b&gt;Global Limit Specs&lt;/b&gt;&lt;br/&gt;Rate: 2,000 req/s&lt;br/&gt;Burst: 4,000 req/s" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=11;" vertex="1" parent="box_l1">
          <mxGeometry x="30" y="130" width="200" height="60" as="geometry" />
        </mxCell>
        <mxCell id="l1_dec" value="Allow?" style="rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontStyle=1;fontSize=11;" vertex="1" parent="box_l1">
          <mxGeometry x="480" y="125" width="100" height="70" as="geometry" />
        </mxCell>

        <!-- Container: Layer 2 Per-Client Route Limiter -->
        <mxCell id="box_l2" value="Stage 3: Layer 2 — Per-Client Per-Route Limiter (@Primary)" style="swimlane;startSize=30;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=13;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="320" y="690" width="700" height="250" as="geometry" />
        </mxCell>
        <mxCell id="l2_resolver" value="&lt;b&gt;RateLimitKeyResolver.resolve(exchange)&lt;/b&gt;&lt;br/&gt;• If Principal authenticated: key = user:&lt;sub-uuid&gt;&lt;br/&gt;• Else anonymous: key = ip:&lt;client-ip&gt; (X-Forwarded-For parsed)&lt;br/&gt;• Route ID appended: {routeId, clientKey}" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="box_l2">
          <mxGeometry x="30" y="50" width="640" height="60" as="geometry" />
        </mxCell>
        <mxCell id="l2_knob" value="&lt;b&gt;Per-Route Limit Specs&lt;/b&gt;&lt;br/&gt;Rate: 100 req/s&lt;br/&gt;Burst: 200 req/s" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=11;" vertex="1" parent="box_l2">
          <mxGeometry x="30" y="135" width="200" height="60" as="geometry" />
        </mxCell>
        <mxCell id="l2_dec" value="Allow?" style="rhombus;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=11;" vertex="1" parent="box_l2">
          <mxGeometry x="480" y="130" width="100" height="70" as="geometry" />
        </mxCell>

        <!-- Redis Storage Box -->
        <mxCell id="box_redis" value="&lt;b&gt;Redis 7.4 Cluster (:6379)&lt;/b&gt;&lt;br/&gt;&lt;br/&gt;• Atomic Lua script execution: &lt;code&gt;request_rate_limiter.lua&lt;/code&gt;&lt;br/&gt;• Token replenishment calculation based on time delta&lt;br/&gt;• Tokens remaining and timestamp saved with TTL&lt;br/&gt;• Isolated key spaces: &lt;code&gt;request_rate_limiter.{routeId}.{key}.tokens&lt;/code&gt;" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=11;align=left;spacingLeft=15;" vertex="1" parent="1">
          <mxGeometry x="1100" y="450" width="400" height="350" as="geometry" />
        </mxCell>

        <!-- Reject 429 Box -->
        <mxCell id="gw_429" value="&lt;b&gt;HTTP 429 Too Many Requests&lt;/b&gt;&lt;br/&gt;Headers:&lt;br/&gt;X-RateLimit-Remaining: 0&lt;br/&gt;X-RateLimit-Replenish-Rate&lt;br/&gt;X-RateLimit-Burst-Capacity&lt;br/&gt;(Connection terminated early)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=11;" vertex="1" parent="1">
          <mxGeometry x="1100" y="850" width="400" height="100" as="geometry" />
        </mxCell>

        <!-- Forwarding Box -->
        <mxCell id="gw_fwd" value="&lt;b&gt;Forward to Target Microservice (:8081 - :8094)&lt;/b&gt;&lt;br/&gt;Full path forwarded with no rewrite (/api/v1/...)&lt;br/&gt;Transfers validated JWT, Claims &amp; X-Correlation-Id headers" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1">
          <mxGeometry x="320" y="990" width="700" height="80" as="geometry" />
        </mxCell>

        <!-- Connections -->
        <mxCell id="egw1" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="gw_cli" target="sec_cors"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw2" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="sec_cors" target="sec_mdc"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw3" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="sec_mdc" target="sec_jwt"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw4" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;dashed=1;" edge="1" parent="1" source="sec_jwt" target="sec_kc"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw5" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="sec_jwt" target="l1_filter"><mxGeometry relative="1" as="geometry" /></mxCell>

        <mxCell id="egw6" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="l1_filter" target="l1_knob"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw7" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="l1_knob" target="l1_dec"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw8" value="EVAL Lua" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#b85450;" edge="1" parent="1" source="l1_knob" target="box_redis"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw9" value="Deny" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#b85450;" edge="1" parent="1" source="l1_dec" target="gw_429"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw10" value="Allow" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="l1_dec" target="l2_resolver"><mxGeometry relative="1" as="geometry" /></mxCell>

        <mxCell id="egw11" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="l2_resolver" target="l2_knob"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw12" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;" edge="1" parent="1" source="l2_knob" target="l2_dec"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw13" value="EVAL Lua" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#b85450;" edge="1" parent="1" source="l2_knob" target="box_redis"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw14" value="Deny" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#b85450;" edge="1" parent="1" source="l2_dec" target="gw_429"><mxGeometry relative="1" as="geometry" /></mxCell>
        <mxCell id="egw15" value="Allow" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeWidth=2;strokeColor=#82b366;" edge="1" parent="1" source="l2_dec" target="gw_fwd"><mxGeometry relative="1" as="geometry" /></mxCell>

      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
"""
    with open(f"{OUTPUT_DIR}/gateway-security-rate-limit.drawio", "w") as f:
        f.write(xml.strip())
    print("Generated gateway-security-rate-limit.drawio")

# -------------------------------------------------------------------------
# DIAGRAM 5: DATA ARCHITECTURE, STORAGE & DEPLOYMENT TOPOLOGY
# -------------------------------------------------------------------------

def generate_data_topology_mermaid():
    mmd = """%% Data Stores, Storage Architecture & Deployment Topology
flowchart TB
    classDef pgClass fill:#d5e8d4,stroke:#82b366,stroke-width:2px,color:#000000;
    classDef redisClass fill:#f8cecc,stroke:#b85450,stroke-width:2px,color:#000000;
    classDef kafkaClass fill:#fff2cc,stroke:#d6b656,stroke-width:2px,color:#000000;
    classDef esClass fill:#b1ddf0,stroke:#10739e,stroke-width:2px,color:#000000;
    classDef s3Class fill:#fad7ac,stroke:#b46504,stroke-width:2px,color:#000000;
    classDef svcClass fill:#f5f5f5,stroke:#666666,stroke-width:1px,color:#000000;

    subgraph DOCKER_COMPOSE ["Docker Compose / K8s Deployment Topology"]
        subgraph PG_CLUSTER ["PostgreSQL 16 Instance (:5432) — max_connections=300"]
            DB_AUTH[("authservice<br/>users, roles, user_role")]:::pgClass
            DB_PRD[("productservice<br/>products, categories, brands, variants, outbox")]:::pgClass
            DB_ORD[("orderservice<br/>orders, carts, cart_items, outbox")]:::pgClass
            DB_INV[("inventoryservice<br/>inventory, reservations, outbox")]:::pgClass
            DB_PAY[("paymentservice<br/>payments, refunds, outbox")]:::pgClass
            DB_SHP[("shippingservice<br/>shippings, outbox")]:::pgClass
            DB_RTG[("ratingservice<br/>ratings, outbox")]:::pgClass
            DB_FAV[("favouriteservice<br/>favourites")]:::pgClass
            DB_TAX[("taxservice<br/>tax_classes, tax_rates")]:::pgClass
            DB_PRM[("promotionservice<br/>promotions, promotion_usage, outbox")]:::pgClass
            DB_MED[("mediaservice<br/>medias, outbox")]:::pgClass
            DB_NTF[("notificationservice<br/>notifications, emails")]:::pgClass
            DB_KC[("keycloak<br/>Keycloak IAM tables")]:::pgClass
        end

        subgraph REDIS_CLUSTER ["Redis 7.4 Instance (:6379)"]
            R_BUCKETS["Token Buckets<br/>request_rate_limiter.system<br/>request_rate_limiter.{route}.{key}"]:::redisClass
            R_CATALOG["Catalog Cache<br/>product::{id} (10m)<br/>productBySlug::{slug} (10m)<br/>category/brand (30m)"]:::redisClass
            R_SESSIONS["Sessions & Tokens<br/>sso_session::{ticket} (60s)<br/>cart cache"]:::redisClass
        end

        subgraph KAFKA_CLUSTER ["Apache Kafka 3.9 KRaft (:9092)"]
            K_BROKER["KRaft Broker & Controller (node_id=1)<br/>No Zookeeper needed<br/>Internal replication factor=1<br/>Cluster ID: MkU3OEVBNTcwNTJENDM2Qk"]:::kafkaClass
        end

        subgraph ES_CLUSTER ["Elasticsearch 8.15 (:9200)"]
            ES_IDX_PRD["Index: products<br/>Mappings: productId, title, description, category, price, score, tags"]:::esClass
            ES_IDX_RTG["Index: ratings<br/>Mappings: ratingId, productId, userId, score, comment, createdAt"]:::esClass
        end

        subgraph RUSTFS_CLUSTER ["RustFS S3 Compatible Storage (:9000/:9001)"]
            S3_BUCKET["Bucket: ecommerce-media<br/>• Original high-res uploads<br/>• 6 Responsive WebP variants (100w..1440w)<br/>• Presigned PUT/GET URLs"]:::s3Class
        end
    end
"""
    with open(f"{OUTPUT_DIR}/data-stores-infrastructure-topology.mmd", "w") as f:
        f.write(mmd.strip())
    print("Generated data-stores-infrastructure-topology.mmd")

def generate_data_topology_drawio():
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<mxfile host="Electron" version="26.0.0">
  <diagram id="datatopology" name="Data-Stores-and-Infrastructure">
    <mxGraphModel dx="2200" dy="1600" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2200" pageHeight="1600">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />

        <mxCell id="t1" value="Petproject Microservices — Data Architecture &amp; Storage Infrastructure Topology" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=22;fontStyle=1;fontColor=#1a365d;" vertex="1" parent="1">
          <mxGeometry x="500" y="20" width="1200" height="40" as="geometry" />
        </mxCell>
        <mxCell id="t2" value="12 Per-Service PostgreSQL Databases + Keycloak, Redis 7.4 Caching &amp; Limiting, Kafka 3.9 KRaft, Elasticsearch 8.15, RustFS S3" style="text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontSize=12;fontColor=#4a5568;" vertex="1" parent="1">
          <mxGeometry x="500" y="60" width="1200" height="20" as="geometry" />
        </mxCell>

        <!-- Big Container: PostgreSQL 16 -->
        <mxCell id="c_pg" value="PostgreSQL 16 Instance (:5432) — max_connections=300 (Database-per-Service Pattern)" style="swimlane;startSize=30;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="80" y="110" width="2040" height="380" as="geometry" />
        </mxCell>
        <mxCell id="pg1" value="&lt;b&gt;authservice&lt;/b&gt;&lt;br/&gt;users, roles,&lt;br/&gt;user_role" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="40" y="50" width="120" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg2" value="&lt;b&gt;productservice&lt;/b&gt;&lt;br/&gt;products, categories,&lt;br/&gt;brands, outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="190" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg3" value="&lt;b&gt;orderservice&lt;/b&gt;&lt;br/&gt;orders, carts,&lt;br/&gt;cart_items, outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="350" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg4" value="&lt;b&gt;inventoryservice&lt;/b&gt;&lt;br/&gt;inventory, reserves,&lt;br/&gt;outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="510" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg5" value="&lt;b&gt;paymentservice&lt;/b&gt;&lt;br/&gt;payments, refunds,&lt;br/&gt;outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="670" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg6" value="&lt;b&gt;shippingservice&lt;/b&gt;&lt;br/&gt;shippings,&lt;br/&gt;outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="830" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg7" value="&lt;b&gt;ratingservice&lt;/b&gt;&lt;br/&gt;ratings,&lt;br/&gt;outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="990" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg8" value="&lt;b&gt;favouriteservice&lt;/b&gt;&lt;br/&gt;favourites" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="1150" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg9" value="&lt;b&gt;taxservice&lt;/b&gt;&lt;br/&gt;tax_classes,&lt;br/&gt;tax_rates" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="1310" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg10" value="&lt;b&gt;promotionservice&lt;/b&gt;&lt;br/&gt;promotions, usage,&lt;br/&gt;outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="1470" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg11" value="&lt;b&gt;mediaservice&lt;/b&gt;&lt;br/&gt;medias,&lt;br/&gt;outbox" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="1630" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg12" value="&lt;b&gt;notificationservice&lt;/b&gt;&lt;br/&gt;notifications,&lt;br/&gt;emails retry" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="1790" y="50" width="130" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg13" value="&lt;b&gt;keycloak&lt;/b&gt;&lt;br/&gt;IAM realm tables,&lt;br/&gt;users, clients" style="shape=cylinder3;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=11;" vertex="1" parent="c_pg">
          <mxGeometry x="1940" y="50" width="90" height="90" as="geometry" />
        </mxCell>
        <mxCell id="pg_note" value="&lt;b&gt;Persistence Design Rules:&lt;/b&gt; Schema isolation strictly enforced. Cross-database queries / foreign keys are PROHIBITED. Liquibase migrations managed in &lt;code&gt;src/main/resources/db/changelog/&lt;/code&gt; per service. Composite indexes added on frequently queried FKs and partial unique indexes on &lt;code&gt;WHERE deleted = false&lt;/code&gt;." style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#82b366;fontSize=11;align=left;spacingLeft=15;" vertex="1" parent="c_pg">
          <mxGeometry x="40" y="170" width="1960" height="40" as="geometry" />
        </mxCell>

        <!-- Container: Redis 7.4 -->
        <mxCell id="c_redis" value="Redis 7.4 Cluster (:6379) — Memory Caching &amp; Atomic Token Buckets" style="swimlane;startSize=30;fillColor=#f8cecc;strokeColor=#b85450;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="80" y="520" width="650" height="240" as="geometry" />
        </mxCell>
        <mxCell id="red1" value="&lt;b&gt;Rate Limiting Token Buckets&lt;/b&gt;&lt;br/&gt;• request_rate_limiter.system (Global)&lt;br/&gt;• request_rate_limiter.{routeId}.{user|ip} (Per-Client)&lt;br/&gt;• Handled atomically by Redis Lua Script" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#b85450;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_redis">
          <mxGeometry x="30" y="50" width="590" height="70" as="geometry" />
        </mxCell>
        <mxCell id="red2" value="&lt;b&gt;Application Caches (@Cacheable)&lt;/b&gt;&lt;br/&gt;• product::{id} (10m TTL) | productBySlug::{slug} (10m TTL)&lt;br/&gt;• category::{id} (30m TTL) | brand::{id} (30m TTL)&lt;br/&gt;• Evicted automatically on product update/delete" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#b85450;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_redis">
          <mxGeometry x="30" y="140" width="590" height="70" as="geometry" />
        </mxCell>

        <!-- Container: Elasticsearch 8.15 -->
        <mxCell id="c_es" value="Elasticsearch 8.15 (:9200) — Full-Text &amp; Faceted Search Engine" style="swimlane;startSize=30;fillColor=#b1ddf0;strokeColor=#10739e;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="770" y="520" width="650" height="240" as="geometry" />
        </mxCell>
        <mxCell id="es1" value="&lt;b&gt;Index: products&lt;/b&gt;&lt;br/&gt;• Fields: productId, title, description, category, price, score, tags&lt;br/&gt;• Analyzers: Standard, Edge-NGram for autocomplete suggesters&lt;br/&gt;• Popularity boosting based on sales &amp; rating score" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_es">
          <mxGeometry x="30" y="50" width="590" height="70" as="geometry" />
        </mxCell>
        <mxCell id="es2" value="&lt;b&gt;Index: ratings&lt;/b&gt;&lt;br/&gt;• Fields: ratingId, productId, userId, score, comment, createdAt&lt;br/&gt;• Aggregations: Average stars, score distribution histogram&lt;br/&gt;• Powers fast review search and sentiment filters" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#10739e;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_es">
          <mxGeometry x="30" y="140" width="590" height="70" as="geometry" />
        </mxCell>

        <!-- Container: RustFS S3 -->
        <mxCell id="c_rustfs" value="RustFS S3 Storage (:9000/:9001) — Object Store" style="swimlane;startSize=30;fillColor=#fad7ac;strokeColor=#b46504;fontStyle=1;fontSize=14;rounded=1;" vertex="1" parent="1">
          <mxGeometry x="1460" y="520" width="660" height="240" as="geometry" />
        </mxCell>
        <mxCell id="s3_1" value="&lt;b&gt;Bucket: ecommerce-media&lt;/b&gt;&lt;br/&gt;• High-throughput Rust-based S3 compatible engine&lt;br/&gt;• S3ClientFactory from common-storage (AWS SDK v2)&lt;br/&gt;• Presigned PUT/GET URLs (5 min TTL) for direct client uploads" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#b46504;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_rustfs">
          <mxGeometry x="30" y="50" width="600" height="70" as="geometry" />
        </mxCell>
        <mxCell id="s3_2" value="&lt;b&gt;Media Variants Storage Structure&lt;/b&gt;&lt;br/&gt;• original/&lt;uuid&gt;.png&lt;br/&gt;• variants/&lt;uuid&gt;_100w.webp .. _1440w.webp (6 responsive sizes)&lt;br/&gt;• Safe deletion cascading with product reference check" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#b46504;fontSize=11;align=left;spacingLeft=10;" vertex="1" parent="c_rustfs">
          <mxGeometry x="30" y="140" width="600" height="70" as="geometry" />
        </mxCell>

      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
"""
    with open(f"{OUTPUT_DIR}/data-stores-infrastructure-topology.drawio", "w") as f:
        f.write(xml.strip())
    print("Generated data-stores-infrastructure-topology.drawio")

def main():
    print("Generating Mermaid and Draw.io diagrams...")
    generate_landscape_mermaid()
    generate_landscape_drawio()
    generate_event_driven_mermaid()
    generate_event_driven_drawio()
    generate_order_saga_mermaid()
    generate_order_saga_drawio()
    generate_gateway_security_mermaid()
    generate_gateway_security_drawio()
    generate_data_topology_mermaid()
    generate_data_topology_drawio()
    print("All diagram definitions generated successfully.")

if __name__ == "__main__":
    main()
