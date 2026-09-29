package io.github.munang77.cosmeticscore.cosmetic;

/** 머리 칸에 씌우는 장식 아이템. */
public final class HatCosmetic extends Cosmetic {

    private final ItemSpec item;

    public HatCosmetic(Info info, ItemSpec item) {
        super(info, Category.HAT);
        this.item = item;
    }

    public ItemSpec item() {
        return item;
    }
}
