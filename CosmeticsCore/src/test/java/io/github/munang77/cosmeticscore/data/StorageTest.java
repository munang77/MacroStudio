package io.github.munang77.cosmeticscore.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.munang77.cosmeticscore.cosmetic.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path dir;

    @Test
    void sqliteReadsWritesAndUpserts() throws Exception {
        SqlStorage sql = SqlStorage.sqlite(dir.resolve("data.db"), "cosmetics_test", dir.resolve("broken"), LOG);
        sql.open();
        UUID id = UUID.randomUUID();
        assertNull(sql.read(id));
        sql.write(id, "a: 1\n");
        sql.write(id, "a: 2\n");
        assertEquals("a: 2\n", sql.read(id));
        assertTrue(sql.quarantine(id, "broken: [\n"));
        try (Stream<Path> files = Files.list(dir.resolve("broken"))) {
            assertEquals(1, files.count());
        }
        sql.close();
    }

    @Test
    void tableNameIsValidated() {
        try {
            SqlStorage.sqlite(dir.resolve("x.db"), "players; DROP TABLE x", dir, LOG);
            throw new AssertionError("잘못된 표 이름을 받아들임");
        } catch (IllegalArgumentException expected) {
            // 통과
        }
    }

    @Test
    void dataStoreRoundTripOfflineEditAndBrokenFile() throws Exception {
        YamlStorage yaml = new YamlStorage(dir.resolve("data"), LOG);
        DataStore store = new DataStore(yaml, LOG);
        UUID id = UUID.randomUUID();

        PlayerData data = store.load(id, "Steve");
        assertTrue(data.unlocked().isEmpty());
        data.unlock("crown");
        data.setEquipped(Category.HAT, "crown");
        data.addKeys(3);
        store.unload(id);

        assertTrue(store.editOffline(id, d -> d.unlock("red_wings")).get());
        assertFalse(store.editOffline(id, d -> d.unlock("red_wings")).get());

        PlayerData again = store.load(id, "Steve");
        assertEquals(List.of("crown", "red_wings"), again.unlocked().stream().sorted().toList());
        assertEquals("crown", again.equipped(Category.HAT));
        assertEquals(3, again.keys());
        assertTrue(again.takeKey());
        assertEquals(2, again.keys());
        store.unload(id);
        store.flush();

        // 깨진 파일은 옆으로 치우고 빈 데이터로 시작한다
        UUID broken = UUID.randomUUID();
        Files.writeString(dir.resolve("data").resolve(broken + ".yml"), "unlocked: [oops\n", StandardCharsets.UTF_8);
        PlayerData fresh = store.load(broken, "Alex");
        assertFalse(fresh.readOnly());
        assertTrue(fresh.unlocked().isEmpty());
        try (Stream<Path> files = Files.list(dir.resolve("data"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith(broken + ".broken-")));
        }
        store.shutdown();
    }

    @Test
    void importYamlIntoSqlite() throws Exception {
        YamlStorage yaml = new YamlStorage(dir.resolve("data"), LOG);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PlayerData pa = new PlayerData(a);
        pa.unlock("crown");
        yaml.write(a, pa.serialize());
        yaml.write(b, new PlayerData(b).serialize());
        Files.writeString(dir.resolve("data").resolve("not-a-uuid.yml"), "x: 1");

        SqlStorage sql = SqlStorage.sqlite(dir.resolve("data.db"), "cosmeticscore_players", dir.resolve("broken"), LOG);
        sql.open();
        DataStore store = new DataStore(sql, LOG);
        assertEquals(2, store.importYaml(dir.resolve("data")).get());
        assertTrue(store.load(a, "A").hasUnlocked("crown"));
        store.shutdown();
    }
}
