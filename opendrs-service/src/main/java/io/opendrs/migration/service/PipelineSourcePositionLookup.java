package io.opendrs.migration.service;

import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caches {@link SourcePositionQuery} results briefly so Prometheus scrapes and the HTTP API can
 * share the same source-inclusive lag computation without hitting the source on every gauge read.
 */
@Component
public class PipelineSourcePositionLookup {

    private static final long CACHE_TTL_MS = 1_000L;

    private final SourcePositionQuery query;
    private final TaskPipelineMapper pipelineMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final MigrationLagRegistry lags;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public PipelineSourcePositionLookup(
            SourcePositionQuery query,
            TaskPipelineMapper pipelineMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationLagRegistry lags) {
        this.query = query;
        this.pipelineMapper = pipelineMapper;
        this.connectionMapper = connectionMapper;
        this.lags = lags;
    }

    @PostConstruct
    void bind() {
        lags.setSourcePositionLookup(this::positionOf);
    }

    public String positionOf(long pipelineId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(pipelineId);
        if (cached != null && now - cached.atMs < CACHE_TTL_MS) {
            return cached.position;
        }
        String position = queryNow(pipelineId);
        cache.put(pipelineId, new Cached(position, now));
        return position;
    }

    private String queryNow(long pipelineId) {
        TaskPipeline pipeline = pipelineMapper.findById(pipelineId);
        if (pipeline == null || pipeline.getSourceEndpointId() == null) {
            return null;
        }
        return query.query(connectionMapper.findById(pipeline.getSourceEndpointId()));
    }

    private record Cached(String position, long atMs) {
    }
}
