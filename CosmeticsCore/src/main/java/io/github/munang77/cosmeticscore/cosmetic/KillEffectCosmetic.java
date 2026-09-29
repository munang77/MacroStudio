package io.github.munang77.cosmeticscore.cosmetic;

import org.bukkit.FireworkEffect;

/** 상대를 처치한 자리에서 터지는 효과. */
public final class KillEffectCosmetic extends Cosmetic {

    /** 효과 종류. */
    public enum Effect {
        /** 피해 없는 번개. */
        LIGHTNING,
        /** 피해 없는 폭죽. */
        FIREWORK,
        /** 파티클 폭발. */
        BURST
    }

    private final Effect effect;
    private final FireworkEffect firework;
    private final ParticleSpec burst;
    private final int amount;
    private final double spread;
    private final double speed;
    private final String sound;
    private final float volume;
    private final float pitch;

    public KillEffectCosmetic(Info info, Effect effect, FireworkEffect firework, ParticleSpec burst, int amount,
                              double spread, double speed, String sound, float volume, float pitch) {
        super(info, Category.KILL_EFFECT);
        this.effect = effect;
        this.firework = firework;
        this.burst = burst;
        this.amount = amount;
        this.spread = spread;
        this.speed = speed;
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
    }

    public Effect effect() {
        return effect;
    }

    /** {@link Effect#FIREWORK} 일 때만 있다. */
    public FireworkEffect firework() {
        return firework;
    }

    /** {@link Effect#BURST} 일 때만 있다. */
    public ParticleSpec burst() {
        return burst;
    }

    public int amount() {
        return amount;
    }

    public double spread() {
        return spread;
    }

    public double speed() {
        return speed;
    }

    /** 함께 재생할 소리 키 (없으면 {@code null}). */
    public String sound() {
        return sound;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }
}
