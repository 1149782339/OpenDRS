package io.opendrs.engine.replay.util;

/**
 * Error handler
 *
 * <p>Worker threads have no caller to throw to, so a failure
 * is parked here and rethrown on the engine thread (see {@link #checkFailure()}), where it
 * propagates through the change consumer into the Debezium engine and fails the task — the same
 * fail-fast path a synchronous apply failure takes today.
 *
 */
public class ErrorHandler {

    private volatile Throwable throwable;

    /**
     * Set exception
     *
     * @param throwable exception
     */
    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public boolean hasError() {
        return throwable != null;
    }

    public Throwable throwable() {
        return throwable;
    }

    /**
     * Rethrow the captured failure on the calling (engine) thread, unwrapping nothing. Called at
     * batch boundaries so an asynchronous worker failure surfaces exactly where a synchronous
     * apply failure would: the change consumer, which turns it into the task's failed state.
     */
    public void checkFailure() {
        Throwable failure = throwable;
        if (failure != null) {
            throw new IllegalStateException("parallel apply failed", failure);
        }
    }
}
