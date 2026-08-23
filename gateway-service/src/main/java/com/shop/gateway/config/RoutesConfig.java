package com.shop.gateway.config;

import com.shop.gateway.routing.ServiceRoute;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {

    @Bean
    public RouteLocator gatewayRoutes(final RouteLocatorBuilder builder) {
        final var routesBuilder = builder.routes();
        for (final var route : ServiceRoute.values()) {
            routesBuilder.route(route.id(), spec -> spec
                    .path(route.path())
                    .uri(route.uri()));
        }
        return routesBuilder.build();
    }
}
