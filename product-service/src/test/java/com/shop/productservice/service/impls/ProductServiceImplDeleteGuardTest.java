package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.client.InventoryReservationClient;
import com.shop.productservice.client.MediaHeadClient;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.Product;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-060: the catalogue refuses to drop a product whose stock is reserved or
 * committed.
 *
 * <p>inventory-service already refused to delete an inventory row with live
 * reservations. The symmetric guard was missing on the catalogue side, so a
 * product could leave the catalogue while its stock was reserved - the
 * checkout could still commit that reservation for a product that no longer
 * existed, and the reserved stock never returned to the sellable pool.</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplDeleteGuardTest {

    @Mock ProductRepository repo;
    @Mock CategoryRepository categoryRepo;
    @Mock BrandRepository brandRepo;
    @Mock ProductMapper mapper;
    @Mock ProductEventPublisher publisher;
    @Mock AuditorAware<String> auditorAware;
    @Mock MediaHeadClient mediaHeadClient;
    @Mock InventoryReservationClient inventoryReservationClient;
    @Mock CacheManager cacheManager;
    @Mock TransactionTemplate transactionTemplate;
    @InjectMocks ProductServiceImpl service;

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void runTransactionCallback() {
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Product sampleProduct() {
        return Product.builder().id(ID).title("iPhone 15").slug("iphone-15").sku("IP15-001")
                .priceUnit(new BigDecimal("999.00")).quantity(10).status(ProductStatus.ACTIVE)
                .avgRating(new BigDecimal("0.00")).ratingCount(0).build();
    }

    @Test
    @DisplayName("a product with live stock reservations is not deleted")
    void aProductWithLiveStockReservationsIsNotDeleted() {
        when(repo.findById(ID)).thenReturn(Optional.of(sampleProduct()));
        when(inventoryReservationClient.activeReservationCount(ID)).thenReturn(OptionalLong.of(2));

        assertThatThrownBy(() -> service.delete(ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("PRD-2010");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });

        verify(repo, never()).save(any());
        verify(publisher, never()).publishDeleted(any());
    }

    @Test
    @DisplayName("an unavailable inventory-service fails the delete closed")
    void anUnavailableInventoryServiceFailsTheDeleteClosed() {
        when(repo.findById(ID)).thenReturn(Optional.of(sampleProduct()));
        when(inventoryReservationClient.activeReservationCount(ID)).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> service.delete(ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("PRD-2011");
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                });

        verify(repo, never()).save(any());
        verify(publisher, never()).publishDeleted(any());
    }

    @Test
    @DisplayName("a product without live stock reservations is deleted")
    void aProductWithoutLiveStockReservationsIsDeleted() {
        Product product = sampleProduct();
        when(repo.findById(ID)).thenReturn(Optional.of(product));
        when(inventoryReservationClient.activeReservationCount(ID)).thenReturn(OptionalLong.of(0));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("admin@example.com"));

        service.delete(ID);

        verify(repo).save(product);
        verify(publisher).publishDeleted(product);
    }

    @Test
    @DisplayName("an unknown product is rejected before inventory-service is asked")
    void anUnknownProductIsRejectedBeforeTheRemoteCall() {
        when(repo.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("PRD-2001"));

        verify(inventoryReservationClient, never()).activeReservationCount(any());
        verify(repo, never()).save(any());
    }
}
