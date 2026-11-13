package com.shop.inventoryservice.dto.response;

import java.util.UUID;

/**
 * The live stock reservations of a product (F-060).
 *
 * <p>Internal, SERVICE-role payload: product-service asks for it before it
 * soft-deletes a product, because the reservations table lives in the
 * inventory database and cannot be joined with the catalogue.</p>
 *
 * @param productId             the product the reservations belong to
 * @param activeReservationCount PENDING + COMMITTED reservations (0 = the
 *                              product can leave the catalogue)
 */
public record ActiveReservationCountResponse(UUID productId, long activeReservationCount) {
}
