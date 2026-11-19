package com.shop.common.spring.deploy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Prometheus rule that names a metric nobody publishes never fires.
 *
 * <p>The alert file is the only place where the platform states what an
 * operator is supposed to be told about. A metric name that does not exist in
 * any service turns the rule into a silent no-op: the backlog grows and
 * nothing is sent to anybody.</p>
 */
class PrometheusAlertMetricContractTest {

    private static final Pattern EXPR = Pattern.compile("(?m)^\\s*expr:\\s*(.+)$");

    private static final Pattern METRIC_WITH_LABELS = Pattern.compile(
            "(?<![a-zA-Z0-9_:.])([a-z][a-zA-Z0-9_]*)\\s*\\{");

    private static final Pattern METRIC_BARE = Pattern.compile(
            "(?<![a-zA-Z0-9_:.])([a-z][a-zA-Z0-9_]*)\\s*(==|!=|<=|>=|<|>)");

    private static final Set<String> KEYWORDS = Set.of(
            "and", "or", "by", "without", "on", "ignoring", "group_left", "group_right",
            "sum", "rate", "irate", "increase", "count", "max", "min", "avg", "abs",
            "histogram_quantile", "le", "offset", "bool", "up", "vector", "scalar",
            "job", "namespace", "application", "instance", "pod", "status");

    private static final Set<String> INFRASTRUCTURE_METRICS = Set.of(
            "up",
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_bucket",
            "http_server_requests_seconds_sum",
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "hikaricp_connections_active",
            "hikaricp_connections_max",
            "hikaricp_connections_pending");

    private static final Pattern METER_NAME = Pattern.compile(
            "\"(?<name>[a-zA-Z][a-zA-Z0-9_.-]*)\"");

    @Test
    @DisplayName("every metric named by an alert rule is published by a service or by the platform")
    void everyMetricNamedByAnAlertRuleIsPublished() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path alerts = repoRoot.resolve("k8s/prometheus-alerts.yaml");
        Assumptions.assumeTrue(Files.exists(alerts), "k8s/prometheus-alerts.yaml not found - contract not checked");

        Set<String> published = publishedMeters(repoRoot);
        assertThat(published)
                .as("the scan must actually find the meters of the services")
                .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (String expression : expressions(Files.readString(alerts, StandardCharsets.UTF_8))) {
            for (String metric : metricsIn(expression)) {
                if (INFRASTRUCTURE_METRICS.contains(metric)) {
                    continue;
                }
                if (!published.contains(metric)) {
                    offenders.add(metric + " -> " + expression);
                }
            }
        }

        assertThat(offenders)
                .as("an alert that names a metric nobody publishes can never fire")
                .isEmpty();
    }

    @Test
    @DisplayName("the outbox backlog alert watches every service that publishes an outbox gauge")
    void theOutboxBacklogAlertWatchesEveryServiceThatPublishesAGauge() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path alerts = repoRoot.resolve("k8s/prometheus-alerts.yaml");
        Assumptions.assumeTrue(Files.exists(alerts), "k8s/prometheus-alerts.yaml not found - contract not checked");

        Set<String> published = publishedMeters(repoRoot);
        Set<String> outboxGauges = new LinkedHashSet<>();
        for (String meter : published) {
            if (meter.endsWith("_outbox_pending_count")) {
                outboxGauges.add(meter);
            }
        }
        assertThat(outboxGauges)
                .as("the services must publish an outbox backlog gauge")
                .isNotEmpty();

        String content = Files.readString(alerts, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String gauge : outboxGauges) {
            if (!content.contains(gauge)) {
                missing.add(gauge);
            }
        }

        assertThat(missing)
                .as("a published outbox gauge that no alert watches leaves its backlog unobserved")
                .isEmpty();
    }

    private static List<String> expressions(String content) {
        List<String> expressions = new ArrayList<>();
        Matcher matcher = EXPR.matcher(content);
        while (matcher.find()) {
            expressions.add(matcher.group(1).trim());
        }
        return expressions;
    }

    private static Set<String> metricsIn(String expression) {
        Set<String> metrics = new LinkedHashSet<>();
        Matcher withLabels = METRIC_WITH_LABELS.matcher(expression);
        while (withLabels.find()) {
            String candidate = withLabels.group(1);
            if (!KEYWORDS.contains(candidate)) {
                metrics.add(candidate);
            }
        }
        Matcher bare = METRIC_BARE.matcher(expression);
        while (bare.find()) {
            String candidate = bare.group(1);
            if (!KEYWORDS.contains(candidate)) {
                metrics.add(candidate);
            }
        }
        return metrics;
    }

    private static Set<String> publishedMeters(Path repoRoot) throws IOException {
        Set<String> meters = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(repoRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!file.toString().contains("src" + java.io.File.separator + "main")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = METER_NAME.matcher(source);
                while (matcher.find()) {
                    String name = matcher.group("name");
                    if (name.indexOf('.') < 0) {
                        continue;
                    }
                    meters.add(name.replace('.', '_'));
                }
            }
        }
        return meters;
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
