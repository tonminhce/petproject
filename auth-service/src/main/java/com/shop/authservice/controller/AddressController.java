package com.shop.authservice.controller;

import com.shop.authservice.dto.request.AddressCreateRequest;
import com.shop.authservice.dto.request.AddressUpdateRequest;
import com.shop.authservice.dto.response.AddressResponse;
import com.shop.authservice.service.AddressService;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/addresses")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ApiResponse<List<AddressResponse>> getMyAddresses() {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(addressService.getUserAddresses(userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AddressResponse> createAddress(@Valid @RequestBody AddressCreateRequest request) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(addressService.createAddress(userId, request), "Address created successfully");
    }

    @PutMapping("/{id}")
    public ApiResponse<AddressResponse> updateAddress(
            @PathVariable UUID id,
            @Valid @RequestBody AddressUpdateRequest request) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(addressService.updateAddress(userId, id, request), "Address updated successfully");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@PathVariable UUID id) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        addressService.deleteAddress(userId, id);
    }

    @PutMapping("/{id}/default")
    public ApiResponse<AddressResponse> setDefaultAddress(@PathVariable UUID id) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(addressService.setDefaultAddress(userId, id), "Default address updated successfully");
    }
}
