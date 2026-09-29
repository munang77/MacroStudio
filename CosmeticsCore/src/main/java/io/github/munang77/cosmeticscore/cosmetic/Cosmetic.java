package io.github.munang77.cosmeticscore.cosmetic;

import java.util.List;

/** 모든 코스메틱의 공통 정보. 불변이라 다른 스레드(채팅 등)에서 읽어도 안전하다. */
public abstract class Cosmetic {

    private final Info info;
    private final Category category;

    protected Cosmetic(Info info, Category category) {
        this.info = info;
        this.category = category;
    }

    public String id() {
        return info.id();
    }

    public Category category() {
        return category;
    }

    /** 색이 입혀진 이름. */
    public String name() {
        return info.name();
    }

    /** 색이 입혀진 설명 줄. */
    public List<String> lore() {
        return info.lore();
    }

    /** 메뉴 아이콘 모양. */
    public ItemSpec icon() {
        return info.icon();
    }

    /** 누구나 쓸 수 있는지. */
    public boolean free() {
        return info.free();
    }

    public String permission() {
        return info.permission();
    }

    /** 등급 키 (config.yml 의 rarities 아래 이름). */
    public String rarity() {
        return info.rarity();
    }

    /** 가격. 0 이하면 팔지 않는다. */
    public double price() {
        return info.price();
    }

    /** 뽑기에서 나올 수 있는지. */
    public boolean inCrate() {
        return info.crate();
    }

    /** 설정에서 읽은 공통 항목. 이름과 설명은 이미 색이 입혀져 있다. */
    public record Info(String id, String name, List<String> lore, ItemSpec icon, boolean free, String permission,
                       String rarity, double price, boolean crate) {
        public Info {
            lore = List.copyOf(lore);
        }
    }
}
