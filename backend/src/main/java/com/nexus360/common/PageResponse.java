package com.nexus360.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static final int MAX_PAGE_SIZE = 100;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(Page<?> page, List<T> mapped) {
        return new PageResponse<>(mapped, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** Clamps client supplied paging to sane bounds. */
    public static Pageable pageable(Integer page, Integer size, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s, sort);
    }
}
