package com.ptcgo.revival.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {
    @Test
    void missingDirectoryGivesEmptyCatalog(@TempDir Path dir) throws Exception {
        assertEquals(0, Catalog.load(dir).size());
    }

    @Test
    void loadsExporterFormat(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("cards"));
        Files.writeString(dir.resolve("cards/Free_Energy.json"), """
                [{"guid":"AAAA-1","key":"Free_Energy","display_name":"Fire Energy","set_code":"Free_Energy",
                  "collector_number":2,"kind":"energy","searchable_by":[],"subtypes":[],
                  "attributes":{"1":{"type":"int","value":1}},
                  "wire_attributes":{"1":{"type":"int","value":2}}}]""");
        Catalog c = Catalog.load(dir);
        assertEquals(1, c.size());
        assertEquals(java.util.Set.of("aaaa-1"), c.freeEnergyGuids());
        assertEquals(2, c.card("AAAA-1").wire().get("1").get("value").asInt()); // wire attributes win
        assertEquals("energy", c.card("aaaa-1").kind());
    }

    /** Opt-in: mvn test -Dcatalog.dir=/path/to/export (the full ~85 MB export). */
    @Test
    @EnabledIfSystemProperty(named = "catalog.dir", matches = ".+")
    void loadsFullExport() throws Exception {
        long t = System.nanoTime();
        Catalog c = Catalog.load(Path.of(System.getProperty("catalog.dir")));
        System.out.printf("Loaded %d cards, %d sets in %d ms; free energy %d%n", c.size(), c.setCodes().size(),
                (System.nanoTime() - t) / 1_000_000, c.freeEnergyGuids().size());
        assertTrue(c.size() > 20_000);
        assertEquals(177, c.setCodes().size());
    }

    /** Opt-in cross-check helper: mvn test -Dcatalog.dir=... -Dcatalog.dumpKey=SWSH9 -Dcatalog.dumpOut=/tmp/x.bin */
    @Test
    @EnabledIfSystemProperty(named = "catalog.dumpKey", matches = ".+")
    void dumpsEncodedKey() throws Exception {
        Catalog c = Catalog.load(Path.of(System.getProperty("catalog.dir")));
        String key = System.getProperty("catalog.dumpKey");
        byte[] found = ArchetypeEncoder.found(c.encoder().cardArchetypes(key), java.util.List.of(), "chk", key);
        Files.write(Path.of(System.getProperty("catalog.dumpOut")), found);
    }
}
