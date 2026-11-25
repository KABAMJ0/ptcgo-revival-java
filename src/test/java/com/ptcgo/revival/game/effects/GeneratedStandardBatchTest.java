package com.ptcgo.revival.game.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Checks the generated standard batch registration (see tools/translator/VERIFY_STANDARD.md). There is no hand port
 * for these attacks, so behaviour is verified by reading the Python source; this test only checks that the generated
 * registration builds and that every abilityID in the IR is a real catalog ability.
 */
class GeneratedStandardBatchTest {
    private static final Path IR = Path.of("tools/translator/ir/standard_batch.json");
    /** Catalog export directory; override with -Dcatalog.export=... */
    private static final Path CATALOG = Path.of(System.getProperty("catalog.export", "/home/user/catalog-export"))
            .resolve("cards");
    private static final int EXPECTED_ATTACKS = 35;

    /** One IR attack: its set code and abilityID. */
    private record Entry(String setCode, String abilityId, String title) {}

    private static List<Entry> entries() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(IR, StandardCharsets.UTF_8));
        List<Entry> out = new ArrayList<>();
        for (JsonNode card : root.get("cards")) {
            String set = card.get("set_code").asText();
            for (JsonNode attack : card.get("attacks")) {
                out.add(new Entry(set, attack.get("ability_id").asText(), attack.get("title").asText()));
            }
        }
        return out;
    }

    @Test
    void generatedRegistrationBuildsAndRegistersEveryIrAttack() throws Exception {
        List<Entry> ir = entries();
        assertEquals(EXPECTED_ATTACKS, ir.size(), "IR attack count");
        Set<String> distinct = new HashSet<>();
        for (Entry e : ir) distinct.add(e.abilityId());
        assertEquals(ir.size(), distinct.size(), "IR abilityIDs are distinct");

        EffectRegistry generated = GeneratedStandardBatch.register(EffectRegistry.empty());
        assertNotNull(generated);
        for (Entry e : ir) {
            assertNotNull(generated.attack(e.abilityId()), e.setCode() + " " + e.title() + " is registered");
        }
    }

    @Test
    void everyIrAbilityIdIsInTheCatalogExport() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(CATALOG), "catalog export not present at " + CATALOG);
        for (Entry e : entries()) {
            Path setFile = CATALOG.resolve(e.setCode() + ".json");
            assertTrue(Files.isRegularFile(setFile), "catalog file " + setFile);
            String catalog = Files.readString(setFile, StandardCharsets.UTF_8);
            assertTrue(catalog.contains(e.abilityId()),
                    e.setCode() + " " + e.title() + " abilityID " + e.abilityId() + " is in " + setFile.getFileName());
        }
    }
}
