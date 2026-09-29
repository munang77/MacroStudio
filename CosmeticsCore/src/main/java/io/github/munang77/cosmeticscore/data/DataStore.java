package io.github.munang77.cosmeticscore.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;

/**
 * 접속 중인 플레이어의 데이터를 메모리에 두고, {@link Storage} 에 읽고 쓴다.
 *
 * <p>모든 읽기/쓰기는 스레드 하나에서 순서대로 처리한다. 그래서 "저장 → 다시 접속해서 읽기"
 * 순서가 뒤집히지 않고, 오프라인 지급과 접속이 겹쳐도 한쪽이 다른 쪽을 덮어쓰지 않는다.
 */
public final class DataStore {

    private final Storage storage;
    private final Logger log;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmeticsCore-IO");
        t.setDaemon(true);
        return t;
    });

    public DataStore(Storage storage, Logger log) {
        this.storage = storage;
        this.log = log;
    }

    public Storage storage() {
        return storage;
    }

    /** 접속한 플레이어의 데이터를 읽어 메모리에 올린다 (메인 스레드에서 호출, 대기 중인 저장이 끝난 뒤 읽는다). */
    public PlayerData load(UUID uuid, String name) {
        PlayerData data;
        try {
            data = io.submit(() -> read(uuid)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.SEVERE, name + " 의 코스메틱 데이터를 읽지 못했습니다. 이번 접속 동안은 저장하지 않습니다.", e);
            data = new PlayerData(uuid, true);
        }
        data.setLastName(name);
        online.put(uuid, data);
        return data;
    }

    /** 접속 중인 플레이어의 데이터. 없으면 {@code null}. */
    public PlayerData get(UUID uuid) {
        return online.get(uuid);
    }

    /** 스냅숏을 떠서 백그라운드로 저장한다. */
    public void save(PlayerData data) {
        if (data.readOnly()) {
            return;
        }
        String yaml = data.serialize();
        UUID uuid = data.uuid();
        io.execute(() -> write(uuid, yaml));
    }

    /** 나간 플레이어의 데이터를 저장하고 메모리에서 내린다. */
    public void unload(UUID uuid) {
        PlayerData data = online.remove(uuid);
        if (data != null) {
            save(data);
        }
    }

    /**
     * 접속하지 않은 플레이어의 데이터를 읽고 고쳐서 다시 쓴다 (백그라운드).
     *
     * @param edit 고쳤으면 {@code true} 를 돌려준다
     * @return 고쳤는지 여부
     */
    public CompletableFuture<Boolean> editOffline(UUID uuid, Predicate<PlayerData> edit) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerData data = read(uuid);
            if (data.readOnly()) {
                throw new IllegalStateException("데이터를 읽을 수 없습니다: " + uuid);
            }
            boolean changed = edit.test(data);
            if (changed) {
                write(uuid, data.serialize());
            }
            return changed;
        }, io);
    }

    /**
     * YAML 폴더의 데이터를 지금 저장소(SQLite/MySQL)로 옮긴다. 이미 있는 데이터는 덮어쓴다.
     *
     * @return 옮긴 플레이어 수
     */
    public CompletableFuture<Integer> importYaml(Path dir) {
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.yml")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(name.substring(0, name.length() - ".yml".length()));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    String yaml = Files.readString(file, StandardCharsets.UTF_8);
                    PlayerData.deserialize(uuid, yaml);
                    storage.write(uuid, yaml);
                    count++;
                }
            } catch (Exception e) {
                throw new IllegalStateException("옮기다가 멈췄습니다 (" + count + "명 완료): " + e.getMessage(), e);
            }
            return count;
        }, io);
    }

    /** 서버가 꺼질 때: 남은 데이터를 모두 저장하고 쓰기가 끝날 때까지 기다린다. */
    public void shutdown() {
        for (PlayerData data : new ArrayList<>(online.values())) {
            save(data);
        }
        online.clear();
        io.execute(storage::close);
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warning("코스메틱 데이터 저장이 15초 안에 끝나지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 입출력 스레드 ──

    private PlayerData read(UUID uuid) {
        String text;
        try {
            text = storage.read(uuid);
        } catch (Exception e) {
            log.log(Level.SEVERE, uuid + " 의 데이터를 읽지 못했습니다.", e);
            return new PlayerData(uuid, true);
        }
        if (text == null) {
            return new PlayerData(uuid);
        }
        try {
            return PlayerData.deserialize(uuid, text);
        } catch (InvalidConfigurationException e) {
            return storage.quarantine(uuid, text) ? new PlayerData(uuid) : new PlayerData(uuid, true);
        }
    }

    private void write(UUID uuid, String yaml) {
        try {
            storage.write(uuid, yaml);
        } catch (Exception e) {
            log.log(Level.SEVERE, uuid + " 의 데이터를 저장하지 못했습니다.", e);
        }
    }

    /** 앞서 맡긴 읽기/쓰기가 모두 끝날 때까지 기다린다. */
    public void flush() {
        try {
            io.submit(() -> { }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.WARNING, "저장 대기 중 오류", e);
        }
    }
}
