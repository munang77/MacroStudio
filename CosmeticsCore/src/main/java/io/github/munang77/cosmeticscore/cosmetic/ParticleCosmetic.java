package io.github.munang77.cosmeticscore.cosmetic;

/** 몸 주변에 계속 나오는 파티클. */
public final class ParticleCosmetic extends Cosmetic {

    private final ParticleSpec spec;
    private final ParticleStyle style;

    public ParticleCosmetic(Info info, ParticleSpec spec, ParticleStyle style) {
        super(info, Category.PARTICLE);
        this.spec = spec;
        this.style = style;
    }

    public ParticleSpec spec() {
        return spec;
    }

    public ParticleStyle style() {
        return style;
    }
}
