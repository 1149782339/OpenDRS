package io.opendrs.engine.replay.transaction;

/**
 * Sequence
 *
 */
public class SequenceNo {

    private volatile long sequenceNumber;

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public void waitSeqNoGreatEquals(long lastCommitted) throws InterruptedException {
        while (sequenceNumber < lastCommitted) {
            Thread.sleep(0);
        }
    }
}
