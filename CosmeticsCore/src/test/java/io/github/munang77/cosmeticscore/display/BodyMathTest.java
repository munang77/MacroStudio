package io.github.munang77.cosmeticscore.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.munang77.cosmeticscore.cosmetic.Attachment;
import io.github.munang77.cosmeticscore.cosmetic.Attachment.Vec3;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Motion;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BodyMathTest {

    private static final float EPS = 1e-4f;

    /** 디스플레이 엔티티 안쪽 좌표에서 모델 점이 가는 곳 (아이템 렌더러가 반 바퀴 돌리는 것까지). */
    private static Vector3f place(Transformation t, Vector3f model) {
        Vector3f p = new Quaternionf().rotationY((float) Math.PI).transform(new Vector3f(model));
        p.mul(t.getScale());
        t.getLeftRotation().transform(p);
        return p.add(t.getTranslation());
    }

    private static void near(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPS, () -> expected + " != " + actual);
        assertEquals(expected.y, actual.y, EPS, () -> expected + " != " + actual);
        assertEquals(expected.z, actual.z, EPS, () -> expected + " != " + actual);
    }

    @Test
    void leftPieceMirrorsRightPiece() {
        Attachment a = new Attachment(Vec3.ZERO, new Vec3(10, 25, -15), new Vec3(0.1, 0.2, -0.05), true,
                Motion.FLAP, 1, 16, 20, 0);
        for (double phase : new double[] {0, 0.7, 2.5}) {
            Transformation right = BodyMath.transform(a, 0.9f, 1, phase);
            Transformation left = BodyMath.transform(a, 0.9f, -1, phase);
            // 날개 판은 모델 안에서 z=0 이라 거울과 반 바퀴 돌리기가 같은 모양이 된다
            for (Vector3f m : new Vector3f[] {new Vector3f(0.3f, 0.2f, 0), new Vector3f(-0.4f, -0.1f, 0),
                    new Vector3f(0.5f, 0.5f, 0)}) {
                Vector3f r = place(right, m);
                near(new Vector3f(-r.x, r.y, r.z), place(left, m));
            }
        }
    }

    @Test
    void flapFoldsBothWingsBackward() {
        Attachment a = Attachment.slot(Category.WINGS).defaults();
        // 오른쪽 날개 끝 = 모델 동쪽(+x) → 엔티티 안쪽 -x. 뒤로 접히면 z 가 음수가 된다
        Vector3f tipRight = place(BodyMath.transform(a, 1, 1, Math.PI / 2), new Vector3f(1, 0, 0));
        Vector3f tipLeft = place(BodyMath.transform(a, 1, -1, Math.PI / 2), new Vector3f(1, 0, 0));
        assertTrue(tipRight.x < 0 && tipRight.z < 0, "오른쪽 날개: " + tipRight);
        assertTrue(tipLeft.x > 0 && tipLeft.z < 0, "왼쪽 날개: " + tipLeft);
    }

    @Test
    void pivotStaysPut() {
        Vec3 pivot = new Vec3(0, 0.4, -0.17);
        Attachment a = new Attachment(Vec3.ZERO, new Vec3(12, 0, 0), pivot, false, Motion.SWING, 1, 8, 0, 0);
        for (double phase : new double[] {0, 1, 2}) {
            Transformation t = BodyMath.transform(a, 1, 0, phase);
            // 설정의 오른쪽(+x) 은 안쪽 좌표 -x. 중심점에 있는 모델 점은 돌려도 그 자리에 있어야 한다
            Vector3f local = new Vector3f(0, 0.4f, -0.17f);
            Vector3f model = new Quaternionf().rotationY((float) Math.PI).transform(new Vector3f(local));
            near(local, place(t, model));
        }
        // 돌리지 않으면 제자리 (옮김 없음)
        Transformation still = BodyMath.transform(new Attachment(Vec3.ZERO, Vec3.ZERO, pivot, false,
                Motion.NONE, 1, 0, 0, 0), 1, 0, 0);
        near(new Vector3f(), still.getTranslation());
    }

    @Test
    void sneakingLeansAroundTheNeck() {
        Vec3 neck = BodyMath.lean(new Vec3(0, BodyMath.NECK, 0), true);
        assertEquals(BodyMath.SNEAK_NECK, neck.y(), 1e-9);
        assertEquals(0, neck.z(), 1e-9);
        // 등 뒤 백팩은 웅크리면 더 뒤로, 더 아래로
        Vec3 back = BodyMath.anchor(Category.BACKPACK);
        Vec3 leaned = BodyMath.lean(back, true);
        assertTrue(leaned.z() < back.z() && leaned.y() < back.y(), "웅크린 백팩: " + leaned);
        assertEquals(back, BodyMath.lean(back, false));
    }

    @Test
    void mirroredPiecesSitOnBothSides() {
        Vec3 p = new Vec3(0.2, 1, 0);
        assertEquals(new Vec3(-0.2, 1, 0), BodyMath.side(p, -1));
        assertEquals(p, BodyMath.side(p, 1));
    }
}
