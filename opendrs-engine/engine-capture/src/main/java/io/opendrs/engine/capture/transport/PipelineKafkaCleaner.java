package io.opendrs.engine.capture.transport;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes the per-pipeline Kafka topic ({@code opendrs.pipeline.{public_id}}) and replay consumer
 * group ({@code opendrs.replay.{public_id}}) via {@link AdminClient}. Idempotent: missing topic /
 * group is success. Callers should invoke this only for {@link CdcTransportSpec#KAFKA} pipelines
 * and only on terminate (never on pause).
 */
public final class PipelineKafkaCleaner {

    private static final Logger log = LoggerFactory.getLogger(PipelineKafkaCleaner.class);

    static final int MAX_ATTEMPTS = 3;
    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration RETRY_PAUSE = Duration.ofMillis(200);

    private PipelineKafkaCleaner() {
    }

    /**
     * Opens an {@link AdminClient} against {@code bootstrapServers} and deletes topic + group for
     * {@code pipelinePublicId}. Retries briefly on transient failures; still throws if all
     * attempts fail so the caller can log and decide whether to land TERMINATED.
     */
    public static void deleteTopicAndGroup(String bootstrapServers, String pipelinePublicId) {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        String publicId = requirePublicId(pipelinePublicId);
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) ATTEMPT_TIMEOUT.toMillis());
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) ATTEMPT_TIMEOUT.toMillis());
        try (Admin admin = AdminClient.create(props)) {
            deleteTopicAndGroup(admin, publicId);
        }
    }

    /** Test / injectable entry: same semantics without opening a client. */
    static void deleteTopicAndGroup(Admin admin, String pipelinePublicId) {
        Objects.requireNonNull(admin, "admin");
        String publicId = requirePublicId(pipelinePublicId);
        String topic = PipelineKafkaNames.topic(publicId);
        String group = PipelineKafkaNames.consumerGroup(publicId);
        withRetry("delete topic " + topic, () -> deleteTopic(admin, topic));
        withRetry("delete consumer group " + group, () -> deleteGroup(admin, group));
        log.info("Released Kafka resources topic={} group={}", topic, group);
    }

    private static void deleteTopic(Admin admin, String topic)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            admin.deleteTopics(List.of(topic))
                    .all()
                    .get(ATTEMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            if (isCausedBy(ex, UnknownTopicOrPartitionException.class)
                    || isCausedByName(ex, "UnknownTopicIdException")) {
                log.debug("Kafka topic {} already absent", topic);
                return;
            }
            throw ex;
        }
    }

    private static void deleteGroup(Admin admin, String group)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            admin.deleteConsumerGroups(List.of(group))
                    .all()
                    .get(ATTEMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            if (isCausedBy(ex, GroupIdNotFoundException.class)) {
                log.debug("Kafka consumer group {} already absent", group);
                return;
            }
            throw ex;
        }
    }

    private static boolean isCausedBy(ExecutionException ex, Class<? extends Exception> type) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isCausedByName(ExecutionException ex, String simpleName) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (simpleName.equals(cause.getClass().getSimpleName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void withRetry(String label, Interruptible action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                action.run();
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while " + label, ex);
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                last = ex instanceof RuntimeException
                        ? (RuntimeException) ex
                        : new IllegalStateException(
                                "Failed to " + label + " (attempt " + attempt + ")", ex);
                log.warn(
                        "Transient failure while {} (attempt {}/{}): {}",
                        label,
                        attempt,
                        MAX_ATTEMPTS,
                        ex.toString());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_PAUSE.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while retrying " + label, ie);
                    }
                }
            }
        }
        throw last;
    }

    private static String requirePublicId(String pipelinePublicId) {
        Objects.requireNonNull(pipelinePublicId, "pipelinePublicId");
        if (pipelinePublicId.isBlank()) {
            throw new IllegalArgumentException("pipelinePublicId must not be blank");
        }
        return pipelinePublicId;
    }

    @FunctionalInterface
    private interface Interruptible {
        void run() throws InterruptedException, ExecutionException, TimeoutException;
    }
}
