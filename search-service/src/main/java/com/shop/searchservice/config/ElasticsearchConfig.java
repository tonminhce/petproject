package com.shop.searchservice.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfig {

    private final SearchProperties properties;

    /**
     * Managed low-level client so Spring invokes {@link RestClient#close()} on
     * context shutdown, stopping the async I/O reactor thread (T1 review
     * finding 1 — an inline builder would leak it past shutdown).
     */
    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient() {
        RestClientBuilder builder = RestClient.builder(HttpHost.create(properties.url()))
            .setRequestConfigCallback(rc -> rc
                .setConnectTimeout(3000)
                .setSocketTimeout(10000));

        if (properties.username() != null && !properties.username().isBlank()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                properties.username(), properties.password()));
            builder.setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(credentials));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient elasticsearchRestClient) {
        return new ElasticsearchClient(
            new RestClientTransport(elasticsearchRestClient, new JacksonJsonpMapper()));
    }
}
