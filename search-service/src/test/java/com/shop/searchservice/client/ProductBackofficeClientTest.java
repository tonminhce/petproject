package com.shop.searchservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.config.ShopServicesProperties;
import com.shop.searchservice.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ProductBackofficeClientTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Mock
    private ServiceTokenProvider serviceTokenProvider;

    private MockRestServiceServer server;
    private ProductBackofficeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://product.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ShopServicesProperties props = new ShopServicesProperties(
            new ShopServicesProperties.Service("http://product.test", 3000), null);
        client = new ProductBackofficeClient(props, builder.build(), serviceTokenProvider);
    }

    private void stubToken() {
        lenient().when(serviceTokenProvider.getToken()).thenReturn("test-token");
    }

    private static String snapshotJson(UUID id, String title) {
        return """
            {"id":"%s","title":"%s","slug":"%s","description":"D","priceUnit":110.00,
             "status":"ACTIVE","imageUrl":"http://img/x.png","avgRating":4.5,"ratingCount":2,
             "categoryId":"22222222-2222-2222-2222-222222222222","categoryTitle":"Phones",
             "brandId":"11111111-1111-1111-1111-111111111111","brandName":"Apple",
             "updatedAt":"2026-08-31T10:15:30Z","sku":"IGNORED-FIELD","quantity":9}
            """.formatted(id, title, title.toLowerCase()).replace("\n", " ");
    }

    private static String pageJson(int page, int totalPages, boolean last, String... entries) {
        return """
            {"success":true,"code":"OK","data":{"content":[%s],"page":%d,"size":200,
             "totalElements":%d,"totalPages":%d,"first":%s,"last":%s}}
            """.formatted(String.join(",", entries), page, entries.length, totalPages,
                page == 0, last).replace("\n", " ");
    }

    @Test
    void returnsMappedSnapshots() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .andRespond(withSuccess(pageJson(0, 1, true, snapshotJson(ID, "iPhone 15")),
                MediaType.APPLICATION_JSON));

        PageResponse<ProductBackofficeClient.ProductSnapshot> page = client.fetchPage(0, 200);

        assertThat(page.content()).hasSize(1);
        ProductBackofficeClient.ProductSnapshot snapshot = page.content().get(0);
        assertThat(snapshot.id()).isEqualTo(ID);
        assertThat(snapshot.title()).isEqualTo("iPhone 15");
        assertThat(snapshot.priceUnit()).isEqualByComparingTo("110.00");
        assertThat(snapshot.categoryTitle()).isEqualTo("Phones");
        assertThat(snapshot.brandName()).isEqualTo("Apple");
        assertThat(snapshot.status()).isEqualTo("ACTIVE");
        assertThat(snapshot.updatedAt()).isEqualTo("2026-08-31T10:15:30Z");
        // §4(5): unknown wire fields tolerated
        assertThat(snapshot.ratingCount()).isEqualTo(2);
        server.verify();
    }

    @Test
    void fetchesRequestedPageWithSizeAndActiveFilter() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=3&size=200&status=ACTIVE"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(pageJson(3, 4, false), MediaType.APPLICATION_JSON));

        PageResponse<ProductBackofficeClient.ProductSnapshot> page = client.fetchPage(3, 200);

        assertThat(page.content()).isEmpty();
        server.verify();
    }

    @Test
    void sendsBearerServiceToken() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess(pageJson(0, 1, true), MediaType.APPLICATION_JSON));

        client.fetchPage(0, 200);
        server.verify();
    }

    @Test
    void serverErrorFailsFastWithSrh12002() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchPage(0, 200))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_QUERY_FAILED.getCode()));
        server.verify();
    }

    @Test
    void connectionRefusedFailsFastWithSrh12002() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> client.fetchPage(0, 200))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_QUERY_FAILED.getCode()));
        server.verify();
    }

    @Test
    void nullDataFailsFastWithSrh12002() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .andRespond(withSuccess("{\"success\":true,\"code\":\"OK\",\"data\":null}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchPage(0, 200))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_QUERY_FAILED.getCode()));
        server.verify();
    }

    @Test
    void malformedBodyFailsFastWithSrh12002() {
        stubToken();
        server.expect(requestTo("http://product.test/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .andRespond(withSuccess("not-json{", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchPage(0, 200))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_QUERY_FAILED.getCode()));
        server.verify();
    }

    @Test
    void srh12002IsA503() {
        assertThat(ErrorCode.SEARCH_QUERY_FAILED.getHttpStatus().is5xxServerError()).isTrue();
    }
}
