package io.github.munang77.cosmeticscore.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /** 로그인 스레드에서 미리 읽고 접속 때 쓰기 시작하는, 실제 서버와 같은 흐름. */
    private static PlayerData login(DataStore store, UUID id, String name) {
        store.preload(id, name);
        return store.activate(id, name);
    }

    @Test
    void dataStoreRoundTripOfflineEditAndBrokenFile() throws Exception {
        YamlStorage yaml = new YamlStorage(dir.resolve("data"), LOG);
        DataStore store = new DataStore(yaml, LOG, 0);
        UUID id = UUID.randomUUID();

        assertNull(store.get(id), "로그인 전에는 없어야 함");
        PlayerData data = login(store, id, "Steve");
        assertSame(data, store.get(id));
        assertTrue(data.unlocked().isEmpty());
        data.unlock("crown");
        data.setEquipped(Category.HAT, "crown");
        data.addKeys(3);

        // 접속 중에 고치면 메모리 데이터를 한 번만 고친다
        assertTrue(store.edit(id, d -> {
            d.addKeys(5);
            return true;
        }).get());
        assertEquals(8, data.keys());
        store.unload(id);
        store.flush();
        assertNull(store.get(id));

        // 나간 뒤에는 저장소를 고친다
        assertTrue(store.edit(id, d -> d.unlock("red_wings")).get());
        assertFalse(store.edit(id, d -> d.unlock("red_wings")).get());

        PlayerData again = login(store, id, "Steve");
        assertEquals(List.of("crown", "red_wings"), again.unlocked().stream().sorted().toList());
        assertEquals("crown", again.equipped(Category.HAT));
        assertEquals(8, again.keys());
        assertTrue(again.takeKey());
        assertEquals(7, again.keys());
        store.unload(id);
        store.flush();

        // 깨진 파일은 옆으로 치우고 빈 데이터로 시작한다
        UUID broken = UUID.randomUUID();
        Files.writeString(dir.resolve("data").resolve(broken + ".yml"), "unlocked: [oops\n", StandardCharsets.UTF_8);
        PlayerData fresh = login(store, broken, "Alex");
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
        DataStore store = new DataStore(sql, LOG, 0);

        // b 는 옮기기 전에 이미 새 저장소에서 접속해 열쇠를 받았다 → 옮겨도 사라지면 안 된다
        PlayerData liveB = login(store, b, "B");
        liveB.addKeys(4);
        liveB.unlock("red_wings");

        assertEquals(2, store.importYaml(dir.resolve("data")).get());
        assertEquals(2, store.importYaml(dir.resolve("data")).get(), "두 번 옮겨도 안전해야 함");
        assertTrue(login(store, a, "A").hasUnlocked("crown"));
        assertEquals(4, liveB.keys(), "접속 중인 데이터가 덮어써지면 안 됨");
        assertTrue(liveB.hasUnlocked("red_wings"));
        store.shutdown();
    }

    @Test
    void duplicateLoginKeepsOneSharedData() throws Exception {
        DataStore store = new DataStore(new YamlStorage(dir.resolve("data"), LOG), LOG, 0);
        UUID id = UUID.randomUUID();
        PlayerData first = login(store, id, "Dup");
        first.unlock("crown");
        // 같은 계정으로 다시 로그인 → 옛 접속이 끊기기 전에 새 로그인이 먼저 온다
        store.preload(id, "Dup");
        store.unload(id);
        PlayerData second = store.activate(id, "Dup");
        assertSame(first, second, "같은 데이터를 이어서 써야 함");
        assertTrue(second.hasUnlocked("crown"));
        store.unload(id);
        store.flush();
        assertNull(store.get(id));
        store.shutdown();
    }

    @Test
    void rejectedLoginIsReleased() {
        DataStore store = new DataStore(new YamlStorage(dir.resolve("data"), LOG), LOG, 0);
        UUID id = UUID.randomUUID();
        store.preload(id, "Nope");
        store.release(id);
        store.flush();
        assertNull(store.activate(id, "Nope"), "거절된 로그인의 데이터는 버려야 함");
        store.shutdown();
    }
}
