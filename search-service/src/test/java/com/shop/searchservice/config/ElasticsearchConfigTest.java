package com.shop.searchservice.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchConfigTest {

    private static final Path REPO_ROOT = repoRoot();

    private static final Pattern MANAGED_VERSION =
        Pattern.compile("<elasticsearch-client\\.version>([^<]+)</elasticsearch-client\\.version>");
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern ARTIFACT_ID =
        Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern VERSION_ELEMENT =
        Pattern.compile("<version>([^<]*)</version>");
    private static final Pattern RESOLVED_ARTIFACT =
        Pattern.compile("elasticsearch-java-([0-9][^/]*?)\\.jar");

    @Test
    @DisplayName("the search client is built on the managed 9.x transport")
    void theClientIsBuiltOnTheManagedTransport() throws IOException {
        ElasticsearchConfig config =
            new ElasticsearchConfig(new SearchProperties("http://localhost:9200", null, null));
        Rest5Client lowLevelClient = config.elasticsearchRestClient();
        try {
            ElasticsearchClient client = config.elasticsearchClient(lowLevelClient);
            assertThat(client._transport()).isInstanceOf(Rest5ClientTransport.class);
        } finally {
            lowLevelClient.close();
        }
    }

    @Test
    @DisplayName("the transport-client-era low level REST client is absent from the classpath")
    void theLegacyLowLevelRestClientIsAbsent() {
        assertThatThrownBy(() -> Class.forName("org.elasticsearch.client.RestClient"))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("the resolved client artifact is the version the root pom manages")
    void theResolvedClientIsTheManagedVersion() throws IOException {
        String managed = managedClientVersion();
        String resolved = resolvedClientArtifactVersion();

        assertThat(managed).isNotBlank();
        assertThat(resolved)
            .as("the fleet must resolve one Elasticsearch client version")
            .isEqualTo(managed);
    }

    @Test
    @DisplayName("no module pom pins its own Elasticsearch client version")
    void noModulePinsTheElasticsearchClientVersion() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path pom : modulePoms()) {
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            Matcher dependencies = DEPENDENCY_BLOCK.matcher(content);
            while (dependencies.find()) {
                String block = dependencies.group(1);
                String artifact = artifactId(block);
                if ("elasticsearch-java".equals(artifact)) {
                    Matcher version = VERSION_ELEMENT.matcher(block);
                    if (version.find()) {
                        offenders.add(REPO_ROOT.relativize(pom) + " pins " + artifact + " " + version.group(1));
                    }
                }
                if ("elasticsearch-rest-client".equals(artifact)) {
                    offenders.add(REPO_ROOT.relativize(pom) + " declares the legacy " + artifact);
                }
            }
        }

        assertThat(offenders)
            .as("every module must inherit the managed Elasticsearch client version")
            .isEmpty();
    }

    private static String managedClientVersion() throws IOException {
        String rootPom = Files.readString(REPO_ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher matcher = MANAGED_VERSION.matcher(rootPom);
        if (!matcher.find()) {
            throw new IllegalStateException("the root pom does not manage the Elasticsearch client version");
        }
        return matcher.group(1).trim();
    }

    private static String resolvedClientArtifactVersion() {
        String location = ElasticsearchClient.class.getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .getPath();
        Matcher matcher = RESOLVED_ARTIFACT.matcher(location);
        if (!matcher.find()) {
            throw new IllegalStateException("the Elasticsearch client artifact cannot be located: " + location);
        }
        return matcher.group(1);
    }

    private static String artifactId(String dependencyBlock) {
        Matcher matcher = ARTIFACT_ID.matcher(dependencyBlock);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private static List<Path> modulePoms() throws IOException {
        try (Stream<Path> paths = Files.walk(REPO_ROOT)) {
            return paths
                .filter(path -> path.getFileName().toString().equals("pom.xml"))
                .filter(path -> !path.equals(REPO_ROOT.resolve("pom.xml")))
                .filter(path -> !path.toString().contains("/target/"))
                .toList();
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("utils"))
                    && Files.isDirectory(current.resolve("search-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("the repository root was not found");
    }
}
