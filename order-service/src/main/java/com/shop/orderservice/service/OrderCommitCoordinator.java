package com.shop.orderservice.service;

import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCommitCoordinator {

    private final PromotionServiceClient promotionClient;
    private final InventoryServiceClient inventoryClient;
    private final MeterRegistry meterRegistry;
    private final OrderConfirmMetrics confirmMetrics;

    /** SUCCESS or throws. Never PARTIAL — compensations are best-effort, failures counted. */
    public CommitOutcome commitForConfirm(Order order, List<OrderItem> items) {
        List<CompensationTarget> committed = new ArrayList<>();   // (type,id) pairs — spec D10
        try {
            if (order.getPromotionReservationId() != null) {
                Timer.Sample promotionTimer = Timer.start(meterRegistry);
                try {
                    promotionClient.commit(order.getPromotionReservationId());
                } finally {                                        // record even on failure — §8 latency alerting
                    promotionTimer.stop(confirmMetrics.timer("commit_promotion"));
                }
                committed.add(new CompensationTarget(CompensationTarget.Type.PROMOTION,
                    order.getPromotionReservationId()));
            }
            Timer.Sample inventoryTimer = Timer.start(meterRegistry);
            try {
                List<OrderItem> sorted = items.stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId)).toList();
                for (OrderItem item : sorted) {
                    if (item.getReservationId() == null) {
                        log.info("Order {} item {}: no reservationId (legacy) — skipping commit",
                            order.getId(), item.getId());
                        continue;
                    }
                    inventoryClient.commit(item.getReservationId());
                    committed.add(new CompensationTarget(CompensationTarget.Type.INVENTORY,
                        item.getReservationId()));
                }
            } finally {
                inventoryTimer.stop(confirmMetrics.timer("commit_inventory"));
            }
            meterRegistry.counter("order.confirm.commit.outcome", "result", "success").increment();
            return CommitOutcome.SUCCESS;
        } catch (RuntimeException ex) {
            log.warn("Order {} commit failed after {} successes — compensating",
                order.getId(), committed.size(), ex);
            compensateInReverse(committed);
            meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").increment();
            throw ex;
        }
    }

    private void compensateInReverse(List<CompensationTarget> committed) {
        for (int i = committed.size() - 1; i >= 0; i--) {          // plain reverse loop — no Guava
            CompensationTarget t = committed.get(i);
            try {
                if (t.type() == CompensationTarget.Type.PROMOTION) promotionClient.releaseCommitted(t.id());
                else inventoryClient.releaseCommitted(t.id());
            } catch (Exception ex) {
                log.error("Failed to rollback {} reservation {} — reconciliation owns it", t.type(), t.id(), ex);
                meterRegistry.counter("order.commit.rollback.failed").increment();
            }
        }
    }
}
