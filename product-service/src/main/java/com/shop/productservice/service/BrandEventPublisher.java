package com.shop.productservice.service;

import com.shop.productservice.entity.Brand;

public interface BrandEventPublisher {

    void publishCreated(Brand brand);

    void publishUpdated(Brand brand);

    void publishDeleted(Brand brand);
}
