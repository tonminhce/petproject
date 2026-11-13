package com.shop.common.kafka.contract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fleet contract: every published topic has a declared consumption mode
 * (F-002).
 *
 * <p>Four topics were published by the outbox relay with no consumer anywhere
 * in the fleet - {@code shop.inventory.events.v1},
 * {@code shop.promotion.lifecycle.v1}, {@code shop.category.lifecycle.v1} and
 * {@code shop.brand.lifecycle.v1} - while the documentation implied
 * projections that did not exist. A published topic that nobody consumes is
 * not a defect in itself, but an UNDECLARED one is: the fleet then drifts
 * apart from its own documentation and every new reader has to guess whether
 * a missing consumer is intentional.</p>
 *
 * <p>This test therefore enforces that:</p>
 * <ul>
 *   <li>every topic bound to a {@code @KafkaListener} is declared in
 *       {@link KafkaTopicContract} - a new consumer cannot introduce an
 *       undeclared topic;</li>
 *   <li>every topic declared as {@code CONSUMED} really has at least one
 *       listener - a consumer cannot silently disappear;</li>
 *   <li>every topic declared as {@code FIRE_AND_FORGET} carries a rationale
 *       that explains why no in-fleet consumer exists - the gap has to be a
 *       reviewed decision, not an omission;</li>
 *   <li>every declared topic is really produced - the contract cannot drift
 *       away from the code.</li>
 * </ul>
 */
class KafkaTopicContractTest {

    private static final Pattern LISTENER = Pattern.compile("@KafkaListener\\s*\\(");
    private static final Pattern TOPICS_ATTRIBUTE = Pattern.compile("topics\\s*=\\s*(\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_.]*)");

    /** The topic literal behind each constant of the contract, by constant name. */
    private static final Map<String, String> CONSTANTS = constantsByName();

    @Test
    void everyPublishedTopicHasADeclaredConsumptionMode() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        Map<String, List<String>> listenersByTopic = listenerTopicsByTopic(repoRoot);

        // 1) every topic a listener binds to is declared
        List<String> undeclared = listenersByTopic.entrySet().stream()
                .filter(entry -> KafkaTopicContract.find(entry.getKey()) == null)
                .map(entry -> entry.getKey() + " (consumed by " + String.join(", ", entry.getValue()) + ")")
                .toList();
        assertThat(undeclared)
                .as("every consumed topic must be declared in the Kafka topic contract")
                .isEmpty();

        // 2) every declared CONSUMED topic really has a listener
        List<String> withoutConsumer = new ArrayList<>();
        for (KafkaTopicContract.Topic topic : KafkaTopicContract.TOPICS) {
            if (topic.mode() == KafkaTopicContract.Mode.CONSUMED
                    && !listenersByTopic.containsKey(topic.topic())) {
                withoutConsumer.add(topic.topic());
            }
        }
        assertThat(withoutConsumer)
                .as("a topic declared as CONSUMED must have at least one @KafkaListener")
                .isEmpty();

        // 3) every fire-and-forget topic explains itself
        List<String> withoutRationale = KafkaTopicContract.TOPICS.stream()
                .filter(topic -> topic.mode() == KafkaTopicContract.Mode.FIRE_AND_FORGET)
                .filter(topic -> topic.rationale() == null || topic.rationale().isBlank())
                .map(KafkaTopicContract.Topic::topic)
                .toList();
        assertThat(withoutRationale)
                .as("a fire-and-forget topic must state why no in-fleet consumer exists")
                .isEmpty();

        // 4) every declared topic is really produced by the module that owns it
        List<String> notProduced = new ArrayList<>();
        for (KafkaTopicContract.Topic topic : KafkaTopicContract.TOPICS) {
            Path producerModule = repoRoot.resolve(topic.producerModule());
            if (!Files.isDirectory(producerModule.resolve("src/main/java"))) {
                notProduced.add(topic.topic() + " -> unknown producer module " + topic.producerModule());
                continue;
            }
            boolean published = false;
            for (Path file : sources(producerModule.resolve("src/main/java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("\"" + topic.topic() + "\"")
                        || text.contains("KafkaTopicContract." + constantNameFor(topic.topic()))) {
                    published = true;
                    break;
                }
            }
            if (!published) {
                notProduced.add(topic.topic() + " -> " + topic.producerModule());
            }
        }
        assertThat(notProduced)
                .as("every declared topic must be produced by the module that owns it")
                .isEmpty();
    }

    @Test
    void theContractDeclaresEveryTopicTheFleetPublishes() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");

        // The producers publish through the contract constants, so a producer
        // that used its own literal would show up here as an undeclared topic.
        Set<String> declared = new HashSet<>();
        for (KafkaTopicContract.Topic topic : KafkaTopicContract.TOPICS) {
            declared.add(topic.topic());
        }

        List<String> publishedOutsideTheContract = new ArrayList<>();
        for (Path file : productionSources(repoRoot)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"(shop\\.[a-z0-9.-]+\\.v1|media\\.lifecycle\\.v1)\"").matcher(text);
            while (matcher.find()) {
                String literal = matcher.group(1);
                if (!declared.contains(literal)) {
                    publishedOutsideTheContract.add(repoRoot.relativize(file).toString() + " -> " + literal);
                }
            }
        }
        assertThat(publishedOutsideTheContract)
                .as("a topic literal that is not in the contract is an undeclared topic")
                .isEmpty();
    }

    /** The topic literal behind each constant name of the contract. */
    private static Map<String, String> constantsByName() {
        Map<String, String> constants = new HashMap<>();
        for (java.lang.reflect.Field field : KafkaTopicContract.class.getDeclaredFields()) {
            if (field.getType() == String.class
                    && java.lang.reflect.Modifier.isPublic(field.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    constants.put(field.getName(), (String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot read the topic contract constant " + field.getName(), e);
                }
            }
        }
        return Map.copyOf(constants);
    }

    /** The contract constant that carries the given topic literal. */
    private static String constantNameFor(String topic) {
        for (Map.Entry<String, String> entry : CONSTANTS.entrySet()) {
            if (entry.getValue().equals(topic)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("no contract constant carries the topic " + topic);
    }

    /** The topics every @KafkaListener of the fleet binds to, with the consuming files. */
    private static Map<String, List<String>> listenerTopicsByTopic(Path repoRoot) throws IOException {
        Map<String, List<String>> byTopic = new HashMap<>();
        for (Path file : productionSources(repoRoot)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String annotation : listenerAnnotations(text)) {
                Matcher matcher = TOPICS_ATTRIBUTE.matcher(annotation);
                while (matcher.find()) {
                    String topic = resolveTopic(matcher.group(1));
                    byTopic.computeIfAbsent(topic, key -> new ArrayList<>())
                            .add(repoRoot.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return byTopic;
    }

    /** A topic literal, or the literal behind a contract constant. */
    private static String resolveTopic(String value) {
        if (value.startsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        String constantName = value.substring(value.lastIndexOf('.') + 1);
        String topic = CONSTANTS.get(constantName);
        if (topic == null) {
            throw new IllegalStateException("the listener binds to an unknown topic constant: " + value);
        }
        return topic;
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
