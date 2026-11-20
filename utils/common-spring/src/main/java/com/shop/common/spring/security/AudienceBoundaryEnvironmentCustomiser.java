package com.shop.common.spring.security;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AudienceBoundaryEnvironmentCustomiser implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    public static final String SHARED_DEFAULT_CLIENT_IDS = "ecommerce-client";
    public static final String EXPECTED_CLIENT_IDS = "shop.security.expected-client-ids";
    public static final String EXPECTED_AUDIENCES = "shop.security.expected-audiences";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            throw new IllegalStateException("A configurable environment is required to resolve the security audience");
        }
        if (!configurableEnvironment.getProperty("shop.security.enabled", Boolean.class, Boolean.TRUE)) {
            return;
        }

        AudienceProperties audience = Binder.get(configurableEnvironment)
                .bind("shop.security.audience", AudienceProperties.class)
                .orElseGet(AudienceProperties::disabled);
        if (!audience.enabled()) {
            return;
        }

        AudienceBoundary boundary = AudienceBoundary.resolve(
                audience,
                configurableEnvironment.getProperty(EXPECTED_CLIENT_IDS),
                configurableEnvironment.getProperty(EXPECTED_AUDIENCES));

        if (!boundary.allowsAnyClient()) {
            throw new IllegalStateException(
                    EXPECTED_CLIENT_IDS + " must not be empty: declare the client ids allowed to call this service "
                            + "through shop.security.audience.allowed-client-ids or "
                            + "shop.security.audience.per-service-client-ids");
        }

        Map<String, Object> published = new LinkedHashMap<>();
        published.put(EXPECTED_CLIENT_IDS, String.join(",", boundary.allowedClientIds()));
        if (!boundary.allowedAudiences().isEmpty()) {
            published.put(EXPECTED_AUDIENCES, String.join(",", boundary.allowedAudiences()));
        }
        configurableEnvironment.getPropertySources()
                .addFirst(new MapPropertySource("shopSecurityAudience", published));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
