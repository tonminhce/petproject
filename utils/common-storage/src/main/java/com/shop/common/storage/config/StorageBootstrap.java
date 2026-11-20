package com.shop.common.storage.config;

import com.shop.common.storage.service.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class StorageBootstrap implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StorageBootstrap.class);

    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final Executor executor;
    private final boolean ownsExecutor;

    public StorageBootstrap(ObjectStorageService storage, StorageProperties properties) {
        this(storage, properties, daemonExecutor(), true);
    }

    public StorageBootstrap(ObjectStorageService storage, StorageProperties properties, Executor executor) {
        this(storage, properties, executor, false);
    }

    public StorageBootstrap(ObjectStorageService storage,
                            StorageProperties properties,
                            Executor executor,
                            boolean ownsExecutor) {
        this.storage = storage;
        this.properties = properties;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ensureBucketReady();
    }

    public CompletableFuture<Void> ensureBucketReady() {
        return CompletableFuture.runAsync(() -> {
            try {
                storage.ensureBucketExists(properties.getBucket());
                log.info("Object-storage bucket '{}' is available", properties.getBucket());
            } catch (RuntimeException failure) {
                log.warn("Object-storage bucket '{}' could not be verified at startup: {}",
                        properties.getBucket(), failure.getMessage());
            }
        }, executor);
    }

    @Override
    public void destroy() {
        if (ownsExecutor && executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdown();
        }
    }

    private static Executor daemonExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "object-storage-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
    }
}
