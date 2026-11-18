package com.shop.mediaservice.service;

import java.util.UUID;

public interface MediaLifecycleService {

    void softDelete(UUID mediaId);
}
