package com.shop.common.storage.config;

import com.shop.common.storage.service.ObjectStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageStartupResilienceTest {

    private static final String UNREACHABLE_ENDPOINT = "http://127.0.0.1:1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObjectStorageAutoConfiguration.class))
            .withPropertyValues(
                    "shop.storage.enabled=true",
                    "shop.storage.endpoint=" + UNREACHABLE_ENDPOINT,
                    "shop.storage.access-key=access",
                    "shop.storage.secret-key=secret",
                    "shop.storage.bucket=shop-media",
                    "shop.storage.api-call-timeout=2s",
                    "shop.storage.api-call-attempt-timeout=1s");

    @Test
    @DisplayName("the application context starts even when the object store is unreachable")
    void theApplicationContextStartsEvenWhenTheObjectStoreIsUnreachable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectStorageService.class);
            assertThat(context).hasSingleBean(StorageBootstrap.class);
        });
    }

    @Test
    @DisplayName("the lazy bucket bootstrap tolerates an unreachable object store")
    void theLazyBucketBootstrapToleratesAnUnreachableObjectStore() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StorageBootstrap bootstrap = context.getBean(StorageBootstrap.class);

            CompletableFuture<Void> ready = bootstrap.ensureBucketReady();

            assertThat(ready).succeedsWithin(30, TimeUnit.SECONDS);
            assertThat(context).hasNotFailed();
        });
    }
}
