package io.opendrs.migration.service;

import io.opendrs.engine.capture.transport.CdcTransportSpec;
import io.opendrs.engine.capture.transport.PipelineKafkaCleaner;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.config.OpenDrsTransportProperties;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Releases per-pipeline Kafka topic + consumer group when a task that used
 * {@link CdcTransportSpec#KAFKA} is terminated. Never called on pause.
 *
 * <p>Failures are logged and swallowed after the cleaner's brief retries so TERMINATED can still
 * land; orphaned Kafka resources can be swept later (e.g. on delete).
 */
@Component
public class KafkaPipelineResourceReleaser {

    private static final Logger log = LoggerFactory.getLogger(KafkaPipelineResourceReleaser.class);

    /** No-op for unit tests that do not wire transport. */
    public static final KafkaPipelineResourceReleaser NONE = new KafkaPipelineResourceReleaser(null, null) {
        @Override
        public void releaseIfNeeded(MigrationTask task) {
            // no-op
        }
    };

    private final TaskPipelineMapper pipelineMapper;
    private final OpenDrsTransportProperties transportProperties;

    public KafkaPipelineResourceReleaser(
            TaskPipelineMapper pipelineMapper, OpenDrsTransportProperties transportProperties) {
        this.pipelineMapper = pipelineMapper;
        this.transportProperties = transportProperties;
    }

    /**
     * Deletes Kafka topic/group for every pipeline of {@code task} when the effective CDC transport
     * is KAFKA. Safe to call when transport is DIRECT (no-op) or when pipelines have no public_id.
     */
    public void releaseIfNeeded(MigrationTask task) {
        if (task == null || resolveChannel(task) != CdcTransportSpec.KAFKA) {
            return;
        }
        if (transportProperties == null) {
            log.warn(
                    "Task {} is KAFKA but opendrs.transport is unset; skip Kafka resource release",
                    task.getId());
            return;
        }
        String bootstrap = transportProperties.getKafkaBootstrapServers();
        if (bootstrap == null || bootstrap.isBlank()) {
            log.warn(
                    "Task {} is KAFKA but kafka-bootstrap-servers is blank; skip Kafka resource release",
                    task.getId());
            return;
        }
        if (pipelineMapper == null) {
            log.warn("Task {} is KAFKA but pipeline mapper is unset; skip Kafka resource release", task.getId());
            return;
        }
        List<TaskPipeline> pipelines = pipelineMapper.findByTaskId(task.getId());
        if (pipelines == null || pipelines.isEmpty()) {
            log.info("Task {} has no pipelines to release Kafka resources for", task.getId());
            return;
        }
        for (TaskPipeline pipeline : pipelines) {
            String publicId = pipeline.getPublicId();
            if (publicId == null || publicId.isBlank()) {
                log.warn(
                        "Pipeline {} of task {} has no public_id; skip Kafka resource release",
                        pipeline.getId(),
                        task.getId());
                continue;
            }
            try {
                PipelineKafkaCleaner.deleteTopicAndGroup(bootstrap, publicId);
            } catch (RuntimeException ex) {
                log.warn(
                        "Failed to release Kafka resources for task {} pipeline {} publicId={}: {}",
                        task.getId(),
                        pipeline.getId(),
                        publicId,
                        ex.toString());
            }
        }
    }

    /** Task options override global channel; null/blank options inherit global then DIRECT. */
    CdcTransportSpec resolveChannel(MigrationTask task) {
        MigrationOptions options = task.getOptionsJson();
        if (options != null && options.cdcTransportSpec() != null) {
            return options.cdcTransportSpec();
        }
        if (transportProperties != null) {
            return transportProperties.channelEnum();
        }
        return CdcTransportSpec.DIRECT;
    }
}
