package com.ptcgo.revival.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchetypeEncoderTest {
    /** Produced by the Python protobuf runtime (scratch script gen_proto_oracle.py) from the same inputs. */
    private static final String ORACLE = "0aa1010a120957a049b0adb95aaf1115503ca9a442036b121908b89e0112130808220f7b226964223a22582e4e616d65227d"
            + "120a08d4bc181204080530321206080a120208051211080d120d080530fdffffffffffffffff01120a0894c4181204080428011207089203"
            + "120208041208089ca40112020803121c0894a501121608074212095cadea56de1a6a8511105bb04d92001d00120c08d804120708063d0000c03f"
            + "120e7370697269745f53575348395f311a055357534839";

    private static final String ATTRS = """
            {"10140":{"type":"json","value":"{\\"id\\":\\"X.Name\\"}"},
             "200490":{"type":"int","value":50},
             "5":{"type":"int","value":0},
             "-7":{"type":"int","value":-3},
             "200970":{"type":"bool","value":true},
             "201":{"type":"bool","value":false},
             "10510":{"type":"string","value":""},
             "10570":{"type":"uuid","value":"001d0092-4db0-5b10-856a-1ade56eaad5c"},
             "300":{"type":"float","value":1.5}}""";

    @Test
    void matchesPythonProtobufByteForByte() throws Exception {
        JsonNode attrs = Catalog.JSON.readTree(ATTRS);
        byte[] entry = ArchetypeEncoder.archetype("6b0342a4-a93c-5015-af5a-b9adb049a057", attrs, null);
        byte[] found = ArchetypeEncoder.found(entry, List.of(), "spirit_SWSH9_1", "SWSH9");
        assertEquals(ORACLE.replace(" ", ""), HexFormat.of().formatHex(found));
    }

    @Test
    void productKeyOverridesCacheKeyAndExpansion() throws Exception {
        JsonNode attrs = Catalog.JSON.readTree("{\"200580\":{\"type\":\"string\",\"value\":\"core\"}}");
        String withOverride = HexFormat.of().formatHex(ArchetypeEncoder.archetype("001d0092-4db0-5b10-856a-1ade56eaad5c", attrs, "coins"));
        assertTrue(withOverride.contains(HexFormat.of().formatHex("coins".getBytes())));
        assertFalse(withOverride.contains(HexFormat.of().formatHex("core".getBytes())));
        // expansion (10020) is added even though the product didn't carry it
        assertTrue(withOverride.split(HexFormat.of().formatHex("coins".getBytes()), -1).length == 3);
    }
}
