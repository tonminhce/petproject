package com.shop.searchservice.service;

import com.shop.searchservice.dto.response.ReindexResponse;

public interface ReindexService {

    ReindexResponse reindex(boolean dryRun);
}
