package io.opendrs.engine.replay.util;

import io.opendrs.engine.replay.context.ConnectorContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker Thread
 *
 */
public abstract class WorkerThread implements Runnable {

    private final static Logger LOGGER = LoggerFactory.getLogger(WorkerThread.class);

    private final static long MAX_AWAIT_TIME = 60;

    private volatile boolean isStopping;

    private final ExecutorService executor;

    protected final ErrorHandler errorHandler;

    protected final ConnectorContext context;

    public WorkerThread(ConnectorContext context, ErrorHandler errorHandler) {
        this.isStopping = false;
        this.executor = Executors.newSingleThreadExecutor();
        this.context = context;
        this.errorHandler = errorHandler;
    }

    /**
     * Start thread
     *
     */
    public synchronized void start() {
        executor.submit(this);
    }

    /**
     * Stop thread
     *
     */
    public synchronized void stop() {
        // Set isStopping=true here: otherwise run() reports every shutdown
        // interrupt as an unexpected failure; stopping means failures are expected.
        isStopping = true;
        executor.shutdownNow();
        try {
            executor.awaitTermination(MAX_AWAIT_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("interrupted when stop,ignore it", e);
        }
    }

    /**
     * This method is called when thread is started
     * Thread main Subclasses of Thread should override this method.
     *
     */
    protected abstract void execute() throws Exception;

    @Override
    public final void run() {
        try {
            execute();
        } catch (Throwable e) {
            if (isStopping) {
                LOGGER.trace("Worker thread is interrupted", e);
            } else {
                errorHandler.setThrowable(e);
            }
        }
    }
}
