package com.shop.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.NumberRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchParams;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.metrics.SearchMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Storefront product query over the {@code products} alias (spec D5):
 * multi_match over title^3/brandName^2/categoryName/description when {@code q}
 * is present, match_all browse otherwise, with keyword filters for
 * brand/category, a range filter for price, and an avgRating floor for
 * {@code minRating} (never-rated docs have no value and drop out naturally).
 *
 * <p>F3 sort branching: {@code _score} is meaningless on match_all, so an
 * empty-q browse defaults to NEWEST — including when the client explicitly
 * asks for relevance. A q-present query defaults to RELEVANCE, and an
 * explicit sort param always wins.</p>
 *
 * <p>ES transport failures (I/O, timeout, connection refused) surface as 503
 * SRH-12002 — never a raw exception or 500 (spec D6).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchQueryService {

    private static final int SIZE_CAP = 200;

    private final ElasticsearchClient client;
    private final SearchMetrics metrics;

    public PageResponse<ProductSearchResponse> search(SearchParams params) {
        boolean queryPresent = params.getQ() != null && !params.getQ().isBlank();
        SortKey effectiveSort = resolveSort(params.getSort(), queryPresent);
        int size = Math.min(params.getSize(), SIZE_CAP);
        int from = params.getPage() * size;

        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (queryPresent) {
            bool.must(m -> m.multiMatch(mm -> mm
                .query(params.getQ())
                .type(TextQueryType.BestFields)
                .fields("title^3", "brandName^2", "categoryName", "description")
                .lenient(true)));
        } else {
            bool.must(m -> m.matchAll(ma -> ma));
        }
        if (params.getBrandId() != null) {
            bool.filter(f -> f.term(t -> t.field("brandId").value(params.getBrandId().toString())));
        }
        if (params.getCategoryId() != null) {
            bool.filter(f -> f.term(t -> t.field("categoryId").value(params.getCategoryId().toString())));
        }
        if (params.getMinPrice() != null || params.getMaxPrice() != null) {
            bool.filter(priceRange(params.getMinPrice(), params.getMaxPrice()));
        }
        if (params.getMinRating() != null) {
            bool.filter(f -> f.range(r -> r.number(new NumberRangeQuery.Builder()
                .field("avgRating")
                .gte(params.getMinRating().doubleValue())
                .build())));
        }

        SearchResponse<Map> response;
        try {
            response = client.search(s -> s
                .index(ProductSearchService.INDEX_ALIAS)
                .query(q -> q.bool(bool.build()))
                .from(from)
                .size(size)
                .sort(sortOptions(effectiveSort)), Map.class);
        } catch (IOException ex) {
            // 8.15 transport failures (connect refused, timeout, socket reset)
            // all surface as IOException — TransportException included.
            log.warn("Search query failed — Elasticsearch unavailable", ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }

        metrics.recordQuery(effectiveSort.wire);

        long total = response.hits().total() != null
            ? response.hits().total().value()
            : response.hits().hits().size();
        List<ProductSearchResponse> content = response.hits().hits().stream()
            .map(SearchQueryService::toResponse)
            .toList();
        return PageResponse.of(content, params.getPage(), size, total);
    }

    private static Query priceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        NumberRangeQuery.Builder range = new NumberRangeQuery.Builder().field("price");
        if (minPrice != null) {
            range = range.gte(minPrice.doubleValue());
        }
        if (maxPrice != null) {
            range = range.lte(maxPrice.doubleValue());
        }
        NumberRangeQuery built = range.build();
        return Query.of(q -> q.range(r -> r.number(built)));
    }

    private static SortKey resolveSort(String sort, boolean queryPresent) {
        SortKey requested = sort == null ? null : SortKey.valueOf(sort.toUpperCase(Locale.ROOT));
        if (!queryPresent) {
            return requested == null || requested == SortKey.RELEVANCE ? SortKey.NEWEST : requested;
        }
        return requested == null ? SortKey.RELEVANCE : requested;
    }

    private static List<SortOptions> sortOptions(SortKey sort) {
        return switch (sort) {
            case RELEVANCE -> List.of(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
            case PRICE_ASC -> List.of(SortOptions.of(s -> s.field(f -> f.field("price").order(SortOrder.Asc))));
            case PRICE_DESC -> List.of(SortOptions.of(s -> s.field(f -> f.field("price").order(SortOrder.Desc))));
            case RATING_DESC -> List.of(SortOptions.of(s -> s.field(f -> f.field("avgRating")
                .order(SortOrder.Desc)
                .missing("_last"))));
            case NEWEST -> List.of(SortOptions.of(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Desc))));
        };
    }

    private static ProductSearchResponse toResponse(Hit<Map> hit) {
        Map<String, Object> doc = hit.source();
        return new ProductSearchResponse(
            UUID.fromString(str(doc, "id")),
            str(doc, "title"),
            str(doc, "brandName"),
            str(doc, "categoryName"),
            str(doc, "slug"),
            str(doc, "imageUrl"),
            decimal(doc, "price"),
            decimal(doc, "avgRating"),
            integer(doc, "ratingCount"));
    }

    private static String str(Map<String, Object> doc, String field) {
        Object value = doc == null ? null : doc.get(field);
        return value == null ? null : value.toString();
    }

    private static BigDecimal decimal(Map<String, Object> doc, String field) {
        Object value = doc == null ? null : doc.get(field);
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static Integer integer(Map<String, Object> doc, String field) {
        Object value = doc == null ? null : doc.get(field);
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private enum SortKey {
        RELEVANCE("relevance"),
        PRICE_ASC("price_asc"),
        PRICE_DESC("price_desc"),
        RATING_DESC("rating_desc"),
        NEWEST("newest");

        final String wire;

        SortKey(String wire) {
            this.wire = wire;
        }
    }
}
