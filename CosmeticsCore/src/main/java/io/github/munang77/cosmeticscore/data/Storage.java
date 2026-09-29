package io.github.munang77.cosmeticscore.data;

import java.util.UUID;

/**
 * 플레이어 데이터(YAML 문자열)를 어디에 둘지. 모든 메서드는 {@link DataStore} 의 입출력 스레드 하나에서만 불린다.
 */
public interface Storage {

    /** @return 저장된 내용, 없으면 {@code null} */
    String read(UUID uuid) throws Exception;

    void write(UUID uuid, String yaml) throws Exception;

    /**
     * 형식이 깨진 데이터를 따로 보관한다 (그 뒤 새 데이터로 덮어쓴다).
     *
     * @return 보관했으면 {@code true}. {@code false} 면 덮어쓰지 않도록 저장을 막는다.
     */
    boolean quarantine(UUID uuid, String raw);

    /** 로그에 보일 이름. */
    String describe();

    void close();
}
