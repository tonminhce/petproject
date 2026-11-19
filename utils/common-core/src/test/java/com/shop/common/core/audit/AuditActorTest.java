package com.shop.common.core.audit;

import com.shop.common.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-049: one convention for the actor of an audited change.
 */
class AuditActorTest {

    @Test
    @DisplayName("the resolved actor is returned")
    void theResolvedActorIsReturned() {
        assertThat(AuditActor.require(() -> Optional.of("alice@example.com")))
                .isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("an unresolvable actor is a domain error, not a raw NoSuchElementException")
    void anUnresolvableActorIsADomainError() {
        assertThatThrownBy(() -> AuditActor.require(() -> Optional.empty()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("AUD-9001");
                    assertThat(ex.getStatus().value()).isEqualTo(500);
                })
                .isNotInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("a blank actor is rejected too")
    void aBlankActorIsRejected() {
        assertThatThrownBy(() -> AuditActor.require(() -> Optional.of("  ")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a background job names itself")
    void aBackgroundJobNamesItself() {
        assertThat(AuditActor.system("reservation-expiry-sweep"))
                .isEqualTo("system:reservation-expiry-sweep");
        assertThatThrownBy(() -> AuditActor.system(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
