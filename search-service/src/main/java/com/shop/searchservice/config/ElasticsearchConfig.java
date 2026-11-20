package com.shop.searchservice.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfig {

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(30);
    private static final AuthScope ANY_SCOPE = new AuthScope(null, -1);

    private final SearchProperties properties;

    @Bean(destroyMethod = "close")
    public Rest5Client elasticsearchRestClient() {
        Rest5ClientBuilder builder = Rest5Client.builder(targetHost())
            .setRequestConfigCallback(rc -> rc
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setResponseTimeout(RESPONSE_TIMEOUT));

        if (properties.username() != null && !properties.username().isBlank()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(ANY_SCOPE, new UsernamePasswordCredentials(
                properties.username(), passwordChars()));
            builder.setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(credentials));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(Rest5Client elasticsearchRestClient) {
        return new ElasticsearchClient(
            new Rest5ClientTransport(elasticsearchRestClient, new JacksonJsonpMapper()));
    }

    private HttpHost targetHost() {
        String url = properties.url();
        URI uri = URI.create(url.contains("://") ? url : "http://" + url);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
        return new HttpHost(scheme, uri.getHost(), port);
    }

    private char[] passwordChars() {
        String password = properties.password();
        return password == null ? new char[0] : password.toCharArray();
    }
}
