package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.LifeCycle;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.transaction.SequenceNo;
import io.opendrs.engine.replay.util.ErrorHandler;
import io.opendrs.engine.replay.util.WorkerThread;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * dispatch transaction event to applier worker {@link ApplierWorker}
 *
 * <p>The phase-1 {@link #awaitQuiescent()} barrier: the embedded engine commits offsets only
 * after the change
 * consumer returns, so the consumer must not return while transactions are still in flight —
 * apply() blocks until everything dispatched so far has completed (or failed).
 *
 */
public class TransactionDispatcher extends WorkerThread implements LifeCycle {

    /** Creates one worker; replaced in tests to avoid real JDBC connections. */
    @FunctionalInterface
    public interface WorkerFactory {
        ApplierWorker create(TransactionDispatcher dispatcher);
    }

    private final BlockingQueue<ApplierEvent> queue;

    private final SequenceNo maxCommittedSeqNo;

    private Long maxProcessedSeqNo = 0L;

    private final Queue<Long> processingSeqNoQueue;

    private List<ApplierWorker> applierWorkers;

    private final ReentrantLock lock = new ReentrantLock();

    private final ReplayConfig config;

    /** Dispatched-but-not-yet-completed transactions; guarded by {@link #quietLock}. */
    private long outstandingTransactions;

    /** Cumulative completions, incremented once per {@link #onTransactionComplete}; barrier target. */
    private long completedCount;

    private final Object quietLock = new Object();

    public TransactionDispatcher(
            BlockingQueue<ApplierEvent> queue,
            ConnectorContext context,
            ErrorHandler errorHandler,
            ReplayConfig config) {
        this(queue, context, errorHandler, config,
                dispatcher -> new ApplierWorker(dispatcher, context, errorHandler, config));
    }

    TransactionDispatcher(
            BlockingQueue<ApplierEvent> queue,
            ConnectorContext context,
            ErrorHandler errorHandler,
            ReplayConfig config,
            WorkerFactory workerFactory) {
        super(context, errorHandler);
        this.queue = queue;
        this.maxCommittedSeqNo = new SequenceNo();
        this.processingSeqNoQueue = new LinkedList<>();
        this.config = config;
        this.applierWorkers = new ArrayList<>(config.getTxApplierConcurrency());
        for (int i = 0; i < config.getTxApplierConcurrency(); i++) {
            this.applierWorkers.add(workerFactory.create(this));
        }
    }

    @Override
    public void execute() throws Exception {
        while (context.isRunning()) {
            if (errorHandler.hasError()) {
                // A worker failed; stop feeding so the engine thread's awaitQuiescent() can
                // surface the error instead of this thread spinning on a sequence that will
                // never commit.
                return;
            }
            ApplierEvent applierEvent = queue.take();
            Long lastCommitted = applierEvent.getLastCommitted();
            Long sequenceNumber = applierEvent.getSequenceNumber();
            if (maxProcessedSeqNo == 0) {
                maxCommittedSeqNo.setSequenceNumber(lastCommitted);
            }
            maxCommittedSeqNo.waitSeqNoGreatEquals(lastCommitted);
            lock.lock();
            try {
                processingSeqNoQueue.add(sequenceNumber);
                maxProcessedSeqNo = sequenceNumber;
                outstandingTransactions++;
            } finally {
                lock.unlock();
            }
            ApplierWorker applierWorker = advise();
            applierWorker.feed(applierEvent);
        }
    }

    /**
     * Callback from a worker that finished applying a transaction, successfully or not; also
     * advances the commit watermark so conflicting successors can dispatch. Called exactly once
     * per fed event (worker side is try/finally), so the barrier below always wakes.
     */
    public void onTransactionComplete(Long sequenceNo) {
        lock.lock();
        try {
            for (Long processingSeqNo : processingSeqNoQueue) {
                if (processingSeqNo.equals(sequenceNo)) {
                    processingSeqNoQueue.remove(sequenceNo);
                    break;
                }
            }
            Long processingSeqNo = processingSeqNoQueue.peek();
            if (processingSeqNo != null) {
                maxCommittedSeqNo.setSequenceNumber(processingSeqNo - 1);
            } else {
                maxCommittedSeqNo.setSequenceNumber(maxProcessedSeqNo);
            }
            outstandingTransactions--;
        } finally {
            lock.unlock();
        }
        synchronized (quietLock) {
            completedCount++;
            quietLock.notifyAll();
        }
    }

    /**
     * Phase-1 barrier: block until every event the caller dispatched (cumulative {@code target})
     * has completed. Counting completions — not "nothing in flight" — closes the race where the
     * dispatcher thread has not yet taken a queued event (so nothing is in flight) while the
     * caller is already at the barrier; the change consumer relies on this to keep Debezium
     * offset commits at-least-once without touching the offset store. Throws as soon as a worker
     * failure is observed, so the caller fails fast instead of draining.
     */
    public void awaitQuiescent(long target) {
        synchronized (quietLock) {
            while (completedCount < target) {
                errorHandler.checkFailure();
                try {
                    // Bounded wait so an error set without a completion callback (e.g. the
                    // dispatcher itself died) still surfaces here.
                    quietLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorHandler.checkFailure();
                    return;
                }
            }
        }
        errorHandler.checkFailure();
    }

    /**
     * Advise an applier worker {@link ApplierWorker} to apply a transaction,
     * the policy is to choose the least loaded one.
     *
     * @return applier worker {@link ApplierWorker}
     */
    private ApplierWorker advise() {
        ApplierWorker applierWorker = applierWorkers.get(0);
        for (int i = 1; i < applierWorkers.size(); i++) {
            if (applierWorkers.get(i).getApplierEventQueueSize() < applierWorker.getApplierEventQueueSize()) {
                applierWorker = applierWorkers.get(i);
            }
        }
        return applierWorker;
    }

    @Override
    public void prepare(TaskContext taskContext) {
        for (ApplierWorker applierWorker : applierWorkers) {
            applierWorker.prepare(taskContext);
            applierWorker.start();
        }
    }

    @Override
    public void release() {
        for (ApplierWorker applierWorker : applierWorkers) {
            applierWorker.stop();
        }
        for (ApplierWorker applierWorker : applierWorkers) {
            applierWorker.release();
        }
    }
}
