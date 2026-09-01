package com.shop.productservice.job;

import com.shop.productservice.client.MediaHeadClient;
import com.shop.productservice.config.ProductMediaSweepProperties;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductMediaService;
import com.shop.productservice.service.ProductMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * H-3 reconciliation sweep — the durable backstop for the at-most-once window
 * of the {@code MediaDeleted} consumer (bounded retry + ack-always posture):
 * the consumer can lose a clear to a crash right after its commit, so stale
 * product references must be re-verified EVENTUALLY, not only on event replay.
 *
 * <p>Each cycle examines ONE bounded page ({@code limit}, default 100) of
 * products holding any media reference and HEAD-checks each against
 * media-service through the fail-closed {@link MediaHeadClient}: 200 → the
 * reference is live, keep; 404 → the media is gone, clear via the existing
 * {@link ProductMediaService#clearReference} path (also re-publishes
 * {@code ProductUpdated} so the search doc refreshes).</p>
 *
 * <p>Fail-safe ruling H-3: media being UNAVAILABLE is not evidence that
 * references are dead — the client throws on anything except 200/404, and ANY
 * exception aborts the ENTIRE remaining cycle with a WARN. The sweep never
 * mass-clears references behind an outage; the next cron tick retries. The
 * {@code product_media_sweep_checked_total} / {@code product_media_sweep_cleared_total}
 * counters plus the per-cycle INFO summary make the drift (and its repair)
 * observable.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductMediaSweepJob {

    private final ProductRepository productRepository;
    private final MediaHeadClient mediaHeadClient;
    private final ProductMediaService productMediaService;
    private final ProductMediaSweepProperties properties;
    private final ProductMetrics metrics;

    @Scheduled(cron = "${shop.product.media-sweep.cron:0 */30 * * * *}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        int checked = 0;
        int cleared = 0;
        try {
            // Bounded: exactly one page per cycle — cycles are short and the
            // next tick continues; no unbounded pagination loops.
            var page = productRepository.findByMediaIdIsNotNull(
                PageRequest.of(0, properties.limit()));
            for (Product product : page.getContent()) {
                UUID mediaId = product.getMediaId();
                checked++;
                metrics.recordSweepChecked();
                if (mediaHeadClient.exists(mediaId)) {
                    continue;   // 200 — reference live, keep
                }
                // 404 — clear via the consumer's own path (transactional clear
                // + ProductUpdated per row). clearReference re-queries by
                // mediaId, so rows sharing this dead media are all repaired.
                long removed = productMediaService.clearReference(mediaId);
                cleared += removed;
                metrics.recordSweepCleared(removed);
            }
            log.info("Media sweep cycle complete: checked {} product(s), cleared {} reference(s), limit {}",
                checked, cleared, properties.limit());
        } catch (Exception ex) {
            // Media outage (fail-closed client → MED-12006) or any unexpected
            // failure: skip the ENTIRE cycle — never clear behind doubt.
            log.warn("Media sweep cycle SKIPPED after checked={} cleared={} — media integrity " +
                "verification failed, remaining rows left untouched (fail-safe, will retry next cycle)",
                checked, cleared, ex);
        }
    }
}
