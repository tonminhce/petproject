package com.shop.common.spring.deploy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architecture document must describe the artifacts that are committed.
 *
 * <p>The document used to embed a verification receipt whose hashes belonged
 * to an earlier revision of the diagram, next to a claim that a visual check
 * had passed while the committed receipt said the check had been skipped. A
 * citation that no longer resolves is not evidence, and a reader cannot tell
 * the difference.</p>
 */
class ArchitectureArtifactHashContractTest {

    private static final Pattern HASH = Pattern.compile(
            "\"(?<key>[A-Za-z]+Sha256|[A-Za-z]+Bytes)\"\\s*:\\s*\"?(?<value>[0-9a-f]{64}|[0-9]+)\"?");

    private static final Pattern FILE_NAME = Pattern.compile(
            "\"(?<name>[A-Za-z0-9._-]+\\.(?:json|html))\"");

    @Test
    @DisplayName("every artifact hash quoted in the architecture document matches the committed file")
    void everyArtifactHashQuotedInTheDocumentMatchesTheCommittedFile() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path document = repoRoot.resolve("docs/architecture/MASTER_ARCHITECTURE_ANALYSIS.md");
        Assumptions.assumeTrue(Files.exists(document), "architecture document not found - contract not checked");

        String content = Files.readString(document, StandardCharsets.UTF_8);
        Path architectureDir = document.getParent();

        List<String> offenders = new ArrayList<>();
        String currentName = null;
        for (String line : content.split("\n")) {
            Matcher nameMatcher = FILE_NAME.matcher(line);
            if (nameMatcher.find()) {
                currentName = nameMatcher.group("name");
            }
            Matcher hashMatcher = HASH.matcher(line);
            while (hashMatcher.find()) {
                String key = hashMatcher.group("key");
                String value = hashMatcher.group("value");
                if (currentName == null) {
                    continue;
                }
                Path artifact = architectureDir.resolve(currentName);
                if (!Files.exists(artifact)) {
                    continue;
                }
                if (key.endsWith("Bytes")) {
                    long actual = Files.size(artifact);
                    if (Long.parseLong(value) != actual) {
                        offenders.add(currentName + " -> " + key + " says " + value + " but the file is " + actual);
                    }
                } else {
                    String actual = sha256(artifact);
                    if (!actual.equals(value)) {
                        offenders.add(currentName + " -> " + key + " says " + value + " but the file is " + actual);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("a quoted hash that does not resolve describes an artifact that is not committed")
                .isEmpty();
    }

    @Test
    @DisplayName("the architecture document does not claim a visual verification that was skipped")
    void theDocumentDoesNotClaimAVisualVerificationThatWasSkipped() throws IOException {
        Path repoRoot = findRepoRoot();
        Assumptions.assumeTrue(repoRoot != null,
                "repository root not reachable from the module working directory - contract not checked");
        Path document = repoRoot.resolve("docs/architecture/MASTER_ARCHITECTURE_ANALYSIS.md");
        Assumptions.assumeTrue(Files.exists(document), "architecture document not found - contract not checked");
        Path receipt = repoRoot.resolve("docs/architecture/petproject-platform.visual-check.json");
        Assumptions.assumeTrue(Files.exists(receipt), "visual check receipt not found - contract not checked");

        String receiptContent = Files.readString(receipt, StandardCharsets.UTF_8);
        Assumptions.assumeTrue(receiptContent.contains("\"status\": \"skipped\""),
                "the receipt records a completed check - the contract does not apply");

        String content = Files.readString(document, StandardCharsets.UTF_8);

        assertThat(content)
                .as("a skipped check must not be reported as a passed one")
                .doesNotContain("9 of 9 validation checks passed")
                .doesNotContain("\"compositionStatus\": \"pass\"")
                .contains("has NOT been visually verified");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
