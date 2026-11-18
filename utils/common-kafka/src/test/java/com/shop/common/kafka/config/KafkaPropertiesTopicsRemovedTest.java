package com.shop.common.kafka.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPropertiesTopicsRemovedTest {

    private static final List<String> LEGACY_TOPIC_NAMES = List.of(
            "order-events", "payment-events", "notification-events", "inventory-events");

    @Test
    @DisplayName("no legacy topic name survives in the production sources or the configuration")
    void noLegacyTopicNameSurvives() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        List<String> offenders = new ArrayList<>();
        for (Path file : configurationAndProductionSources(repoRoot)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String legacy : LEGACY_TOPIC_NAMES) {
                if (text.contains("\"" + legacy + "\"") || text.contains(":" + legacy)
                        || text.contains("=" + legacy)) {
                    offenders.add(repoRoot.relativize(file).toString().replace('\\', '/') + " -> " + legacy);
                }
            }
        }

        assertThat(offenders)
                .as("the dead topic-name configuration must not come back")
                .isEmpty();
    }

    private static List<Path> configurationAndProductionSources(Path repoRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.toList()) {
                if (Files.isDirectory(module.resolve("src/main/java"))) {
                    files.addAll(sources(module.resolve("src/main/java")).toList());
                }
                Path resources = module.resolve("src/main/resources");
                if (Files.isDirectory(resources)) {
                    files.addAll(configurations(resources).toList());
                }
            }
        }
        return files;
    }

    private static Stream<Path> sources(Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static Stream<Path> configurations(Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".yml")
                    || path.toString().endsWith(".yaml")
                    || path.toString().endsWith(".properties")).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("utils"))
                    && Files.isDirectory(current.resolve("order-service"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
