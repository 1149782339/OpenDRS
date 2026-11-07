package io.opendrs.migration.config;

import io.opendrs.engine.capture.transport.TransportChannel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opendrs.transport.*} — selects DIRECT (default) vs KAFKA buffering.
 * When channel is KAFKA, bootstrap servers and Apicurio URL must be reachable.
 */
@ConfigurationProperties(prefix = "opendrs.transport")
public class OpenDrsTransportProperties {

    /** {@code DIRECT} (default) or {@code KAFKA}. */
    private String channel = "DIRECT";

    private String kafkaBootstrapServers = "127.0.0.1:9092";

    /** Apicurio Registry base URL (e.g. http://127.0.0.1:8081). Reserved for schema strategy. */
    private String schemaRegistryUrl = "http://127.0.0.1:8081";

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public void setKafkaBootstrapServers(String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public String getSchemaRegistryUrl() {
        return schemaRegistryUrl;
    }

    public void setSchemaRegistryUrl(String schemaRegistryUrl) {
        this.schemaRegistryUrl = schemaRegistryUrl;
    }

    public TransportChannel channelEnum() {
        return TransportChannel.fromConfig(channel);
    }
}
