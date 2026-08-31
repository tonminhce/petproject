package com.shop.searchservice.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class IndexProvisioner implements ApplicationRunner {

    public static final String INDEX_TEMPLATE = "products-template";
    public static final String INDEX = "products-v1";
    public static final String ALIAS = "products";
    /** All reindex generation indices match this pattern. */
    public static final String INDEX_PATTERN = "products-v*";

    private final ElasticsearchClient client;

    @Override
    public void run(ApplicationArguments args) {
        try {
            provision();
        } catch (Exception e) {
            log.error("Search index provisioning failed — search stays degraded until Elasticsearch is reachable", e);
        }
    }

    void provision() throws IOException {
        if (!client.indices().existsIndexTemplate(t -> t.name(INDEX_TEMPLATE)).value()) {
            client.indices().putIndexTemplate(t -> t
                .name(INDEX_TEMPLATE)
                .indexPatterns("products-v*")
                .template(ti -> ti
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m.properties(productMapping()))));
            log.info("Created index template {}", INDEX_TEMPLATE);
        }

        if (!client.indices().exists(e -> e.index(INDEX)).value()) {
            client.indices().create(c -> c.index(INDEX));
            log.info("Created index {}", INDEX);
        }

        if (!client.indices().existsAlias(a -> a.name(ALIAS)).value()) {
            client.indices().putAlias(a -> a.index(INDEX).name(ALIAS));
            log.info("Attached alias {} -> {}", ALIAS, INDEX);
        }
    }

    private static Map<String, Property> productMapping() {
        Map<String, Property> fields = new LinkedHashMap<>();
        fields.put("id", Property.of(p -> p.keyword(k -> k)));
        fields.put("title", Property.of(p -> p.text(t -> t.fields("keyword",
            Property.of(k -> k.keyword(kw -> kw.ignoreAbove(256)))))));
        fields.put("description", Property.of(p -> p.text(t -> t)));
        fields.put("brandName", Property.of(p -> p.text(t -> t.fields("keyword",
            Property.of(k -> k.keyword(kw -> kw.ignoreAbove(256)))))));
        fields.put("brandId", Property.of(p -> p.keyword(k -> k)));
        fields.put("categoryId", Property.of(p -> p.keyword(k -> k)));
        fields.put("categoryName", Property.of(p -> p.text(t -> t)));
        fields.put("slug", Property.of(p -> p.keyword(k -> k)));
        fields.put("imageUrl", Property.of(p -> p.keyword(k -> k.index(false))));
        fields.put("price", Property.of(p -> p.double_(d -> d)));
        fields.put("avgRating", Property.of(p -> p.halfFloat(h -> h)));
        fields.put("ratingCount", Property.of(p -> p.integer(i -> i)));
        fields.put("status", Property.of(p -> p.keyword(k -> k)));
        fields.put("updatedAt", Property.of(p -> p.date(d -> d)));
        return fields;
    }
}
