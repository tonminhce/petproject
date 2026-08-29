package com.shop.productservice.service;

import com.shop.productservice.entity.Category;

public interface CategoryEventPublisher {

    void publishCreated(Category category);

    void publishUpdated(Category category);

    void publishDeleted(Category category);
}
