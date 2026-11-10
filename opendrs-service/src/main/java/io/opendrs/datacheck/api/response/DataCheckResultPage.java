package io.opendrs.datacheck.api.response;

import java.util.List;

/** One page of inconsistent rows, optionally filtered by table and flag. */
public record DataCheckResultPage(List<DataCheckResultItem> items, long total, int page, int size) {
}
