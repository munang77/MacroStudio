package io.github.munang77.cosmeticscore.cosmetic;

/** 쏜 화살을 따라가는 파티클. */
public final class ArrowTrailCosmetic extends Cosmetic {

    private final ParticleSpec spec;
    private final int amount;

    public ArrowTrailCosmetic(Info info, ParticleSpec spec, int amount) {
        super(info, Category.ARROW_TRAIL);
        this.spec = spec;
        this.amount = amount;
    }

    public ParticleSpec spec() {
        return spec;
    }

    /** 한 틱에 뿌릴 개수. */
    public int amount() {
        return amount;
    }
}
