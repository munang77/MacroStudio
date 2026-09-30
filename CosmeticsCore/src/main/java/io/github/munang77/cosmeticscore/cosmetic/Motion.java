package io.github.munang77.cosmeticscore.cosmetic;

/** 몸에 붙는 장식의 움직임. */
public enum Motion {

    /** 가만히 있는다. */
    NONE,
    /** 날개처럼 좌우 한 쌍이 앞뒤로 퍼덕인다 ({@code mirror} 와 함께 쓴다). */
    FLAP,
    /** 꼬리처럼 좌우로 살랑인다. */
    SWAY,
    /** 망토처럼 앞뒤로 흔들린다. */
    SWING,
    /** 위아래로 살짝 오르내린다. */
    BOB,
    /** 제자리에서 빙글빙글 돈다. */
    SPIN
}
