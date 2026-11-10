package io.opendrs.datacheck.job;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.engine.capture.transport.CdcTransportSpec;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import java.util.List;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;

class DataCheckCoordinatorTest {

    @Test
    void fullOnlyAllowsDirectTransport() {
        assertNull(DataCheckCoordinator.incrementalRequiresKafka(
                DataCheckMode.FULL_ONLY, CdcTransportSpec.DIRECT));
        assertNull(DataCheckCoordinator.incrementalRequiresKafka(
                DataCheckMode.FULL_ONLY, CdcTransportSpec.KAFKA));
    }

    @Test
    void incrementalModesRequireKafka() {
        String error = DataCheckCoordinator.incrementalRequiresKafka(
                DataCheckMode.FULL_AND_INCREMENTAL, CdcTransportSpec.DIRECT);
        assertEquals(
                "incremental data check requires KAFKA transport; current channel is DIRECT",
                error);
        error = DataCheckCoordinator.incrementalRequiresKafka(
                DataCheckMode.INCREMENTAL_ONLY, CdcTransportSpec.DIRECT);
        assertEquals(
                "incremental data check requires KAFKA transport; current channel is DIRECT",
                error);
        assertNull(DataCheckCoordinator.incrementalRequiresKafka(
                DataCheckMode.FULL_AND_INCREMENTAL, CdcTransportSpec.KAFKA));
        assertNull(DataCheckCoordinator.incrementalRequiresKafka(
                DataCheckMode.INCREMENTAL_ONLY, CdcTransportSpec.KAFKA));
    }

    @Test
    void resolveCdcTransportSpec_taskOptionsOverrideProcessDefault() {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(
                CdcTransportSpec.KAFKA,
                DataCheckCoordinator.resolveCdcTransportSpec(
                        mapper, "{\"cdcTransportSpec\":\"KAFKA\"}", CdcTransportSpec.DIRECT));
        assertEquals(
                CdcTransportSpec.DIRECT,
                DataCheckCoordinator.resolveCdcTransportSpec(
                        mapper, "{\"cdcTransportSpec\":\"DIRECT\"}", CdcTransportSpec.KAFKA));
        assertEquals(
                CdcTransportSpec.DIRECT,
                DataCheckCoordinator.resolveCdcTransportSpec(mapper, "{}", CdcTransportSpec.DIRECT));
        assertEquals(
                CdcTransportSpec.KAFKA,
                DataCheckCoordinator.resolveCdcTransportSpec(mapper, null, CdcTransportSpec.KAFKA));
        assertEquals(
                CdcTransportSpec.DIRECT,
                DataCheckCoordinator.resolveCdcTransportSpec(mapper, "not-json", CdcTransportSpec.DIRECT));
    }

    @Test
    void alreadyInIncremental_allTablesInIncrementalPhase() {
        DataCheckTableRow incremental = row(DataCheckPhase.INCREMENTAL);
        DataCheckTableRow full = row(DataCheckPhase.FULL);
        assertFalse(DataCheckCoordinator.alreadyInIncremental(List.of()));
        assertFalse(DataCheckCoordinator.alreadyInIncremental(List.of(full)));
        assertFalse(DataCheckCoordinator.alreadyInIncremental(List.of(incremental, full)));
        assertTrue(DataCheckCoordinator.alreadyInIncremental(List.of(incremental, incremental)));
    }

    @Test
    void isWakeupRecognizesKafkaWakeupException() {
        assertTrue(DataCheckCoordinator.isWakeup(new WakeupException()));
        assertTrue(DataCheckCoordinator.isWakeup(new RuntimeException(new WakeupException())));
        assertFalse(DataCheckCoordinator.isWakeup(new IllegalStateException("no")));
    }

    @Test
    void eligibleForIncremental_includesStoppedAndRunningAfterIncrementalStarted() {
        DataCheckTableRow completedFull = row(DataCheckPhase.FULL, DataCheckTableStatus.COMPLETED);
        DataCheckTableRow failedFull = row(DataCheckPhase.FULL, DataCheckTableStatus.FAILED);
        DataCheckTableRow stoppedInc = row(DataCheckPhase.INCREMENTAL, DataCheckTableStatus.STOPPED);
        DataCheckTableRow runningInc = row(DataCheckPhase.INCREMENTAL, DataCheckTableStatus.RUNNING);
        assertTrue(DataCheckCoordinator.eligibleForIncremental(
                DataCheckMode.FULL_AND_INCREMENTAL, completedFull));
        assertFalse(DataCheckCoordinator.eligibleForIncremental(
                DataCheckMode.FULL_AND_INCREMENTAL, failedFull));
        assertTrue(DataCheckCoordinator.eligibleForIncremental(
                DataCheckMode.FULL_AND_INCREMENTAL, stoppedInc));
        assertTrue(DataCheckCoordinator.eligibleForIncremental(
                DataCheckMode.FULL_AND_INCREMENTAL, runningInc));
        assertTrue(DataCheckCoordinator.eligibleForIncremental(
                DataCheckMode.INCREMENTAL_ONLY, stoppedInc));
    }

    private static DataCheckTableRow row(DataCheckPhase phase) {
        return row(phase, DataCheckTableStatus.RUNNING);
    }

    private static DataCheckTableRow row(DataCheckPhase phase, DataCheckTableStatus status) {
        return new DataCheckTableRow(
                "s.t", "s.t", phase, status, 0L, null, null, null);
    }
}
