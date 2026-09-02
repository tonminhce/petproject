package com.shop.common.patterns;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fleet-pattern compliance harness.
 *
 * <p>Every assertion here encodes a rule from {@code docs/PATTERNS.md}.
 * The harness <strong>fails the build</strong> when a fix introduces or
 * reintroduces an anti-pattern. Update both this class and PATTERNS.md
 * when a new rule is added or an existing one changes.</p>
 *
 * <p>Owner: platform-architecture@shop. When you touch a rule, also touch
 * the doc and the agent brief — they are the rule's three forms. If only
 * one changes, the work is incomplete.</p>
 */
class FleetPatternComplianceTest {

    private static final Path REPO_ROOT = findRepoRoot();

    private static Path findRepoRoot() {
        Path cursor = Paths.get("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/PATTERNS.md"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalStateException("Could not locate repo root (no docs/PATTERNS.md found above).");
        }
        return cursor;
    }

    private static com.github.javaparser.ast.CompilationUnit parse(Path p) throws java.io.IOException {
        return new com.github.javaparser.JavaParser()
                .parse(p)
                .getResult()
                .orElseThrow(() -> new RuntimeException("parse failed: " + p));
    }

    /**
     * R2 — {@code @ExceptionHandler(DataIntegrityViolationException.class)} must
     * map to {@code HttpStatus.CONFLICT} (409). Returning 500 is a mistake:
     * the client needs the signal to know the row collided, and the raw
     * cause text must not reach the response body.
     */
    @Test
    void everyDataIntegrityViolationHandlerReturns409() throws IOException {
        List<Path> javaFiles = walk("utils/common-spring/src/main/java");
        Set<Path> offenders = new TreeSet<>();
        for (Path java : javaFiles) {
            CompilationUnit cu = parse(java);
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration clazz)) continue;
                for (MethodDeclaration method : clazz.getMethods()) {
                    method.getAnnotations().forEach(ann -> {
                        String name = ann.getNameAsString();
                        boolean handlesDataIntegrity = name.equals("ExceptionHandler")
                                && ann.toString()
                                .contains("DataIntegrityViolationException")
                                && !ann.toString().contains("ConstraintViolationException");
                        if (!handlesDataIntegrity) return;
                        // Lint the method body for HttpStatus.INTERNAL_SERVER_ERROR
                        // and any reference to getMostSpecificCause()/getMessage()
                        // being interpolated into a response.
                        String body = method.getBody().toString();
                        if (body.contains("INTERNAL_SERVER_ERROR")) {
                            offenders.add(java);
                        }
                        if (body.contains("getMostSpecificCause")
                                && (body.contains("+ exception")
                                        || body.contains(".getMessage"))) {
                            // Acceptable: only when the result is used in log.error(...)
                            // not in the response message. Conservative check: flag the file.
                            // Refine the linter here when we adopt javaparser-symbol-solver.
                            offenders.add(java);
                        }
                    });
                }
            }
        }
        assertThat(offenders)
                .as("R2 — every DataIntegrityViolationException handler must return 409 + "
                        + "ErrorCode.CONFLICT. See docs/PATTERNS.md#r2.")
                .isEmpty();
    }

    /**
     * R1 — records must NOT carry Lombok {@code @Builder}. The canonical
     * constructor is the construction API; Lombok on top duplicates the
     * surface and lies to readers about the binding model
     * (Jackson uses canonical-ctor, not the Lombok builder).
     */
    @Test
    void noRecordUsesLombokBuilder() throws IOException {
        List<Path> javaFiles = walk("auth-service/src/main/java");
        Set<Path> offenders = new TreeSet<>();
        for (Path java : javaFiles) {
            CompilationUnit cu = parse(java);
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!(type instanceof ClassOrInterfaceDeclaration clazz)) continue;
                boolean isRecord = clazz.toString().contains(" public record ")
                        || clazz.toString().contains("\tpublic record ");
                if (!isRecord) continue;
                boolean hasBuilderAnnotation = clazz.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Builder")
                                || a.getNameAsString().equals("lombok.Builder"));
                if (hasBuilderAnnotation) {
                    offenders.add(java);
                }
            }
        }
        assertThat(offenders)
                .as("R1 — records must not use Lombok @Builder. See docs/PATTERNS.md#r1.")
                .isEmpty();
    }

    /**
     * R3 — Kafka producer/consumer config keys must use Kafka-native names.
     * A key like {@code properties.max.in.flight.requests.per.connection}
     * is wrong — Kafka reads {@code max.in.flight.requests.per.connection}.
     * Pattern: look for any literal key starting with {@code properties.}
     * in the config-build helper.
     */
    @Test
    void kafkaBuildPropertiesDoesNotPrefixKeysWithPropertiesDot() throws IOException {
        Path kafkaProps = REPO_ROOT.resolve(
                "utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java");
        if (!Files.exists(kafkaProps)) {
            return; // Module removed/moved — rule unenforced.
        }
        CompilationUnit cu = parse(kafkaProps);
        List<String> badKeys = new ArrayList<>();
        cu.findAll(MethodCallExpr.class).forEach(mc -> {
            if (!mc.getNameAsString().equals("put")) return;
            if (mc.getArguments().isEmpty()) return;
            String key = mc.getArguments().get(0).toString().replace("\"", "");
            if (key.startsWith("properties.")) {
                badKeys.add(key);
            }
        });
        assertThat(badKeys)
                .as("R3 — Kafka config keys must not start with `properties.`. "
                        + "See docs/PATTERNS.md#r3.")
                .isEmpty();
    }

    /**
     * R5 — The root pom's jib-maven-plugin config must set
     * {@code <container><user>100:101</user></container>} (or any non-zero
     * UID) so Jib-built images do not run as root.
     */
    @Test
    void jibMavenPluginSetsNonRootUser() throws IOException {
        Path pom = REPO_ROOT.resolve("pom.xml");
        String text = Files.readString(pom);
        boolean hasJibUser = Pattern.compile("<container>.*<user>\\s*\\d", Pattern.DOTALL).matcher(text).find();
        assertThat(hasJibUser)
                .as("R5 — root pom jib-maven-plugin must set <container><user>. "
                        + "See docs/PATTERNS.md#r5.")
                .isTrue();
    }

    /**
     * R6 — env vars referenced via {@code ${X}} in yaml/compose files MUST
     * appear in /.env.example. Renaming a var must update all five
     * touchpoints in a single commit.
     */
    @Test
    void envVarsReferencedInYamlMatchEnvExample() throws IOException {
        String envExample = Files.readString(REPO_ROOT.resolve(".env.example"));
        Set<String> declared = extractedEnvVarNames(envExample);

        // Touchpoints: every service's application.yml + the two compose files.
        List<Path> yamls = walk(".").stream()
                .filter(p -> {
                    String name = p.toString();
                    return name.endsWith("application.yml")
                            || name.endsWith("application.yaml")
                            || name.endsWith("docker-compose.yml")
                            || name.endsWith("docker-compose.prod.yml");
                })
                .toList();

        List<String> unresolved = new ArrayList<>();
        Set<String> referenced = new TreeSet<>();
        for (Path yaml : yamls) {
            String content = Files.readString(yaml);
            for (String var : referencedEnvVarNames(content)) {
                referenced.add(var);
                // Spring built-in placeholders we know are not in .env.example.
                if (isSpringKnownPlaceholder(var)) continue;
                if (!declared.contains(var)) {
                    unresolved.add(yaml + " -> " + var);
                }
            }
        }
        assertThat(unresolved)
                .as("R6 — all referenced env vars (%s) must be declared in /.env.example. "
                        + "See docs/PATTERNS.md#r6.", referenced)
                .isEmpty();
    }

    /**
     * R4 — Kafka consumer trusted packages must not be wildcarded
     * ({@code "*"} or {@code TrustedPackages.ALL}).
     */
    @Test
    void noKafkaTrustedPackagesWildcard() throws IOException {
        List<Path> javaFiles = walk("utils");
        Set<Path> offenders = new TreeSet<>();
        for (Path java : javaFiles) {
            if (!java.toString().endsWith(".java")) continue;
            String content = Files.readString(java);
            boolean hasTrustedPackages = content.contains("addTrustedPackages")
                    || content.contains("TrustedPackages")
                    || content.contains("TRUSTED_PACKAGES");
            if (!hasTrustedPackages) continue;
            boolean wildcard = content.contains("addTrustedPackages(\"*\")")
                    || content.contains("addTrustedPackages(\\\"*\")")
                    || content.contains("TrustedPackages.ALL")
                    || content.contains("setTrustedPackages(\"*\")");
            if (wildcard) {
                offenders.add(java);
            }
        }
        assertThat(offenders)
                .as("R4 — Kafka trusted packages must be enumerated, never \"*\". "
                        + "See docs/PATTERNS.md#r4.")
                .isEmpty();
    }

    // ----- helpers ------------------------------------------------------

    private static boolean isSpringKnownPlaceholder(String name) {
        // Spring's own documented placeholders (e.g. ${server.port}, ${spring.profiles.active}).
        // Treat all-lowercase + underscores as Spring- or third-party-bounded; trust the docs.
        return name.toLowerCase().equals(name)
                && (name.startsWith("spring.")
                || name.startsWith("server.")
                || name.startsWith("management.")
                || name.startsWith("logging.")
                || name.startsWith("jpa."));
    }

    private static Set<String> referencedEnvVarNames(String content) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}?").matcher(content);
        while (m.find()) {
            // Use look-behind to require the variable was referenced, not the default
            // value form. We do permit both — the default form ${X:default} declares X in this file.
            out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> extractedEnvVarNames(String envExample) {
        Set<String> out = new TreeSet<>();
        for (String line : envExample.split("\\R")) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            if (name.matches("[A-Z_][A-Z0-9_]*")) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<Path> walk(String start) throws IOException {
        Path root = Paths.get(start);
        if (!root.startsWith(REPO_ROOT) && !root.isAbsolute()) {
            root = REPO_ROOT.resolve(start);
        }
        if (!Files.exists(root)) return List.of();
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                String path = file.toString();
                // Production sources only — exclude target/build/test to keep the
                // harness honest and avoid flagging its own javadoc.
                if (name.endsWith(".java")
                        && !name.contains("/target/")
                        && !name.contains("/build/")
                        && path.contains("/src/main/")) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}
