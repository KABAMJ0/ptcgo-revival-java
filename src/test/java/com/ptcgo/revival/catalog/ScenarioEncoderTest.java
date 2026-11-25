package com.ptcgo.revival.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ScenarioEncoderTest {
    /** all_scenarios.hex is the output of the Python handler (handle_get_protobuf_scenarios) for the same data. */
    @Test
    void matchesPythonByteForByte() throws Exception {
        String expected;
        try (InputStream in = getClass().getResourceAsStream("/oracle/all_scenarios.hex")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertEquals(expected, HexFormat.of().formatHex(ScenarioEncoder.allScenarios()));
    }
}
