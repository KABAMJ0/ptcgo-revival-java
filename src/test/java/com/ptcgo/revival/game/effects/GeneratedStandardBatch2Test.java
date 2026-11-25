package com.ptcgo.revival.game.effects;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Checks the generated standard batch two (see tools/translator/manifests/standard_batch_2.json). The IR is the
 * committed source of truth for the Java class: IDs must be distinct, must not collide with any registration already
 * in the tree, and must each appear in the catalogue export.
 */
class GeneratedStandardBatch2Test {
    private static final Path IR = Path.of("tools/translator/ir/standard_batch_2.json");
    /** Catalog export directory; override with -Dcatalog.export=... */
    private static final Path CARDS = Path.of(System.getProperty("catalog.export", "/home/user/catalog-export"))
            .resolve("cards");
    private static final int EXPECTED_ATTACKS = 938;

    /** One IR attack: its set code, abilityID and title. */
    private record Entry(String setCode, String abilityId, String title) {}

    private static List<Entry> entries() throws IOException {
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
    void batchHasTheExpectedCountAndNoDuplicateIds() throws Exception {
        List<Entry> ir = entries();
        assertEquals(EXPECTED_ATTACKS, ir.size(), "IR attack count");
        Set<String> distinct = new HashSet<>();
        for (Entry e : ir) {
            assertTrue(distinct.add(e.abilityId()), "duplicate abilityID " + e.abilityId() + " (" + e.title() + ")");
        }
        assertEquals(ir.size(), distinct.size());
    }

    @Test
    void generatedRegistrationRegistersEveryIrAttack() throws Exception {
        EffectRegistry generated = GeneratedStandardBatch2.register(EffectRegistry.empty());
        for (Entry e : entries()) {
            assertNotNull(generated.attack(e.abilityId()), e.setCode() + " " + e.title() + " is registered");
        }
    }

    @Test
    void noAbilityIdOverlapsAnExistingRegistration() throws Exception {
        EffectRegistry existing = GeneratedBatchOne.register(EffectRegistry.empty());
        GeneratedStandardBatch.register(existing);
        BatchTwoAttacks.register(existing);
        BatchOneAttacks.register(existing);
        for (Entry e : entries()) {
            assertNull(existing.attack(e.abilityId()),
                    e.setCode() + " " + e.title() + " collides with an existing registration");
        }
    }

    @Test
    void everyIrAbilityIdIsInTheCatalogExport() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(CARDS), "catalog export not present at " + CARDS);
        // The export is ~83 MB, so collect every UUID in one regex pass instead of scanning it once per abilityID.
        Set<String> uuids = new HashSet<>();
        Pattern uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        try (Stream<Path> files = Files.list(CARDS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                Matcher m = uuid.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) uuids.add(m.group());
            }
        }
        for (Entry e : entries()) {
            assertTrue(uuids.contains(e.abilityId()),
                    e.setCode() + " " + e.title() + " abilityID " + e.abilityId() + " is in the catalog export");
        }
    }
}
