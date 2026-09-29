package io.github.munang77.cosmeticscore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.ParticleStyle;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class CategoryAndStyleTest {

    private static final Map<Category, String> KOREAN = Map.of(
            Category.HAT, "모자", Category.PARTICLE, "파티클", Category.ARROW_TRAIL, "화살 궤적",
            Category.KILL_EFFECT, "킬 이펙트", Category.TITLE, "칭호");

    @Test
    void parseToleratesMissingDisplayNames() {
        assertEquals(Category.PET, Category.parse("pet", c -> null));
        assertEquals(Category.JOIN_EFFECT, Category.parse("join-effects", KOREAN::get));
    }

    @Test
    void parsesEnglishKoreanAndSectionNames() {
        assertEquals(Category.ARROW_TRAIL, Category.parse("arrow_trail", KOREAN::get));
        assertEquals(Category.ARROW_TRAIL, Category.parse("arrow-trails", KOREAN::get));
        assertEquals(Category.ARROW_TRAIL, Category.parse("화살궤적", KOREAN::get));
        assertEquals(Category.ARROW_TRAIL, Category.parse("화살 궤적", KOREAN::get));
        assertEquals(Category.TITLE, Category.parse("칭호", KOREAN::get));
        assertEquals(Category.HAT, Category.parse("HAT", null));
        assertNull(Category.parse("없는거", KOREAN::get));
    }

    private static List<Location> render(ParticleStyle style, float yaw, int step, boolean moving) {
        List<Location> out = new ArrayList<>();
        style.render(new Location(null, 0, 64, 0), yaw, step, moving, false, out::add);
        return out;
    }

    @Test
    void haloIsARingAboveTheHead() {
        List<Location> points = render(ParticleStyle.HALO, 0, 1, false);
        assertEquals(12, points.size());
        for (Location p : points) {
            assertEquals(64 + 2.15, p.getY(), 1e-9);
            assertEquals(0.32, Math.hypot(p.getX(), p.getZ()), 1e-9);
        }
    }

    @Test
    void trailOnlyWhileMoving() {
        assertTrue(render(ParticleStyle.TRAIL, 0, 1, false).isEmpty());
        assertEquals(2, render(ParticleStyle.TRAIL, 0, 1, true).size());
    }

    @Test
    void wingsAreBehindThePlayerAndSymmetric() {
        assertTrue(ParticleStyle.WINGS.shouldRender(8));
        assertFalse(ParticleStyle.WINGS.shouldRender(9));
        for (float yaw : new float[] {0, 90, 180, -45}) {
            List<Location> points = render(ParticleStyle.WINGS, yaw, 4, false);
            assertEquals(48, points.size());
            double rad = Math.toRadians(yaw);
            double fx = -Math.sin(rad);
            double fz = Math.cos(rad);
            double lateralSum = 0;
            for (Location p : points) {
                // 바라보는 방향과의 내적이 음수 = 등 뒤
                assertTrue(p.getX() * fx + p.getZ() * fz < 0, "yaw " + yaw + " 에서 날개가 몸 앞에 있음");
                lateralSum += p.getX() * -Math.cos(rad) + p.getZ() * -Math.sin(rad);
            }
            assertEquals(0, lateralSum, 1e-9);
        }
    }

    @Test
    void everyStyleProducesPointsNearThePlayer() {
        for (ParticleStyle style : ParticleStyle.values()) {
            for (int step = 0; step < 40; step++) {
                if (!style.shouldRender(step)) {
                    continue;
                }
                for (Location p : render(style, 30, step, true)) {
                    assertTrue(Math.abs(p.getX()) < 2 && Math.abs(p.getZ()) < 2, style + " 점이 너무 멂");
                    assertTrue(p.getY() >= 64 && p.getY() <= 64 + 2.5, style + " 점 높이가 이상함");
                }
            }
        }
    }
}
