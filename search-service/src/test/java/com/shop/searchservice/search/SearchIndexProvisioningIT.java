package com.shop.searchservice.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.shop.searchservice.support.AbstractSearchIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexProvisioningIT extends AbstractSearchIntegrationTest {

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private IndexProvisioner indexProvisioner;

    @Test
    void provisionsTemplateIndexAndAlias() throws Exception {
        assertThat(client.indices().exists(e -> e.index("products-v1")).value()).isTrue();

        var alias = client.indices().getAlias(g -> g.name("products"));
        assertThat(alias.aliases()).containsKey("products-v1");

        assertThat(client.indices().existsIndexTemplate(t -> t.name("products-template")).value()).isTrue();
    }

    @Test
    void provisioningReRunIsIdempotent() throws Exception {
        indexProvisioner.provision();

        var alias = client.indices().getAlias(g -> g.name("products"));
        assertThat(alias.aliases()).containsOnlyKeys("products-v1");
        assertThat(client.indices().exists(e -> e.index("products-v1")).value()).isTrue();
        assertThat(client.indices().existsIndexTemplate(t -> t.name("products-template")).value()).isTrue();
    }
}
