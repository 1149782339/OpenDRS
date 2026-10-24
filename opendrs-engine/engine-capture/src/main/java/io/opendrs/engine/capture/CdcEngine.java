package io.opendrs.engine.capture;

/**
 * One capture Engine, owned by the thread that calls {@link #run()}.
 *
 * <p>This is a lifecycle handle, not a unit of work, so it deliberately does not implement
 * {@link Runnable}. {@link #run()} takes over the calling thread and returns only once the engine
 * has stopped, and {@link #stop()} has to come from another thread to make that happen. Handing the
 * instance to an executor would also bury the failure it throws inside a {@code Future}, which is
 * exactly the signal the task handles need in order to report {@code FAILED}.
 */
public interface CdcEngine {

    /**
     * Runs the capture on the calling thread and blocks until {@link #stop()} is called or the
     * engine terminates on its own. Throws when the engine failed.
     */
    void run();

    /**
     * Cooperative shutdown (Debezium 3.6 {@code AsyncEmbeddedEngine} exposes this as
     * {@code close()}). Safe from another thread, and safe before, during or after {@link #run()}.
     */
    void stop();
}
