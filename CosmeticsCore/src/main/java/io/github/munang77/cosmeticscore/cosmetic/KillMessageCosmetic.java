package io.github.munang77.cosmeticscore.cosmetic;

import io.github.munang77.cosmeticscore.util.Text;

/** 상대를 처치했을 때 사망 메시지를 바꾼다. */
public final class KillMessageCosmetic extends Cosmetic {

    private final String message;

    public KillMessageCosmetic(Info info, String message) {
        super(info, Category.KILL_MESSAGE);
        this.message = message;
    }

    /** 이름을 채운 사망 메시지 (실제 사망과 미리보기가 같은 모양이 되도록 한곳에서 만든다). */
    public String format(String killer, String victim) {
        return Text.replace(message, "killer", killer, "victim", victim);
    }
}
