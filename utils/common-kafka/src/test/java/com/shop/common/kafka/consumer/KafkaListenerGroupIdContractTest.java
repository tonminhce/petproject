package com.shop.common.kafka.consumer;

import org.junit.jupiter.api.Assumptions;
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

/**
 * Fleet contract: every {@code @KafkaListener} declares its consumer group id
 * explicitly.
 *
 * <p>A listener that inherits {@code shop.kafka.consumer.group-id} has no
 * identity of its own: the id is whatever the deployment configuration
 * happens to say, and two services configured with the same id would silently
 * COMPETE for the partitions of a shared topic, so half of the events would
 * never reach one of them. The id is also the offset key - a new id replays
 * the topic from the earliest offset - so it must be a deliberate, reviewable
 * value rather than an ambient one.</p>
 */
class KafkaListenerGroupIdContractTest {

    private static final Pattern LISTENER = Pattern.compile("@KafkaListener\\s*\\(");
    private static final Pattern GROUP_ID = Pattern.compile("groupId\\s*=\\s*\"([^\"]*)\"");

    @Test
    void everyKafkaListenerPinsItsConsumerGroupId() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        List<String> withoutGroupId = new ArrayList<>();
        int checked = 0;

        for (Path file : productionSources(repoRoot)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String annotation : listenerAnnotations(text)) {
                checked++;
                Matcher matcher = GROUP_ID.matcher(annotation);
                if (!matcher.find() || matcher.group(1).isBlank()) {
                    withoutGroupId.add(repoRoot.relativize(file).toString().replace('\\', '/')
                            + " -> @" + annotation.replaceAll("\\s+", " "));
                }
            }
        }

        assertThat(checked)
                .as("the scan must actually find the fleet's listeners")
                .isGreaterThan(0);
        assertThat(withoutGroupId)
                .as("every @KafkaListener must declare an explicit groupId")
                .isEmpty();
    }

    /** The annotation arguments of every @KafkaListener in the file. */
    private static List<String> listenerAnnotations(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = LISTENER.matcher(text);
        while (matcher.find()) {
            int depth = 1;
            int index = matcher.end();
            while (index < text.length() && depth > 0) {
                char c = text.charAt(index);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                index++;
            }
            found.add(text.substring(matcher.end(), Math.max(matcher.end(), index - 1)));
        }
        return found;
    }

    /** Every first-party production source of the reactor. */
    private static List<Path> productionSources(Path repoRoot) throws IOException {
        try (Stream<Path> modules = Files.list(repoRoot)) {
            return modules
                    .filter(module -> Files.isDirectory(module.resolve("src/main/java")))
                    .flatMap(module -> sources(module.resolve("src/main/java")))
                    .toList();
        }
    }

    private static Stream<Path> sources(Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    /** The reactor root: the directory that holds the modules and the parent pom. */
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
