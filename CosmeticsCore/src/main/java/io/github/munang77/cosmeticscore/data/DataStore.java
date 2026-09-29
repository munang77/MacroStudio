package io.github.munang77.cosmeticscore.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;

/**
 * 접속 중인 플레이어의 데이터를 메모리에 두고, {@link Storage} 에 읽고 쓴다.
 *
 * <p>모든 읽기/쓰기와 "메모리에 올리기/내리기"는 입출력 스레드 하나에서 순서대로 한다. 그래서
 * <ul>
 *   <li>로그인할 때 로그인 스레드에서 미리 읽어 두므로 메인 스레드(서버 틱)가 멈추지 않고,</li>
 *   <li>"나가면서 저장 → 다시 들어와서 읽기" 순서가 뒤집히지 않으며,</li>
 *   <li>지급/회수는 메모리에 있으면 그 데이터를, 없으면 저장소를 고치므로 두 번 적용되거나 덮어써지지 않는다.</li>
 * </ul>
 */
public final class DataStore {

    /** 메모리에 올라온 한 명. {@code logins} 는 같은 계정의 로그인 수 (중복 접속으로 잠깐 2가 될 수 있다). */
    private static final class Entry {
        final PlayerData data;
        final long created = System.currentTimeMillis();
        volatile boolean active;
        int logins = 1;

        Entry(PlayerData data) {
            this.data = data;
        }
    }

    private static final long STALE_LOGIN_MS = 120_000;

    private final Storage storage;
    private final Logger log;
    private final long loginDelayMs;
    private final Map<UUID, Entry> known = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CosmeticsCore-IO");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param loginDelayMs 로그인 때 읽기 전에 기다릴 시간. 서버 여러 대가 MySQL 을 같이 쓸 때
     *                     이전 서버의 퇴장 저장이 먼저 끝나도록 잠깐 기다린다.
     */
    public DataStore(Storage storage, Logger log, long loginDelayMs) {
        this.storage = storage;
        this.log = log;
        this.loginDelayMs = Math.max(0, loginDelayMs);
    }

    public Storage storage() {
        return storage;
    }

    // ── 접속 ─────────────────────────────────────

