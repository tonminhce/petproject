package com.shop.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressCreateRequest(
        @NotBlank(message = "Recipient name must not be blank")
        @Size(max = 150)
        String recipientName,

        @NotBlank(message = "Phone number must not be blank")
        @Size(max = 30)
        String phoneNumber,

        @NotBlank(message = "Province must not be blank")
        @Size(max = 100)
        String province,

        @NotBlank(message = "District must not be blank")
        @Size(max = 100)
        String district,

        @NotBlank(message = "Ward must not be blank")
        @Size(max = 100)
        String ward,

        @NotBlank(message = "Detail address must not be blank")
        @Size(max = 255)
        String detailAddress,

        boolean isDefault
) {
}
