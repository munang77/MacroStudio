package io.github.munang77.cosmeticscore.cosmetic;

/** 상대를 처치했을 때 사망 메시지를 바꾼다. */
public final class KillMessageCosmetic extends Cosmetic {

    private final String message;

    public KillMessageCosmetic(Info info, String message) {
        super(info, Category.KILL_MESSAGE);
        this.message = message;
    }

    /** 색이 입혀진 메시지 ({killer}, {victim} 자리표시자). */
    public String message() {
        return message;
    }
}
