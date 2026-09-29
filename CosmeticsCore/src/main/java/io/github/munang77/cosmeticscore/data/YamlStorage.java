package io.github.munang77.cosmeticscore.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@code data/<uuid>.yml} 파일 하나에 한 명씩. 임시 파일에 쓴 뒤 바꿔 끼워서 쓰다 끊겨도 원본이 안 깨진다. */
public final class YamlStorage implements Storage {

    private final Path dir;
    private final Logger log;

    public YamlStorage(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    public Path dir() {
        return dir;
    }

    private Path file(UUID uuid) {
        return dir.resolve(uuid + ".yml");
    }

    @Override
    public String read(UUID uuid) throws IOException {
        Path path = file(uuid);
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }

    @Override
    public void write(UUID uuid, String yaml) throws IOException {
        Path target = file(uuid);
        Path tmp = target.resolveSibling(uuid + ".yml.tmp");
        Files.createDirectories(dir);
        Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public boolean quarantine(UUID uuid, String raw) {
        Path path = file(uuid);
        Path broken = path.resolveSibling(uuid + ".broken-" + System.currentTimeMillis() + ".yml");
        try {
            Files.move(path, broken);
            log.warning(path.getFileName() + " 형식이 깨져 있어 " + broken.getFileName() + " 로 옮기고 새로 시작합니다.");
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "깨진 데이터 파일을 옮기지 못했습니다: " + path, e);
            return false;
        }
    }

    @Override
    public String describe() {
        return "YAML (" + dir + ")";
    }

    @Override
    public void close() {
    }
}
