package com.shop.ratingservice.service.impls;

import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.eligibility.EligibilityClient;
import com.shop.ratingservice.repository.RatingRepository;
import com.shop.ratingservice.service.RatingService;
import com.shop.ratingservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The eligibility check is a REMOTE call and must not run while a DB
 * transaction (and its pooled connection) is held open (F-066).
 *
 * <p>The unit test cannot prove the boundary - without a Spring context the
 * {@code @Transactional} proxy is inert. This IT runs against the real
 * context (real Postgres, real transaction manager), so the mocked client
 * observes the ACTUAL transaction state of the calling thread.</p>
 */
class RatingServiceImplTransactionBoundaryIT extends AbstractIntegrationTest {

    @MockitoBean
    EligibilityClient eligibilityClient;

    @Autowired
    RatingService ratingService;

    @Autowired
    RatingRepository ratingRepository;

    @Test
    @DisplayName("the eligibility check runs OUTSIDE the transaction, the write inside it")
    void theEligibilityCheckRunsOutsideTheTransaction() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(eligibilityClient.isEligible(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("the remote eligibility check must not run inside a transaction")
                    .isFalse();
            return true;
        });

        ratingService.submit(userId, new RatingSubmitRequest(productId, 5, "Great product"));

        assertThat(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId))
                .as("the rating must be written")
                .isPresent();
    }
}
