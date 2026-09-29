package io.github.munang77.cosmeticscore.cosmetic;

import java.util.Locale;
import java.util.function.Function;

import org.bukkit.Material;

/** 코스메틱 종류. 한 카테고리에는 하나만 착용할 수 있다. */
public enum Category {

    HAT("hat", "hats", Material.LEATHER_HELMET, 10),
    BACKPACK("backpack", "backpacks", Material.CHEST, 11),
    BALLOON("balloon", "balloons", Material.LEAD, 12),
    PET("pet", "pets", Material.BONE, 13),
    PARTICLE("particle", "particles", Material.BLAZE_POWDER, 14),
    ARROW_TRAIL("arrow_trail", "arrow-trails", Material.ARROW, 15),
    KILL_EFFECT("kill_effect", "kill-effects", Material.DIAMOND_SWORD, 16),
    KILL_MESSAGE("kill_message", "kill-messages", Material.WRITABLE_BOOK, 20),
    TITLE("title", "titles", Material.NAME_TAG, 21),
    CHAT_COLOR("chat_color", "chat-colors", Material.MAGENTA_DYE, 23),
    JOIN_EFFECT("join_effect", "join-effects", Material.OAK_DOOR, 24);

    /** 몸에 붙어 다니는 디스플레이 엔티티로 보여 주는 카테고리. */
    public boolean isDisplay() {
        return this == BACKPACK || this == BALLOON || this == PET;
    }

    /** 착용해 두면 계속 보이는 카테고리 (미리보기를 일정 시간 걸어 둘 수 있다). */
    public boolean isPersistentLook() {
        return this == HAT || isDisplay() || this == PARTICLE || this == TITLE || this == CHAT_COLOR;
    }

    private final String key;
    private final String section;
    private final Material defaultIcon;
    private final int defaultSlot;

    Category(String key, String section, Material defaultIcon, int defaultSlot) {
        this.key = key;
        this.section = section;
        this.defaultIcon = defaultIcon;
        this.defaultSlot = defaultSlot;
    }

    /** 설정/명령어에서 쓰는 이름 (예: {@code arrow_trail}). */
    public String key() {
        return key;
    }

    /** cosmetics.yml 의 섹션 이름 (예: {@code arrow-trails}). */
    public String section() {
        return section;
    }

    public Material defaultIcon() {
        return defaultIcon;
    }

    public int defaultSlot() {
        return defaultSlot;
    }

    /**
     * 사용자가 입력한 카테고리 이름을 찾는다. 영문 키, 섹션 이름, 한글 표시 이름(띄어쓰기 무시)을 모두 받는다.
     *
     * @return 없으면 {@code null}
     */
    public static Category parse(String input, Function<Category, String> displayName) {
        if (input == null) {
            return null;
        }
        String wanted = normalize(input);
        for (Category c : values()) {
            String display = displayName == null ? null : displayName.apply(c);
            if (wanted.equals(normalize(c.key)) || wanted.equals(normalize(c.section))
                    || wanted.equals(normalize(c.name()))
                    || (display != null && wanted.equals(normalize(display)))) {
                return c;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }
}
