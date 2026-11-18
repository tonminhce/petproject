package com.shop.common.core.viewmodel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.function.Function;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static <T> PageResponse<T> of(List<T> content,
                                         int page,
                                         int size,
                                         long totalElements) {
        int safeSize = Math.max(size, 1);
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1
        );
    }

    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = content.stream().<R>map(mapper).toList();
        return new PageResponse<>(mapped, page, size, totalElements, totalPages, first, last);
    }
}
