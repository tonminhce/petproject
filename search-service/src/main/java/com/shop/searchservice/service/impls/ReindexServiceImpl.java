package com.shop.searchservice.service.impls;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.searchservice.client.ProductBackofficeClient;
import com.shop.searchservice.dto.response.ReindexResponse;
import com.shop.searchservice.search.IndexProvisioner;
import com.shop.searchservice.service.ProductDocuments;
import com.shop.searchservice.service.ReindexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Failure semantics: any source-page failure or ES failure mid-stream maps
 * to 503 SRH-12002 and aborts WITHOUT an alias swap. The freshly created
 * (possibly empty/partial) index is left behind as an orphan — the next
 * successful run resolves {@code max(products-v*)+1}, skips the orphan number,
 * and its post-swap cleanup deletes ALL {@code products-v*} indices except the
 * new target, so the orphan is reaped then.
 *
 * <p>Concurrency: an in-process {@link AtomicBoolean} lock rejects a second
 * concurrent run with 409 SRH-12001 (single-instance MVP posture — a multi-node
 * deployment needs a distributed lock first).</p>
 *
 * <p>NO auto full-sync on startup (ops §4(1)): the first deploy runs reindex
 * once via this endpoint; the Kafka consumer keeps the index fresh after.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReindexServiceImpl implements ReindexService {

    /** Page size for the source stream — the backoffice endpoint's cap (PageableConstant.MAX_PAGE_SIZE). */
    static final int SOURCE_PAGE_SIZE = PageableConstant.MAX_PAGE_SIZE;

    private final ElasticsearchClient client;
    private final ProductBackofficeClient productClient;

    private final AtomicBoolean reindexInProgress = new AtomicBoolean(false);

    @Override
    public ReindexResponse reindex(boolean dryRun) {
        if (!reindexInProgress.compareAndSet(false, true)) {
            throw BusinessException.of(ErrorCode.SEARCH_REINDEX_IN_PROGRESS);
        }
        long start = System.currentTimeMillis();
        try {
            return dryRun ? dryRun(start) : fullReindex(start);
        } finally {
            reindexInProgress.set(false);
        }
    }

    private ReindexResponse dryRun(long start) {
        long count = streamSource((page, snapshots) -> {});
        return new ReindexResponse(count, currentAliasTarget(), System.currentTimeMillis() - start);
    }

    private ReindexResponse fullReindex(long start) {
        String newIndex = nextIndexName();
        createIndex(newIndex);

        long indexed = streamSource((page, snapshots) -> bulkIndex(newIndex, snapshots));
        refresh(newIndex);

        swapAlias(newIndex);
        deleteSupersededIndices(newIndex);

        log.info("Reindex complete: {} docs into {} in {}ms", indexed, newIndex,
            System.currentTimeMillis() - start);
        return new ReindexResponse(indexed, newIndex, System.currentTimeMillis() - start);
    }

    /** Streams every source page; the sink decides what to do with each page. */
    private long streamSource(SourcePageSink sink) {
        long count = 0;
        int page = 0;
        var current = productClient.fetchPage(page, SOURCE_PAGE_SIZE);
        while (true) {
            sink.accept(page, current.content());
            count += current.content().size();
            if (Boolean.TRUE.equals(current.last()) || current.content().isEmpty()) {
                return count;
            }
            current = productClient.fetchPage(++page, SOURCE_PAGE_SIZE);
        }
    }

    @FunctionalInterface
    private interface SourcePageSink {
        void accept(int page, List<ProductBackofficeClient.ProductSnapshot> snapshots);
    }

    private void bulkIndex(String newIndex, List<ProductBackofficeClient.ProductSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (ProductBackofficeClient.ProductSnapshot snapshot : snapshots) {
                Map<String, Object> document = ProductDocuments.of(snapshot);
                builder.operations(op -> op.index(idx -> idx
                    .index(newIndex)
                    .id(document.get("id").toString())
                    .document(document)));
            }
            BulkResponse response = client.bulk(builder.build());
            if (response.errors()) {
                response.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(item -> log.error("Bulk item failure for doc {}: {}", item.id(),
                        item.error().reason()));
                throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
            }
        } catch (IOException | ElasticsearchException ex) {
            log.error("Bulk indexing into {} failed — aborting before alias swap", newIndex, ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }

    private String nextIndexName() {
        try {
            int max = 0;
            for (String name : listGenerationIndices()) {
                max = Math.max(max, Integer.parseInt(name.substring("products-v".length())));
            }
            return "products-v" + (max + 1);
        } catch (IOException | ElasticsearchException ex) {
            log.error("Resolving the next products-v(n+1) index failed", ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }

    private List<String> listGenerationIndices() throws IOException {
        return client.indices().get(g -> g.index(IndexProvisioner.INDEX_PATTERN)).result().keySet().stream().toList();
    }

    private void createIndex(String newIndex) {
        try {
            client.indices().create(c -> c.index(newIndex));
        } catch (IOException | ElasticsearchException ex) {
            log.error("Creating index {} failed — aborting before alias swap", newIndex, ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }

    private void refresh(String index) {
        try {
            client.indices().refresh(r -> r.index(index));
        } catch (IOException | ElasticsearchException ex) {
            log.error("Refreshing {} failed — aborting before alias swap", index, ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }

    /**
     * Atomic swap: remove the alias from every generation index and add it to
     * the new one in a single updateAliases call.
     */
    private void swapAlias(String newIndex) {
        try {
            client.indices().updateAliases(u -> u.actions(List.of(
                Action.of(a -> a.remove(r -> r.index(IndexProvisioner.INDEX_PATTERN)
                    .alias(IndexProvisioner.ALIAS))),
                Action.of(a -> a.add(ad -> ad.index(newIndex).alias(IndexProvisioner.ALIAS))))));
        } catch (IOException | ElasticsearchException ex) {
            log.error("Alias swap to {} failed — old alias target kept", newIndex, ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }

    /** Post-swap cleanup: every generation index except the new target (incl. orphans from failed runs). */
    private void deleteSupersededIndices(String newIndex) {
        try {
            for (String name : listGenerationIndices()) {
                if (!name.equals(newIndex)) {
                    client.indices().delete(d -> d.index(name));
                    log.info("Deleted superseded index {}", name);
                }
            }
        } catch (IOException | ElasticsearchException ex) {
            // docs are already live on the new index via the alias — never fail
            // the reindex because cleanup of a stale index failed.
            log.warn("Cleaning up superseded products-v* indices failed — they will be "
                + "reaped by the next successful reindex", ex);
        }
    }

    /** The concrete index currently behind the {@code products} alias, or "none". */
    private String currentAliasTarget() {
        try {
            return client.indices().getAlias(a -> a.name(IndexProvisioner.ALIAS))
                .result().keySet().stream().findFirst().orElse("none");
        } catch (ElasticsearchException ex) {
            if (ex.status() == 404) {
                return "none";
            }
            log.error("Resolving the current alias target failed", ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        } catch (IOException ex) {
            log.error("Resolving the current alias target failed", ex);
            throw BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED);
        }
    }
}