    /** 로그인 스레드(AsyncPlayerPreLoginEvent)에서: 저장소에서 미리 읽어 둔다. 서버 틱을 멈추지 않는다. */
    public void preload(UUID uuid, String name) {
        if (loginDelayMs > 0) {
            try {
                Thread.sleep(loginDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            io.submit(() -> {
                Entry entry = known.get(uuid);
                if (entry != null) {
                    // 같은 계정이 아직 접속해 있다 (중복 접속) → 같은 데이터를 이어서 쓴다
                    entry.logins++;
                    return;
                }
                PlayerData data = read(uuid);
                data.setLastName(name);
                known.put(uuid, new Entry(data));
            }).get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warning(name + " 의 데이터를 읽는 데 오래 걸려 접속한 뒤에 불러옵니다.");
        } catch (Exception e) {
            log.log(Level.WARNING, name + " 의 데이터를 미리 읽지 못했습니다.", e);
        }
    }

    /** 로그인이 거절됐을 때: 미리 읽은 데이터를 버린다. */
    public void release(UUID uuid) {
        io.execute(() -> {
            Entry entry = known.get(uuid);
            if (entry != null && --entry.logins <= 0 && !entry.active) {
                known.remove(uuid);
            }
        });
    }

    /**
     * 접속 처리(메인 스레드): 미리 읽은 데이터를 쓰기 시작한다.
     *
     * @return 미리 읽지 못했으면 {@code null} → {@link #loadAsync} 를 쓴다
     */
    public PlayerData activate(UUID uuid, String name) {
        Entry entry = known.get(uuid);
        if (entry == null) {
            return null;
        }
        entry.data.setLastName(name);
        entry.active = true;
        return entry.data;
    }

    /** 미리 읽지 못했을 때(플러그인 리로드, 시간 초과): 백그라운드에서 읽는다. 메인 스레드는 기다리지 않는다. */
    public CompletableFuture<PlayerData> loadAsync(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> {
            Entry entry = known.get(uuid);
            if (entry == null) {
                entry = new Entry(read(uuid));
                known.put(uuid, entry);
            }
            entry.data.setLastName(name);
            entry.active = true;
            return entry.data;
        }, io);
    }

    /** 접속 중인 플레이어의 데이터. 아직 못 읽었거나 접속하지 않았으면 {@code null}. */
    public PlayerData get(UUID uuid) {
        Entry entry = known.get(uuid);
        return entry != null && entry.active ? entry.data : null;
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
        io.execute(() -> {
            Entry entry = known.get(uuid);
            if (entry == null) {
                return;
            }
            if (!entry.data.readOnly()) {
                write(uuid, entry.data.serialize());
            }
            if (--entry.logins <= 0) {
                known.remove(uuid);
            }
        });
    }

    /** 오래도록 접속으로 이어지지 않은 로그인(연결이 끊긴 경우)의 데이터를 버린다. */
    public void sweepStaleLogins() {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            known.values().removeIf(e -> !e.active && now - e.created > STALE_LOGIN_MS);
        });
    }

    // ── 관리자 수정 ──────────────────────────────

    /**
     * 데이터를 고친다 (백그라운드). 메모리에 올라와 있으면 그 데이터를, 아니면 저장소를 고친다.
     *
     * @param edit 고쳤으면 {@code true} 를 돌려준다. 메모리 데이터에 쓰일 수 있으므로 스레드에 안전한 동작만 한다.
     * @return 고쳤는지 여부
     */
    public CompletableFuture<Boolean> edit(UUID uuid, Predicate<PlayerData> edit) {
        return CompletableFuture.supplyAsync(() -> {
            Entry entry = known.get(uuid);
            PlayerData data = entry != null ? entry.data : read(uuid);
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

    /** 이 플레이어의 데이터가 있는지 (서버 여러 대가 같은 저장소를 쓸 때 다른 서버에서 들어온 적이 있는지). */
    public CompletableFuture<Boolean> exists(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (known.containsKey(uuid)) {
                return true;
            }
            try {
                return storage.read(uuid) != null;
            } catch (Exception e) {
                return false;
            }
        }, io);
    }

    /**
     * YAML 폴더의 데이터를 지금 저장소(SQLite/MySQL)로 옮긴다. 이미 있는 데이터(접속 중인 플레이어 포함)와
     * 합치므로 여러 번 실행해도 안전하다.
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
                    PlayerData imported = PlayerData.deserialize(uuid, Files.readString(file, StandardCharsets.UTF_8));
                    Entry entry = known.get(uuid);
                    PlayerData target = entry != null ? entry.data : read(uuid);
                    if (target.readOnly()) {
                        continue;
                    }
                    target.mergeFrom(imported);
                    write(uuid, target.serialize());
                    count++;
                }
            } catch (Exception e) {
                throw new IllegalStateException("옮기다가 멈췄습니다 (" + count + "명 완료): " + e.getMessage(), e);
            }
            return count;
        }, io);
    }

    /** 앞서 맡긴 읽기/쓰기가 모두 끝날 때까지 기다린다. */
    public void flush() {
        try {
            io.submit(() -> { }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.WARNING, "저장 대기 중 오류", e);
        }
    }

    /** 서버가 꺼질 때: 접속 중인 데이터를 모두 저장하고 쓰기가 끝날 때까지 기다린다. */
    public void shutdown() {
        List<PlayerData> active = new ArrayList<>();
        for (Entry entry : known.values()) {
            if (entry.active) {
                active.add(entry.data);
            }
        }
        for (PlayerData data : active) {
            save(data);
        }
        io.execute(() -> {
            known.clear();
            storage.close();
        });
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
            log.log(Level.SEVERE, uuid + " 의 데이터를 읽지 못했습니다. 이번 접속 동안은 저장하지 않습니다.", e);
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
}
