package io.github.munang77.cosmeticscore.util;

import org.bukkit.Material;

/** 설정 파일의 아이템/블록 이름을 읽는다. 쓸 수 없는 이름이면 {@code null} (오류 문구는 부르는 쪽에서). */
public final class Materials {

    private Materials() {
    }

    /** 손에 들 수 있는 아이템 (공기 제외). */
    public static Material item(String name) {
        Material m = name == null || name.isBlank() ? null : Material.matchMaterial(name.trim());
        return m == null || m.isAir() || !m.isItem() ? null : m;
    }

    /** 블록. */
    public static Material block(String name) {
        Material m = name == null || name.isBlank() ? null : Material.matchMaterial(name.trim());
        return m == null || !m.isBlock() ? null : m;
    }
}
