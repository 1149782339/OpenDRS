package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.annotation.ThreadSafe;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.common.metadata.TableId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Table-parallel applier for the full-load phase: snapshot rows ({@code Operation.READ}) carry
 * no transaction context, so a batch is partitioned by table and each table's events go to one
 * dedicated JDBC executor. The apply call is a synchronous barrier — it returns only when every
 * table partition has been applied, so the change consumer's offset bookkeeping needs no change.
 */
@ThreadSafe
public class TableApplier implements Applier<Collection<ChangeEvent>> {
    private final static Logger LOGGER = LoggerFactory.getLogger(TableApplier.class);

    private final static long MAX_AWAIT_TIME = 60;

    private final CompletionService<Void> completionService;

    private final ExecutorService executorService;

    private final List<Applier<Collection<ChangeEvent>>> appliers;

    public TableApplier(ConnectorContext context, ReplayConfig config) {
        this(context, config, () -> ApplierFactory.createJdbcApplier(context, config));
    }

    /** Test seam: inject fake JDBC executors. */
    TableApplier(
            ConnectorContext context,
            ReplayConfig config,
            Supplier<Applier<Collection<ChangeEvent>>> jdbcApplierFactory) {
        this.executorService = Executors.newFixedThreadPool(config.getTableApplierConcurrency());
        this.completionService = new ExecutorCompletionService<>(executorService);
        this.appliers = new ArrayList<>(config.getTableApplierConcurrency());
        for (int i = 0; i < config.getTableApplierConcurrency(); i++) {
            this.appliers.add(jdbcApplierFactory.get());
        }
    }

    @Override
    public void prepare(TaskContext taskContext) {
        for (Applier<Collection<ChangeEvent>> applier : appliers) {
            applier.prepare(taskContext);
        }
    }

    @Override
    public synchronized void release() {
        for (Applier<Collection<ChangeEvent>> applier : appliers) {
            applier.release();
        }
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(MAX_AWAIT_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("interrupted when stop,ignore it", e);
        }
    }

    @Override
    public synchronized void apply(Collection<ChangeEvent> changeEvents) throws ApplierException {
        if (changeEvents == null || changeEvents.isEmpty()) {
            return;
        }
        Map<TableId, List<ChangeEvent>> tableEvents = new HashMap<>();
        for (ChangeEvent changeEvent : changeEvents) {
            if (changeEvent instanceof DataChangeEvent dataChangeEvent) {
                TableId tableId = dataChangeEvent.getTableId();
                List<ChangeEvent> events = tableEvents.computeIfAbsent(tableId, id -> new LinkedList<>());
                events.add(dataChangeEvent);
            }
            // TransactionEvent / SchemaChangeEvent do not occur in the full-load path; the
            // routing applier sends only READ rows here and everything else to the
            // transaction applier.
        }
        for (Map.Entry<TableId, List<ChangeEvent>> entry : tableEvents.entrySet()) {
            final List<ChangeEvent> events = entry.getValue();
            // floorMod: Math.abs(Integer.MIN_VALUE) stays negative and
            // would index out of bounds.
            final Applier<Collection<ChangeEvent>> applier =
                    appliers.get(Math.floorMod(entry.getKey().hashCode(), appliers.size()));
            completionService.submit(() -> {
                applier.apply(events);
                return null;
            });
        }
        try {
            for (int i = 0; i < tableEvents.size(); i++) {
                completionService.take().get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplierException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApplierException applierException) {
                throw applierException;
            }
            throw new ApplierException(cause);
        }
    }
}
