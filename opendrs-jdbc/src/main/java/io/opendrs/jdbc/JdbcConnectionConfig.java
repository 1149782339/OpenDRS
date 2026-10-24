package io.opendrs.jdbc;

import io.opendrs.common.metadata.DatabaseType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Neutral connection descriptor for the jdbc module. Migration-facing concepts
 * (stored {@code JdbcConnectionConfig}) are mapped into this type by the caller, so the
 * module stays free of the migration domain.
 */
public final class JdbcConnectionConfig {

    private final DatabaseType type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final Map<String, Object> extras;

    private JdbcConnectionConfig(Builder builder) {
        this.type = builder.type;
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.username = builder.username;
        this.password = builder.password == null ? "" : builder.password;
        this.extras = builder.extras;
    }

    public DatabaseType getType() {
        return type;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /** Driver/connection extras (ssl mode, timezone, pdb, ...); never null. */
    public Map<String, Object> getExtras() {
        return extras;
    }

    public Object extra(String key) {
        return extras == null ? null : extras.get(key);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private DatabaseType type;
        private String host;
        private int port;
        private String database;
        private String username;
        private String password;
        private Map<String, Object> extras = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder type(DatabaseType type) {
            this.type = type;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder extras(Map<String, Object> extras) {
            this.extras = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
            return this;
        }

        public JdbcConnectionConfig build() {
            return new JdbcConnectionConfig(this);
        }
    }
}
