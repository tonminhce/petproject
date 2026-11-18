package com.shop.common.kafka.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KafkaTopicContract {

    public enum Mode {
        CONSUMED,
        FIRE_AND_FORGET
    }

    public record Topic(String topic, String producerModule, Mode mode, String rationale) {

        public Topic {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("topic must not be blank");
            }
            if (producerModule == null || producerModule.isBlank()) {
                throw new IllegalArgumentException("producerModule must not be blank for topic " + topic);
            }
            if (mode == null) {
                throw new IllegalArgumentException("mode must not be null for topic " + topic);
            }
            if (mode == Mode.FIRE_AND_FORGET
                    && (rationale == null || rationale.isBlank())) {
                throw new IllegalArgumentException(
                        "a fire-and-forget topic needs a rationale - topic " + topic);
            }
        }
    }


    public static final String SHOP_ORDER_LIFECYCLE = "shop.order.lifecycle.v1";
    public static final String SHOP_PAYMENT_LIFECYCLE = "shop.payment.lifecycle.v1";
    public static final String SHOP_PRODUCT_LIFECYCLE = "shop.product.lifecycle.v1";
    public static final String SHOP_RATING_LIFECYCLE = "shop.rating.lifecycle.v1";
    public static final String SHOP_SHIPPING_LIFECYCLE = "shop.shipping.lifecycle.v1";
    public static final String MEDIA_LIFECYCLE = "media.lifecycle.v1";


    public static final String SHOP_INVENTORY_EVENTS = "shop.inventory.events.v1";
    public static final String SHOP_PROMOTION_LIFECYCLE = "shop.promotion.lifecycle.v1";
    public static final String SHOP_CATEGORY_LIFECYCLE = "shop.category.lifecycle.v1";
    public static final String SHOP_BRAND_LIFECYCLE = "shop.brand.lifecycle.v1";

    public static final List<Topic> TOPICS = List.of(
            new Topic(SHOP_ORDER_LIFECYCLE, "order-service", Mode.CONSUMED,
                    "notification-service and shipping-service react to order lifecycle changes"),
            new Topic(SHOP_PAYMENT_LIFECYCLE, "payment-service", Mode.CONSUMED,
                    "order-service reacts to payment captures and refunds"),
            new Topic(SHOP_PRODUCT_LIFECYCLE, "product-service", Mode.CONSUMED,
                    "search-service reindexes the product catalogue"),
            new Topic(SHOP_RATING_LIFECYCLE, "rating-service", Mode.CONSUMED,
                    "product-service maintains the product rating aggregate"),
            new Topic(SHOP_SHIPPING_LIFECYCLE, "shipping-service", Mode.CONSUMED,
                    "order-service advances the order state machine on delivery"),
            new Topic(MEDIA_LIFECYCLE, "media-service", Mode.CONSUMED,
                    "product-service drops media references when the media is deleted"),

            new Topic(SHOP_INVENTORY_EVENTS, "inventory-service", Mode.FIRE_AND_FORGET,
                    "Stock movements (reserved/committed/released/adjusted/deleted/low_stock) are published "
                            + "for audit and external analytics. The checkout flow reads inventory "
                            + "SYNCHRONOUSLY through order-service's InventoryServiceClient, so no in-fleet "
                            + "consumer is required for correctness and no projection is derived from it."),
            new Topic(SHOP_PROMOTION_LIFECYCLE, "promotion-service", Mode.FIRE_AND_FORGET,
                    "Campaign lifecycle is published for audit and external analytics. The checkout flow "
                            + "reads promotions SYNCHRONOUSLY through order-service's PromotionServiceClient, "
                            + "so no in-fleet consumer is required for correctness."),
            new Topic(SHOP_CATEGORY_LIFECYCLE, "product-service", Mode.FIRE_AND_FORGET,
                    "Taxonomy changes are published for audit and external analytics. The product API reads "
                            + "category and brand data from the same service (no cross-service read), and the "
                            + "product cache denormalises categoryTitle/brandName with a bounded TTL, so no "
                            + "in-fleet consumer is required for correctness."),
            new Topic(SHOP_BRAND_LIFECYCLE, "product-service", Mode.FIRE_AND_FORGET,
                    "Taxonomy changes are published for audit and external analytics. The product API reads "
                            + "category and brand data from the same service (no cross-service read), and the "
                            + "product cache denormalises categoryTitle/brandName with a bounded TTL, so no "
                            + "in-fleet consumer is required for correctness."));

    private static final Map<String, Topic> BY_TOPIC = index();

    private KafkaTopicContract() {
    }

    private static Map<String, Topic> index() {
        Map<String, Topic> index = new LinkedHashMap<>();
        for (Topic topic : TOPICS) {
            Topic previous = index.put(topic.topic(), topic);
            if (previous != null) {
                throw new IllegalStateException("duplicate topic in the contract: " + topic.topic());
            }
        }
        return Map.copyOf(index);
    }

    public static Topic find(String topic) {
        return BY_TOPIC.get(topic);
    }

    public static Topic require(String topic) {
        Topic contract = BY_TOPIC.get(topic);
        if (contract == null) {
            throw new IllegalStateException("topic is not declared in the Kafka topic contract: " + topic);
        }
        return contract;
    }
}
