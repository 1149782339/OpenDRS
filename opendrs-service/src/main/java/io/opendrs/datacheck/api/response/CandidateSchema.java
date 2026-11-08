package io.opendrs.datacheck.api.response;

import java.util.List;

/** One schema of the migration task's source scope, with the tables a check may cover. */
public record CandidateSchema(String schema, List<String> tables) {
}
