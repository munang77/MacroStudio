package io.github.munang77.cosmeticscore.cosmetic;

import java.util.Locale;
import java.util.function.Function;

import org.bukkit.Material;

/** 코스메틱 종류. 한 카테고리에는 하나만 착용할 수 있다. */
public enum Category {

    HAT("hat", "hats", Material.LEATHER_HELMET, 10),
    WINGS("wings", "wings", Material.ELYTRA, 11),
    BACKPACK("backpack", "backpacks", Material.CHEST, 12),
    TAIL("tail", "tails", Material.RABBIT_FOOT, 13),
    WAIST("waist", "waist", Material.LEATHER_LEGGINGS, 14),
    TORSO("torso", "torso", Material.LEATHER_CHESTPLATE, 15),
    BALLOON("balloon", "balloons", Material.LEAD, 16),
    PET("pet", "pets", Material.BONE, 19),
    PARTICLE("particle", "particles", Material.BLAZE_POWDER, 20),
    ARROW_TRAIL("arrow_trail", "arrow-trails", Material.ARROW, 21),
    KILL_EFFECT("kill_effect", "kill-effects", Material.DIAMOND_SWORD, 22),
    KILL_MESSAGE("kill_message", "kill-messages", Material.WRITABLE_BOOK, 23),
    TITLE("title", "titles", Material.NAME_TAG, 24),
    CHAT_COLOR("chat_color", "chat-colors", Material.MAGENTA_DYE, 25),
    JOIN_EFFECT("join_effect", "join-effects", Material.OAK_DOOR, 31);

    /** 몸통에 딱 붙어서 몸 방향과 웅크리기를 따라가는 카테고리. */
    public boolean isBody() {
        return this == BACKPACK || this == WINGS || this == TAIL || this == WAIST || this == TORSO;
    }

    /** 몸에 붙어 다니는 디스플레이 엔티티로 보여 주는 카테고리. */
    public boolean isDisplay() {
        return isBody() || this == BALLOON || this == PET;
    }

    /** 옷장에서 마네킹에 입혀 볼 수 있는 카테고리. */
    public boolean isWardrobe() {
        return this == HAT || isDisplay() || this == PARTICLE || this == TITLE;
    }

    /**
     * 미리보기를 몇 초 동안 걸어 두는 카테고리 (몸에 보이는 것). 칭호/채팅 색처럼 채팅에 남는 것은
     * 남들에게 보이지 않게 본인에게만 예시를 보여 준다.
     */
    public boolean isTimedPreview() {
        return this == HAT || isDisplay() || this == PARTICLE;
    }

    /** 코스메틱이 꺼진 월드에서 쓸 수 없는 카테고리 (글자로만 보이는 칭호/채팅 색/메시지는 어디서나 된다). */
    public boolean isWorldBound() {
        return isTimedPreview() || this == ARROW_TRAIL || this == KILL_EFFECT;
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
