package io.opendrs.datacheck.domain;

/** What the check covers. */
public enum DataCheckMode {

    /** Compare the two sides' full data. */
    FULL_ONLY,

    /** Follow the change stream and compare changed rows. */
    INCREMENTAL_ONLY,

    /** Full comparison first, then follow changes. */
    FULL_AND_INCREMENTAL
}
