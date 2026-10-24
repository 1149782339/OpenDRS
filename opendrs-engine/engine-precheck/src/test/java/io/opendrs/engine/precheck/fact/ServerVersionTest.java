package io.opendrs.engine.precheck.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerVersionTest {

    @Test
    void parsesTheLeadingNumbersAndKeepsTheRawString() {
        ServerVersion version = ServerVersion.parse("8.0.36-log");

        assertEquals(8, version.major());
        assertEquals(0, version.minor());
        assertEquals(36, version.patch());
        assertEquals("8.0.36-log", version.raw());
        assertEquals("8.0.36", version.display());
    }

    @Test
    void distributionSuffixesDoNotConfuseTheMinorVersion() {
        ServerVersion version = ServerVersion.parse("17.2 (Debian 17.2-1.pgdg120+1)");

        assertEquals(17, version.major());
        assertEquals(2, version.minor());
        assertTrue(version.atLeast(17, 0));
    }

    @Test
    void majorWinsOverMinor() {
        assertTrue(ServerVersion.parse("8.4.0").atLeast(8, 0));
        assertFalse(ServerVersion.parse("5.7.44").atLeast(8, 0));
        assertFalse(ServerVersion.parse("16.9").atLeast(17, 0));
        assertTrue(ServerVersion.parse("17.0").atLeast(17, 0));
    }

    @Test
    void unparsableVersionNeverClaimsSupport() {
        assertFalse(ServerVersion.parse("unknown").atLeast(8, 0));
        assertFalse(ServerVersion.parse(null).atLeast(8, 0));
    }
}
