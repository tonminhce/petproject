package com.shop.productservice.controller;

import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.AuditEventWriter;
import com.shop.common.logging.config.AuditAutoConfiguration;
import com.shop.common.security.config.SecurityAutoConfiguration;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit spot matrix (spec D6, fix round 1): the ADMIN-gated product mutation
 * on the backoffice path ({@code ApiPaths.BACKOFFICE_PRODUCTS}) exercised
 * through the {@code AuditAspect} — audit coverage follows the authorization
 * level (ADMIN), not the URL prefix. C13 fix: the mutations live on
 * {@code BackofficeProductController} since the storefront write endpoints
 * were removed.
 */
@WebMvcTest(value = BackofficeProductController.class,
    properties = {"shop.security.issuer-uri=http://localhost:9999/realms/test"})
@AutoConfigureMockMvc
@Import({ApiExceptionHandler.class, SecurityAutoConfiguration.class,
        AuditAutoConfiguration.class, AopAutoConfiguration.class})
class ProductControllerAuditTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestWebSecurityConfig {
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductService productService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AuditEventWriter auditEventWriter;

    @Test
    void delete_emitsAuditLineWithAnnotatedActionAndResourceId() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000006001");

        mockMvc.perform(delete("/api/v1/backoffice/products/{id}", productId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000006003"))
                    .authorities(createAuthorityList("ROLE_ADMIN"))))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("product.delete");
        assertThat(event.resourceType()).isEqualTo("product");
        assertThat(event.resourceId()).isEqualTo(productId.toString());
        assertThat(event.actorType()).isEqualTo("user");
        assertThat(event.outcome()).isEqualTo("success");
        assertThat(event.toJson()).contains(
            "\"action\":\"product.delete\"",
            "\"resourceType\":\"product\"",
            "\"resourceId\":\"" + productId + "\"");
    }

    @Test
    void update_emitsAuditLineWithAnnotatedActionAndResourceId() throws Exception {
        // H-2 guard: adding the clearMediaId flag must not disturb the PUT
        // audit wiring — the annotated action/resourceId stay unchanged.
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000006002");
        when(productService.update(eq(productId), any())).thenReturn(null);

        mockMvc.perform(put("/api/v1/backoffice/products/{id}", productId)
                .with(jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000006003"))
                    .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clearMediaId\": true}"))
            .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventWriter).write(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("product.update");
        assertThat(event.resourceType()).isEqualTo("product");
        assertThat(event.resourceId()).isEqualTo(productId.toString());
        assertThat(event.outcome()).isEqualTo("success");
    }
}
