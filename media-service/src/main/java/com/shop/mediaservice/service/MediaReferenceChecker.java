package com.shop.mediaservice.service;

import java.util.UUID;

public interface MediaReferenceChecker {

    boolean isReferenced(UUID mediaId);
}
