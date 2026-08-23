package com.shop.common.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Reference Spring Boot application for the common library starter.
 *
 * <p>This class exists for two purposes:
 * <ol>
 *   <li>Provide a runnable entry point so the starter jar can be launched
 *       standalone for smoke-testing and local experimentation.</li>
 *   <li>Act as a discoverable template that microservices can copy when
 *       creating their own {@code @SpringBootApplication} class.</li>
 * </ol>
 *
 * <p><b>Microservices should NOT extend or reuse this class directly.</b>
 * Each service must declare its own {@code @SpringBootApplication} class
 * in its own package so component scanning is scoped to that service.</p>
 *
 * <p>The accompanying {@code application.yml} in this module provides the
 * platform-wide defaults (server, actuator, security, keycloak, ...)
 * that every service inherits when it depends on
 * {@code com.shop.microservices:common-spring}.</p>
 *
 * @see com.shop.common.spring.config.CommonProperties
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CommonLibraryStarter {

    public static void main(final String[] args) {
        SpringApplication.run(CommonLibraryStarter.class, args);
    }
}
