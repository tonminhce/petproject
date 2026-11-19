/**
 * Pure-Java contracts shared by every microservice.
 * <p>
 * Contains cross-cutting concerns that must work even when Spring is not on the
 * classpath: API path constants, MDC keys, the canonical error catalog, the
 * {@link com.shop.common.core.viewmodel.ApiResponse} envelope, and lightweight
 * utility helpers. Anything that requires the Spring runtime should live in
 * {@code common-spring} instead.
 */
package com.shop.common.core;
