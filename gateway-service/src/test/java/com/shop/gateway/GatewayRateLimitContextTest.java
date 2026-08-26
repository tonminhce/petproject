package com.shop.gateway;

import com.shop.gateway.ratelimit.RateLimitKeyResolver;
import com.shop.gateway.ratelimit.RateLimitProperties;
import com.shop.gateway.ratelimit.GlobalRateLimitFilter;
import com.shop.gateway.ratelimit.GlobalRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/realms/test/protocol/openid-connect/certs",
        "gateway.keycloak-issuer-uri=http://localhost:9999/realms/test",
        "gateway.cors-allowed-origin-patterns=http://localhost:3000"
})
class GatewayRateLimitContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    @Qualifier("gatewayRateLimiter")
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    @Qualifier("globalRateLimiter")
    private RedisRateLimiter globalRateLimiter;

    @Autowired
    private GlobalRateLimitFilter globalRateLimitFilter;

    @Autowired
    private GlobalRateLimitProperties globalRateLimitProperties;

    @Autowired
    private KeyResolver keyResolver;

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void rateLimitBeansAreAutoConfigured() {
        assertThat(applicationContext.getBean(RateLimitKeyResolver.class)).isSameAs(keyResolver);
        assertThat(redisRateLimiter).isNotNull();
        assertThat(globalRateLimiter).isNotNull();
        assertThat(globalRateLimitFilter.getOrder()).isEqualTo(Integer.MIN_VALUE);
        assertThat(rateLimitProperties.enabled()).isTrue();
        assertThat(globalRateLimitProperties.enabled()).isTrue();
    }

    @Test
    void everyBackendRouteHasRateLimiterFilter() {
        var routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).hasSize(15);
        assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).hasSize(1));
    }
}
