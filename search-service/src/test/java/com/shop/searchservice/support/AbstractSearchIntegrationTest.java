package com.shop.searchservice.support;

import com.shop.common.spring.test.TestSecurityConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
public abstract class AbstractSearchIntegrationTest {

    @SuppressWarnings("resource")
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @SuppressWarnings("resource")
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.4.5"))
        .withEnv("discovery.type", "single-node")
        .withEnv("xpack.security.enabled", "false")
        .withEnv("xpack.ml.enabled", "false")
        .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
        .withStartupTimeout(Duration.ofMinutes(15));

    static {
        kafka.start();
        elasticsearch.start();
        awaitElasticsearchReady();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("elasticsearch.url", elasticsearch::getHttpHostAddress);
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
    }

    private static void awaitElasticsearchReady() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://" + elasticsearch.getHttpHostAddress()))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                sleep();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for Elasticsearch", e);
            }
            sleep();
        }
        throw new IllegalStateException("Elasticsearch did not serve the root endpoint in time");
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Elasticsearch", e);
        }
    }
}
