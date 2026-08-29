package com.shop.inventoryservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean ReservationService reservationService;

    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void findById_returns200WithApiResponse() throws Exception {
        InventoryResponse resp = new InventoryResponse(productId, 100, 0, Instant.now());
        when(inventoryService.findById(productId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.productId").value(productId.toString()));
    }

    @Test
    void create_returns200() throws Exception {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        InventoryResponse resp = new InventoryResponse(productId, 50, 0, Instant.now());
        when(inventoryService.create(any(InventoryUpsertRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/v1/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.availableQuantity").value(50));
    }

    @Test
    void reserve_returnsReservation() throws Exception {
        ReserveRequest req = new ReserveRequest(5, null);
        ReservationResponse resp = new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING, Instant.now().plusSeconds(900), null);
        when(reservationService.reserveWithRetry(eq(productId), any(ReserveRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()));
    }

    @Test
    void findById_throwsNotFound_returns404Envelope() throws Exception {
        when(inventoryService.findById(productId))
            .thenThrow(BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INV-3008"));
    }

    @Test
    void commit_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{reservationId}/commit", reservationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void release_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{reservationId}/release", reservationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
