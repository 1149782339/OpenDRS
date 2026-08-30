package io.opendrs.debezium;

/**
 * One Engine instance owned by the coordinator thread. {@link #stop()} is cooperative shutdown
 * (Debezium 3.6 {@code AsyncEmbeddedEngine} exposes this as {@code close()}).
 */
public interface CdcEngine extends Runnable {

    void stop();
}
