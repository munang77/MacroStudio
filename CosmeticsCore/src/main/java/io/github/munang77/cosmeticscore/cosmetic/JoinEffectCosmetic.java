package io.github.munang77.cosmeticscore.cosmetic;

import org.bukkit.FireworkEffect;

/** 접속/퇴장할 때 나오는 메시지, 소리, 폭죽. */
public final class JoinEffectCosmetic extends Cosmetic {

    private final String joinMessage;
    private final String quitMessage;
    private final String sound;
    private final float volume;
    private final float pitch;
    private final FireworkEffect firework;

    public JoinEffectCosmetic(Info info, String joinMessage, String quitMessage, String sound, float volume,
                              float pitch, FireworkEffect firework) {
        super(info, Category.JOIN_EFFECT);
        this.joinMessage = joinMessage;
        this.quitMessage = quitMessage;
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
        this.firework = firework;
    }

    /** 색이 입혀진 입장 메시지 ({player} 자리표시자). 없으면 {@code null}. */
    public String joinMessage() {
        return joinMessage;
    }

    /** 색이 입혀진 퇴장 메시지. 없으면 {@code null}. */
    public String quitMessage() {
        return quitMessage;
    }

    public String sound() {
        return sound;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    /** 없으면 {@code null}. */
    public FireworkEffect firework() {
        return firework;
    }
}
