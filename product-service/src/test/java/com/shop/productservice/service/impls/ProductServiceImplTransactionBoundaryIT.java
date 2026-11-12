package com.shop.productservice.service.impls;

import com.shop.productservice.client.MediaHeadClient;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.service.ProductService;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The media HEAD check is a REMOTE call and must not run while a DB
 * transaction (and its pooled connection) is held open (F-066).
 *
 * <p>The unit test cannot prove the boundary - without a Spring context the
 * {@code @Transactional} proxy is inert. This IT runs against the real
 * context (real Postgres, real transaction manager), so the mocked client
 * observes the ACTUAL transaction state of the calling thread.</p>
 */
class ProductServiceImplTransactionBoundaryIT extends AbstractIntegrationTest {

    @MockitoBean
    MediaHeadClient mediaHeadClient;

    @Autowired
    ProductService productService;

    @Test
    @DisplayName("the media HEAD check runs OUTSIDE the transaction")
    void theMediaHeadCheckRunsOutsideTheTransaction() {
        UUID mediaId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        when(mediaHeadClient.exists(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("the remote media check must not run inside a transaction")
                    .isFalse();
            return true;
        });

        var response = productService.create(new ProductCreateRequest(
                "iPhone 15", "iphone-15-" + suffix, "desc", "IP15-" + suffix,
                new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, mediaId,
                null, null, null, null));

        assertThat(response).isNotNull();
    }
}
