package com.shop.orderservice.service.impls;

import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.client.TaxServiceClient;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.PromotionReserveRequest;
import com.shop.orderservice.dto.internal.PromotionReserveResponse;
import com.shop.orderservice.dto.internal.TaxCalculateResponse;
import com.shop.orderservice.entity.CartItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 7 (persist-early ruling): pricing must carry the REAL orderId into the
 * promotion reserve call (spec D3 — reservation is frozen at reserve time), and
 * the returned reservationId must surface on {@link PricingBreakdown} so the
 * saga can persist it on the order and compensate on failure.
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceImplTest {

    @Mock ProductServiceClient productClient;
    @Mock TaxServiceClient taxClient;
    @Mock PromotionServiceClient promotionClient;

    @InjectMocks PricingServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private List<CartItem> items;

    @BeforeEach
    void setUp() {
        items = List.of(CartItem.builder()
            .productId(productId).productTitle("Test Product")
            .unitPrice(new BigDecimal("100.00")).quantity(2).build());
    }

    @Test
    void calculate_withCoupon_reservesWithRealOrderIdAndSubtotal_andPropagatesReservationId() {
        when(productClient.getProduct(productId))
            .thenReturn(new ProductSnapshot(productId, "Test Product", new BigDecimal("100.00")));
        when(promotionClient.isEnabled()).thenReturn(true);
        UUID reservationId = UUID.randomUUID();
        when(promotionClient.reserve(any(PromotionReserveRequest.class)))
            .thenReturn(new PromotionReserveResponse(reservationId, new BigDecimal("20.00"), new BigDecimal("180.00")));
        when(taxClient.calculate(any())).thenReturn(new TaxCalculateResponse(BigDecimal.ZERO, BigDecimal.ZERO));

        PricingBreakdown breakdown = service.calculate(orderId, userId, items, "SAVE10");

        ArgumentCaptor<PromotionReserveRequest> captor = ArgumentCaptor.forClass(PromotionReserveRequest.class);
        verify(promotionClient).reserve(captor.capture());
        PromotionReserveRequest sent = captor.getValue();
        assertThat(sent.code()).isEqualTo("SAVE10");
        assertThat(sent.orderAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(sent.userId()).isEqualTo(userId);
        assertThat(sent.orderId()).isEqualTo(orderId);   // real orderId — NOT null (persist-early)

        assertThat(breakdown.subtotal()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(breakdown.discountAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(breakdown.promotionReservationId()).isEqualTo(reservationId);
    }

    @Test
    void calculate_noCoupon_skipsReserveAndReturnsNullReservationId() {
        when(productClient.getProduct(productId))
            .thenReturn(new ProductSnapshot(productId, "Test Product", new BigDecimal("100.00")));
        when(taxClient.calculate(any())).thenReturn(new TaxCalculateResponse(BigDecimal.ZERO, BigDecimal.ZERO));

        PricingBreakdown breakdown = service.calculate(orderId, userId, items, null);

        verify(promotionClient, never()).reserve(any(PromotionReserveRequest.class));
        assertThat(breakdown.promotionReservationId()).isNull();
        assertThat(breakdown.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
