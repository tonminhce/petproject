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

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductMediaSweepJob {

    private final ProductRepository productRepository;
    private final MediaHeadClient mediaHeadClient;
    private final ProductMediaService productMediaService;
    private final ProductMediaSweepProperties properties;
    private final ProductMetrics metrics;
    private int currentPage = 0;

    @Scheduled(cron = "${shop.product.media-sweep.cron:0 */30 * * * *}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        int checked = 0;
        int cleared = 0;
        try {
            var page = productRepository.findByMediaIdIsNotNull(
                PageRequest.of(currentPage, properties.limit()));
            if (page.isEmpty() || !page.hasNext()) {
                currentPage = 0;
            } else {
                currentPage++;
            }
            for (Product product : page.getContent()) {
                UUID mediaId = product.getMediaId();
                checked++;
                metrics.recordSweepChecked();
                if (mediaHeadClient.exists(mediaId)) {
                    continue;   // 200 — reference live, keep
                }
                // 404 — clear via the consumer's own path (transactional clear
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
