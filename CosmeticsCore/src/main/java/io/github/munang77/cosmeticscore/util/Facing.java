package io.github.munang77.cosmeticscore.util;

import org.bukkit.util.Vector;

/** 마인크래프트 yaw(도)에서 수평 방향 벡터를 구한다. yaw 0 은 +Z(남쪽)를 본다. */
public final class Facing {

    private Facing() {
    }

    /** 바라보는 방향. */
    public static Vector forward(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** 바라봤을 때 오른쪽. */
    public static Vector right(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
    }
}
