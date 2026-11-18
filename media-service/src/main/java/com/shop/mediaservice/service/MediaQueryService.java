package com.shop.mediaservice.service;

import java.net.URL;
import java.util.UUID;

public interface MediaQueryService {

    String DEFAULT_VARIANT = "display";
    String DEFAULT_FORMAT = "auto";

    URL resolve(UUID mediaId, String variant, String format);

    boolean exists(UUID mediaId);
}
