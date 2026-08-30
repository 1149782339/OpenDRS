package io.opendrs.precheck;

import io.opendrs.jdbc.metadata.Table;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.List;

public interface DbPreCheck {

    DbType type();

    void validate(ConnectionInfo info, List<Table> tables);

    List<CheckResult> precheckSource(ConnectionInfo info, List<Table> tables);

    List<CheckResult> precheckTarget(ConnectionInfo info, List<Table> tables);
}
