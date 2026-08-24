package com.shop.common.spring.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

public interface BaseMapper<M,V> {
    M toModel(V vm);
    V toVm(M toModel);

    // null fields skip to support PATCH
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void partialUpdate(@MappingTarget M model, V vm);
}
