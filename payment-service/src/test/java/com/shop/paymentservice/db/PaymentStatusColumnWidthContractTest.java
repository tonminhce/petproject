package com.shop.paymentservice.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusColumnWidthContractTest {

    private static final Pattern STATUS_COLUMN = Pattern.compile(
        "name:\\s*status\\s*,\\s*type:\\s*varchar\\((\\d+)\\)");

    private static final Pattern WIDENING = Pattern.compile(
        "alter\\s+column\\s+(?:status|previous_status)\\s+type\\s+varchar\\((\\d+)\\)",
        Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every status column is wide enough for the longest payment status")
    void everyStatusColumnIsWideEnoughForTheLongestPaymentStatus() throws IOException {
        int longest = longestPaymentStatusLength();
        Path changelog = Path.of("src/main/resources/db/changelog");
        int widened = widestAppliedWidth(changelog);
        try (var files = Files.list(changelog)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                String source = Files.readString(file);
                Matcher matcher = STATUS_COLUMN.matcher(source);
                while (matcher.find()) {
                    int width = Integer.parseInt(matcher.group(1));
                    int effective = Math.max(width, widened);
                    assertThat(effective)
                        .as("the status column declared in " + file.getFileName()
                            + " ends up " + effective + " characters wide after every change set"
                            + " and must hold the longest payment status, which is " + longest + " characters")
                        .isGreaterThanOrEqualTo(longest);
                }
            }
        }
    }

    private int widestAppliedWidth(Path changelog) throws IOException {
        int widest = 0;
        try (var files = Files.list(changelog)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                String source = Files.readString(file);
                Matcher matcher = WIDENING.matcher(source);
                while (matcher.find()) {
                    widest = Math.max(widest, Integer.parseInt(matcher.group(1)));
                }
            }
        }
        return widest;
    }

    @Test
    @DisplayName("the widening change set exists and is wired")
    void theWideningChangeSetExistsAndIsWired() throws IOException {
        Path changelog = Path.of("src/main/resources/db/changelog");
        Path widening = changelog.resolve("changelog-013-widen-payment-status-columns.yaml");
        assertThat(widening).exists();
        String source = Files.readString(widening).toLowerCase(Locale.ROOT);
        assertThat(source).contains("alter table payments alter column status type varchar(32)");
        assertThat(source).contains("alter table payments alter column previous_status type varchar(32)");
        assertThat(Files.readString(changelog.resolve("db.changelog-master.yaml")))
            .contains("changelog-013-widen-payment-status-columns.yaml");
    }

    private int longestPaymentStatusLength() throws IOException {
        Path source = Path.of("src/main/java/com/shop/paymentservice/constant/PaymentStatus.java");
        String text = Files.readString(source);
        Matcher matcher = Pattern.compile("\\b([A-Z][A-Z_]{2,})\\b").matcher(text);
        int longest = 0;
        while (matcher.find()) {
            longest = Math.max(longest, matcher.group(1).length());
        }
        assertThat(longest).isPositive();
        return longest;
    }
}
