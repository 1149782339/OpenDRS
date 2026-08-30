package io.opendrs.jdbc;

import io.opendrs.migration.domain.ConnectionInfo;
import org.springframework.stereotype.Component;

@Component
public class JdbcConnectionFactory {

    public JdbcConnection open(ConnectionInfo info) {
        return JdbcConnection.open(info);
    }
}
