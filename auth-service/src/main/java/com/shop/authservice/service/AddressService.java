package com.shop.authservice.service;

import com.shop.authservice.dto.request.AddressCreateRequest;
import com.shop.authservice.dto.request.AddressUpdateRequest;
import com.shop.authservice.dto.response.AddressResponse;

import java.util.List;
import java.util.UUID;

public interface AddressService {

    List<AddressResponse> getUserAddresses(UUID userId);

    AddressResponse createAddress(UUID userId, AddressCreateRequest request);

    AddressResponse updateAddress(UUID userId, UUID addressId, AddressUpdateRequest request);

    void deleteAddress(UUID userId, UUID addressId);

    AddressResponse setDefaultAddress(UUID userId, UUID addressId);
}
