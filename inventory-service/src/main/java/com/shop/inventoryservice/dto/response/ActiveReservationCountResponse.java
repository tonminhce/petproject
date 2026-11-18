package com.shop.inventoryservice.dto.response;

import java.util.UUID;

public record ActiveReservationCountResponse(UUID productId, long activeReservationCount) {
}
