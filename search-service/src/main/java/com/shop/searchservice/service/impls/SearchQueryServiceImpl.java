package com.shop.searchservice.service.impls;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.NumberRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchRequest;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.metrics.SearchMetrics;
import com.shop.searchservice.search.IndexProvisioner;
import com.shop.searchservice.service.SearchQueryService;
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
 * Query mechanics behind {@link SearchQueryService}: multi_match over
 * title^3/brandName^2/categoryName/description when {@code q} is present,
 * match_all browse otherwise, with keyword filters for brand/category, a
 * range filter for price, and an avgRating floor for {@code minRating}
 * (never-rated docs have no value and drop out naturally).
 *
 * <p>F3 sort branching: {@code _score} is meaningless on match_all, so an
 * empty-q browse defaults to NEWEST — including when the client explicitly
 * asks for relevance. A q-present query defaults to RELEVANCE, and an
 * explicit sort param always wins.</p>
 *
 * <p>ES failures surface as 503 SRH-12002 — never a raw exception or 500
 * (spec D6): transport failures (I/O, timeout, connection refused) arrive as
 * IOException, while error responses (e.g. {@code index_not_found_exception}
 * when the alias is missing in a degraded provisioning window — a state
 * {@code IndexProvisioner} explicitly tolerates) arrive as
 * ElasticsearchException.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchQueryServiceImpl implements SearchQueryService {

    private static final int SIZE_CAP = PageableConstant.MAX_PAGE_SIZE;

    private final ElasticsearchClient client;
    private final SearchMetrics metrics;

    @Override
    public PageResponse<ProductSearchResponse> search(SearchRequest request) {
        boolean queryPresent = request.q() != null && !request.q().isBlank();
        SortKey effectiveSort = resolveSort(request.sort(), queryPresent);
        int size = Math.min(request.size(), SIZE_CAP);
        long from = (long) request.page() * size;
        if (from + size > SearchRequest.MAX_RESULT_WINDOW) {
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }

        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (queryPresent) {
            bool.must(m -> m.multiMatch(mm -> mm
                .query(request.q())
                .type(TextQueryType.BestFields)
                .fields("title^3", "brandName^2", "categoryName", "description")
                .lenient(true)));
        } else {
            bool.must(m -> m.matchAll(ma -> ma));
        }
        if (request.brandId() != null) {
            bool.filter(f -> f.term(t -> t.field("brandId").value(request.brandId().toString())));
        }
        if (request.categoryId() != null) {
            bool.filter(f -> f.term(t -> t.field("categoryId").value(request.categoryId().toString())));
        }
        if (request.minPrice() != null || request.maxPrice() != null) {
            bool.filter(priceRange(request.minPrice(), request.maxPrice()));
        }
        if (request.minRating() != null) {
            bool.filter(f -> f.range(r -> r.number(new NumberRangeQuery.Builder()
                .field("avgRating")
                .gte(request.minRating().doubleValue())
                .build())));
        }

        SearchResponse<Map> response;
        try {
            response = client.search(s -> s
                .index(IndexProvisioner.ALIAS)
                .query(q -> q.bool(bool.build()))
                .from(Math.toIntExact(from))
                .size(size)
                .sort(sortOptions(effectiveSort)), Map.class);
        } catch (ElasticsearchException ex) {
            // ES error responses — e.g. index_not_found_exception on a missing
            // alias during the degraded provisioning window.
            log.warn("Search query failed — Elasticsearch error response", ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
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
            .map(SearchQueryServiceImpl::toResponse)
            .toList();
        return PageResponse.of(content, request.page(), size, total);
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
