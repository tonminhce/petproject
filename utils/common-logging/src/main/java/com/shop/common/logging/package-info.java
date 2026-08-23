/**
 * AOP-driven performance and trace logging building blocks.
 * <p>
 * Provides {@link com.shop.common.logging.LogPerformance} / {@link com.shop.common.logging.Loggable}
 * annotations plus an {@link com.shop.common.logging.aspect.LoggerAspect} that wires them
 * up automatically through {@link com.shop.common.logging.config.LoggingAutoConfiguration}.
 *
 * <p>Activation: AspectJ must be on the classpath
 * (the module pulls {@code spring-boot-starter-aop} for that) and
 * {@code shop.web.logging.performance.enabled} must not be explicitly set to {@code false}.
 */
package com.shop.common.logging;
