package com.shop.authservice.service.impls;

import com.shop.authservice.dto.request.AddressCreateRequest;
import com.shop.authservice.dto.request.AddressUpdateRequest;
import com.shop.authservice.dto.response.AddressResponse;
import com.shop.authservice.entity.UserAddress;
import com.shop.authservice.repository.UserAddressRepository;
import com.shop.authservice.service.AddressService;
import com.shop.common.core.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final UserAddressRepository addressRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> getUserAddresses(UUID userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public AddressResponse createAddress(UUID userId, AddressCreateRequest request) {
        List<UserAddress> existing = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);
        boolean shouldBeDefault = request.isDefault() || existing.isEmpty();

        if (shouldBeDefault && !existing.isEmpty()) {
            addressRepository.resetDefaultByUserId(userId);
        }

        Instant now = Instant.now();
        UserAddress address = UserAddress.builder()
                .userId(userId)
                .recipientName(request.recipientName().trim())
                .phoneNumber(request.phoneNumber().trim())
                .province(request.province().trim())
                .district(request.district().trim())
                .ward(request.ward().trim())
                .detailAddress(request.detailAddress().trim())
                .isDefault(shouldBeDefault)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return toResponse(addressRepository.save(address));
    }

    @Override
    @Transactional
    public AddressResponse updateAddress(UUID userId, UUID addressId, AddressUpdateRequest request) {
        UserAddress address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> BusinessException.notFound("address.not.found", addressId));

        if (request.isDefault() && !address.isDefault()) {
            addressRepository.resetDefaultByUserId(userId);
            address.setDefault(true);
        } else if (!request.isDefault() && address.isDefault()) {
            address.setDefault(false);
        }

        address.setRecipientName(request.recipientName().trim());
        address.setPhoneNumber(request.phoneNumber().trim());
        address.setProvince(request.province().trim());
        address.setDistrict(request.district().trim());
        address.setWard(request.ward().trim());
        address.setDetailAddress(request.detailAddress().trim());
        address.setUpdatedAt(Instant.now());

        return toResponse(addressRepository.save(address));
    }

    @Override
    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        UserAddress address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> BusinessException.notFound("address.not.found", addressId));

        addressRepository.delete(address);

        if (address.isDefault()) {
            List<UserAddress> remaining = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);
            if (!remaining.isEmpty()) {
                UserAddress nextDefault = remaining.getFirst();
                nextDefault.setDefault(true);
                nextDefault.setUpdatedAt(Instant.now());
                addressRepository.save(nextDefault);
            }
        }
    }

    @Override
    @Transactional
    public AddressResponse setDefaultAddress(UUID userId, UUID addressId) {
        UserAddress address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> BusinessException.notFound("address.not.found", addressId));

        addressRepository.resetDefaultByUserId(userId);
        address.setDefault(true);
        address.setUpdatedAt(Instant.now());

        return toResponse(addressRepository.save(address));
    }

    private AddressResponse toResponse(UserAddress address) {
        return new AddressResponse(
                address.getId(),
                address.getUserId(),
                address.getRecipientName(),
                address.getPhoneNumber(),
                address.getProvince(),
                address.getDistrict(),
                address.getWard(),
                address.getDetailAddress(),
                address.isDefault(),
                address.getCreatedAt(),
                address.getUpdatedAt()
        );
    }
}
