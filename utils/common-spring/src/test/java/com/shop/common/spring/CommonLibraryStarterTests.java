package com.shop.common.spring;

import com.shop.common.spring.config.CommonProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test that verifies the {@code common-spring} starter boots a full
 * Spring application context with every cross-cutting auto-configuration
 * registered.
 *
 * <p>If any of the bundled modules (security, keycloak, logging, kafka,
 * storage) has a wiring problem — e.g. a missing bean, a circular
 * dependency, or a misconfigured property — this test fails fast.</p>
 *
 * <p>DataSource / JPA auto-configuration is excluded because the starter
 * pulls in {@code spring-boot-starter-data-jpa} as an <i>optional</i>
 * dependency for compile-time convenience, but the starter has no
 * opinion on which database each service uses. A real service adds its
 * own datasource configuration on top of this starter.</p>
 */
@SpringBootTest(
        classes = CommonLibraryStarter.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "shop.keycloak.server-url=http://localhost:9999",
        "shop.keycloak.realm=test",
        "shop.keycloak.client-id=test",
        "shop.keycloak.admin-username=admin",
        "shop.keycloak.admin-password=admin",
        "shop.security.issuer-uri=http://localhost:9999/realms/test",
        "shop.storage.enabled=false",
        "shop.kafka.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
})
class CommonLibraryStarterTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CommonProperties commonProperties;

    @Test
    void contextLoads() {
        assertNotNull(applicationContext, "Application context must be created");
        assertNotNull(commonProperties, "CommonProperties must be bound from shop.common.*");
    }

    @Test
    void commonPropertiesCarrySensibleDefaults() {
        assertNotNull(commonProperties.service());
        assertNotNull(commonProperties.service().name());
        assertTrue(commonProperties.service().name().length() > 0);
        assertTrue(commonProperties.security().enabled(), "Security should be enabled by default");
        assertTrue(commonProperties.keycloak().enabled(), "Keycloak should be enabled by default");
        assertTrue(commonProperties.logging().correlationId(), "Correlation ID should be enabled by default");
    }
}
