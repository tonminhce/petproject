package com.shop.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.searchservice.kafka.ProductLifecycleEvent;
import com.shop.searchservice.search.IndexProvisioner;
import com.shop.searchservice.search.ProductEventVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.ResponseException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSearchService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int VERSION_CONFLICT = 409;

    private final ElasticsearchClient client;

    public void index(ProductLifecycleEvent event) {
        if (!STATUS_ACTIVE.equals(event.status())) {
            delete(event);
            return;
        }
        Map<String, Object> document = ProductDocuments.of(event);
        Long version = ProductEventVersion.forEvent(event);
        if (version == null) {
            log.warn("Product event {} carries no usable ordering value - indexed without a version precondition",
                event.eventId());
            write(event.productId(), document, null);
            return;
        }
        write(event.productId(), document, version);
    }

    public void delete(ProductLifecycleEvent event) {
        delete(event.productId(), ProductEventVersion.forEvent(event));
    }

    public void delete(UUID productId) {
        delete(productId, null);
    }

    private void write(UUID productId, Map<String, Object> document, Long version) {
        try {
            if (version == null) {
                client.index(i -> i
                    .index(IndexProvisioner.ALIAS)
                    .id(productId.toString())
                    .document(document));
                return;
            }
            client.index(i -> i
                .index(IndexProvisioner.ALIAS)
                .id(productId.toString())
                .version(version)
                .versionType(VersionType.External)
                .document(document));
        } catch (ResponseException ex) {
            if (statusOf(ex) == VERSION_CONFLICT) {
                log.info("Stale product event for {} rejected by the index version precondition", productId);
                return;
            }
            throw BusinessException.of(
                ErrorCode.SEARCH_INDEX_FAILED,
                productId);
        } catch (ElasticsearchException ex) {
            if (ex.status() == VERSION_CONFLICT) {
                log.info("Stale product event for {} rejected by the index version precondition", productId);
                return;
            }
            throw BusinessException.of(
                ErrorCode.SEARCH_INDEX_FAILED,
                productId);
        } catch (IOException ex) {
            throw BusinessException.of(
                ErrorCode.SEARCH_INDEX_FAILED,
                productId);
        }
    }

    private static int statusOf(ResponseException ex) {
        return ex.getResponse() == null || ex.getResponse().getStatusLine() == null
                ? -1
                : ex.getResponse().getStatusLine().getStatusCode();
    }

    private void delete(UUID productId, Long version) {
        try {
            DeleteResponse response = version == null
                ? client.delete(d -> d.index(IndexProvisioner.ALIAS).id(productId.toString()))
                : client.delete(d -> d.index(IndexProvisioner.ALIAS)
                    .id(productId.toString())
                    .version(version)
                    .versionType(VersionType.External));
            if (response.result() == Result.NotFound) {
                log.debug("Delete for missing product doc {} — nothing to do", productId);
            }
        } catch (ResponseException ex) {
            int status = statusOf(ex);
            if (status == VERSION_CONFLICT) {
                log.info("Stale product delete for {} rejected by the index version precondition", productId);
                return;
            }
            if (status != 404) {
                throw BusinessException.of(
                    ErrorCode.SEARCH_DELETE_FAILED, productId);
            }
            log.debug("Delete for missing product doc {} — nothing to do", productId);
        } catch (ElasticsearchException ex) {
            if (ex.status() == VERSION_CONFLICT) {
                log.info("Stale product delete for {} rejected by the index version precondition", productId);
                return;
            }
            if (ex.status() != 404) {
                throw BusinessException.of(
                    ErrorCode.SEARCH_DELETE_FAILED, productId);
            }
            log.debug("Delete for missing product doc {} — nothing to do", productId);
        } catch (IOException ex) {
            throw BusinessException.of(
                ErrorCode.SEARCH_DELETE_FAILED, productId);
        }
    }
}
