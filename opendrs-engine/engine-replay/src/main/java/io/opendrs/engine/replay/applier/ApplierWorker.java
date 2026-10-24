package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.LifeCycle;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.util.ErrorHandler;
import io.opendrs.engine.replay.util.WorkerThread;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime Applier worker using jdbc applier {@link JdbcApplier} to apply events {@link ApplierEvent}
 *
 */
public class ApplierWorker extends WorkerThread implements LifeCycle {

    private final static Logger LOGGER = LoggerFactory.getLogger(ApplierWorker.class);
    private final BlockingQueue<ApplierEvent> applierEvents;

    private final TransactionDispatcher dispatcher;

    private final Applier<Collection<ChangeEvent>> jdbcApplier;

    public ApplierWorker(
            TransactionDispatcher dispatcher,
            ConnectorContext context,
            ErrorHandler errorHandler,
            ReplayConfig config) {
        this(dispatcher, context, errorHandler, config, ApplierFactory.createJdbcApplier(context, config));
    }

    /** Test seam: inject the executor instead of opening a real JDBC connection. */
    ApplierWorker(
            TransactionDispatcher dispatcher,
            ConnectorContext context,
            ErrorHandler errorHandler,
            ReplayConfig config,
            Applier<java.util.Collection<ChangeEvent>> jdbcApplier) {
        super(context, errorHandler);
        this.applierEvents = new ArrayBlockingQueue<>(config.getApplierWorkerBufferSize());
        this.dispatcher = dispatcher;
        this.jdbcApplier = jdbcApplier;
    }

    /**
     * Feed one transaction to this worker.
     *
     * @param applierEvent applier event  {@link ApplierEvent}
     * @throws InterruptedException if there is interrupted while put applier event {@link ApplierEvent} into the queue
     */
    public void feed(ApplierEvent applierEvent) throws InterruptedException {
        applierEvents.put(applierEvent);
    }

    /**
     * Applier worker main function: take one transaction, apply it through the JDBC executor,
     * then report completion so conflicting successors can dispatch and the phase-1 barrier can
     * advance. Completion is reported even on failure (try/finally) — otherwise a failed
     * transaction would deadlock the barrier and hide the real error behind a hang.
     */
    @Override
    protected void execute() throws Exception {
        while (context.isRunning()) {
            ApplierEvent applierEvent = applierEvents.take();
            Long sequenceNumber = applierEvent.getSequenceNumber();
            try {
                if (!applierEvent.getEvents().isEmpty()) {
                    jdbcApplier.apply(applierEvent.getEvents());
                }
            } catch (Exception e) {
                // 时序约束：必须先 setThrowable 登记异常，再（在 finally 里）
                // onTransactionComplete 唤醒 dispatcher/引擎线程。
                // 完成信号是引擎线程 awaitQuiescent 放行的唯一依据，若先放行后登记，
                // 引擎线程可能在登记完成前调 checkFailure，读到空登记簿，
                // 导致失败的事务被当作成功提交 offset（真实存在的竞态窗口）。
                // （外层 WorkerThread.run 会重复登记同一个 throwable，幂等无害。）
                errorHandler.setThrowable(e);
                throw e;
            } finally {
                dispatcher.onTransactionComplete(sequenceNumber);
            }
        }
    }

    @Override
    public void prepare(TaskContext taskContext) {
        jdbcApplier.prepare(taskContext);
    }

    /**
     * Get Applier event queue size {@link BlockingQueue}
     *
     * @return applier event queue size {@link BlockingQueue}
     */
    public int getApplierEventQueueSize() {
        return applierEvents.size();
    }

    @Override
    public void release() {
        jdbcApplier.release();
    }
}
