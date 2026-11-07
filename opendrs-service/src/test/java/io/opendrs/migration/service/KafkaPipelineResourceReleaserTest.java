package io.opendrs.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.engine.capture.transport.CdcTransportSpec;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.config.OpenDrsTransportProperties;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KafkaPipelineResourceReleaserTest {

    @Test
    void directIsNoOp() {
        TaskPipelineMapper pipelines = mock(TaskPipelineMapper.class);
        OpenDrsTransportProperties props = new OpenDrsTransportProperties();
        props.setChannel("DIRECT");
        KafkaPipelineResourceReleaser releaser = new KafkaPipelineResourceReleaser(pipelines, props);

        MigrationTask task = task(CdcTransportSpec.DIRECT);
        releaser.releaseIfNeeded(task);

        verify(pipelines, never()).findByTaskId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void resolvesKafkaFromTaskOptionsOverGlobalDirect() {
        TaskPipelineMapper pipelines = mock(TaskPipelineMapper.class);
        OpenDrsTransportProperties props = new OpenDrsTransportProperties();
        props.setChannel("DIRECT");
        KafkaPipelineResourceReleaser releaser = new KafkaPipelineResourceReleaser(pipelines, props);

        MigrationTask task = task(CdcTransportSpec.KAFKA);
        assertThat(releaser.resolveChannel(task)).isEqualTo(CdcTransportSpec.KAFKA);
    }

    @Test
    void inheritsGlobalKafkaWhenOptionsNull() {
        TaskPipelineMapper pipelines = mock(TaskPipelineMapper.class);
        OpenDrsTransportProperties props = new OpenDrsTransportProperties();
        props.setChannel("KAFKA");
        KafkaPipelineResourceReleaser releaser = new KafkaPipelineResourceReleaser(pipelines, props);

        MigrationTask task = task(null);
        assertThat(releaser.resolveChannel(task)).isEqualTo(CdcTransportSpec.KAFKA);
    }

    @Test
    void kafkaLooksUpPipelines() {
        TaskPipelineMapper pipelines = mock(TaskPipelineMapper.class);
        OpenDrsTransportProperties props = new OpenDrsTransportProperties();
        props.setChannel("KAFKA");
        props.setKafkaBootstrapServers("127.0.0.1:9092");
        when(pipelines.findByTaskId(7L)).thenReturn(List.of());

        KafkaPipelineResourceReleaser releaser = new KafkaPipelineResourceReleaser(pipelines, props);
        releaser.releaseIfNeeded(task(CdcTransportSpec.KAFKA));

        verify(pipelines).findByTaskId(7L);
    }

    private static MigrationTask task(CdcTransportSpec channel) {
        MigrationTask task = new MigrationTask();
        task.setId(7L);
        task.setPublicId(UUID.randomUUID().toString());
        task.setOptionsJson(new MigrationOptions(
                MigrationOptions.Topology.ONE_WAY,
                MigrationOptions.Selection.all(),
                MigrationOptions.FullSettings.defaults(),
                MigrationOptions.IncrementalSettings.defaults(),
                null,
                channel));
        return task;
    }
}
