package com.shop.productservice.service;

import com.shop.productservice.entity.Product;

public interface ProductEventPublisher {

    void publishCreated(Product product);

    void publishUpdated(Product product);

    void publishDeleted(Product product);
}