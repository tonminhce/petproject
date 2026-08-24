package com.shop.common.spring.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

public interface EntityCreateUpdateMapper<M, V, R> {
    M toModel(V vm);
    V toVm(M Model);
    R toModelResponse(M model);

    // null fields skip to support PATCH
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void partialUpdate(@MappingTarget M model, V vm);
}