package io.opendrs.engine.replay.event;

/**
 * Transaction Event.
 * See #dispatchTransactionCommittedEvent
 *
 */
public class TransactionEvent extends ChangeEvent {
    private String transactionId;

    private Status status;

    @Override
    public String getTransactionId() {
        return transactionId;
    }

    public Status getStatus() {
        return status;
    }

    public enum Status {
        BEGIN,
        END
    }

    public static Builder build() {
        return new Builder();
    }

    public static class Builder {
        private String topic;

        private long offset;

        private Integer partition;

        private String transactionId;

        private Status status;

        private Builder() {
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder offset(long offset) {
            this.offset = offset;
            return this;
        }

        public Builder partition(Integer partition) {
            this.partition = partition;
            return this;
        }

        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public TransactionEvent build() {
            TransactionEvent transactionEvent = new TransactionEvent();
            transactionEvent.status = status;
            transactionEvent.transactionId = transactionId;
            transactionEvent.offset = offset;
            transactionEvent.topic = topic;
            transactionEvent.partition = partition;
            return transactionEvent;
        }

    }
}
