package com.shop.shippingservice.service;

import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentStatusWriteGuardTest {

    private static final String[] FORBIDDEN_WRITES = {
            "setStatus(ShipmentStatus",
            "setPreviousStatus("
    };

    @Test
    void theShipmentEntityDoesNotExposeASetterForTheStatus() {
        assertThatThrownBy(() -> Shipment.class.getMethod("setStatus", ShipmentStatus.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> Shipment.class.getMethod("setPreviousStatus", ShipmentStatus.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void theShipmentEntityExposesOnlyStateMachineAwareStatusChanges() {
        assertThat(Shipment.class.getMethods())
                .filteredOn(method -> method.getName().startsWith("transition")
                        || method.getName().startsWith("applyCarrier")
                        || method.getName().startsWith("operator")
                        || method.getName().startsWith("autoDeliver"))
                .isNotEmpty();
    }

    @Test
    void noFirstPartyCodeWritesTheShipmentStatusOutsideTheStateMachine() throws IOException {
        Path mainSources = locateMainSources();
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(mainSources)) {
            javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(javaFiles).as("first-party sources under %s", mainSources).isNotEmpty();

        for (Path file : javaFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN_WRITES) {
                assertThat(source)
                        .as("%s must not contain the direct status write %s", file, forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }

    private Path locateMainSources() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate src/main/java from the working directory");
    }
}
