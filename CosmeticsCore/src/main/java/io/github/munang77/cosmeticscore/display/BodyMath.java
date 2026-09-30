package io.github.munang77.cosmeticscore.display;

import io.github.munang77.cosmeticscore.cosmetic.Attachment;
import io.github.munang77.cosmeticscore.cosmetic.Attachment.Vec3;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Motion;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 몸 장식의 자리와 회전을 계산한다. 서버 없이 시험할 수 있도록 따로 둔다.
 *
 * <p>자리는 발을 기준으로 (오른쪽, 위, 앞) 칸 단위로 다룬다. 디스플레이 엔티티 안쪽 좌표는 몸 방향으로
 * 돌린 뒤 +x 가 왼쪽, +y 가 위, +z 가 앞이다. 아이템 모델은 그 안에서 한 번 더 반 바퀴 돌아 그려지므로
 * 모델의 동쪽(+x)이 몸의 오른쪽, 북쪽(-z)이 앞이 된다.
 */
final class BodyMath {

    /** 서 있을 때 목 높이 (칸). 웅크리면 몸통이 목을 중심으로 숙는다. */
    static final double NECK = 1.5;
    /** 웅크렸을 때 목 높이: 바닐라 모델처럼 몸통이 3.2픽셀, 모델 전체가 2픽셀 내려간다. */
    static final double SNEAK_NECK = NECK - (3.2 + 2) / 16;
    /** 웅크렸을 때 몸통이 앞으로 숙는 각도 (라디안, 바닐라 모델과 같다). */
    static final double SNEAK_LEAN = 0.5;
    static final float SNEAK_LEAN_DEGREES = (float) Math.toDegrees(SNEAK_LEAN);
    /** 움직임 빠르기 1 일 때 한 틱에 도는 위상 (약 1.6초에 한 번). */
    static final double BASE_STEP = 0.2;
    /** 걷는 중이면 이만큼 빨리 움직인다. */
    static final double MOVING_BOOST = 1.6;

    private BodyMath() {
    }

    /** 카테고리의 기본 자리 (서 있을 때, 발 기준 오른쪽/위/앞). */
    static Vec3 anchor(Category category) {
        return switch (category) {
            case BACKPACK -> new Vec3(0, 1.1, -0.28);
            case WINGS -> new Vec3(0, 1.25, -0.2);
            case TAIL -> new Vec3(0, 0.72, -0.16);
            case WAIST -> new Vec3(0, 0.78, 0);
            case TORSO -> new Vec3(0, 1.2, 0.15);
            default -> Vec3.ZERO;
        };
    }

    /** 거울 조각이면 좌우를 뒤집는다. */
    static Vec3 side(Vec3 p, int side) {
        return side < 0 ? new Vec3(-p.x(), p.y(), p.z()) : p;
    }

    /** 서 있을 때의 자리를 웅크린 자세로 옮긴다 (몸통이 목을 중심으로 앞으로 숙는다). */
    static Vec3 lean(Vec3 p, boolean sneaking) {
        if (!sneaking) {
            return p;
        }
        double up = p.y() - NECK;
        double c = Math.cos(SNEAK_LEAN);
        double s = Math.sin(SNEAK_LEAN);
        return new Vec3(p.x(), SNEAK_NECK - p.z() * s + up * c, p.z() * c + up * s);
    }

    /** 이번 틱에 위상이 얼마나 나아가는지. */
    static double step(Attachment a, boolean moving) {
        return BASE_STEP * a.speed() * (moving ? MOVING_BOOST : 1);
    }

    /**
     * 장식 한 조각의 변환 (엔티티 안쪽 좌표).
     *
     * @param side  0 = 한 개, 1 = 오른쪽, -1 = 왼쪽 (오른쪽 모델을 거울처럼 돌려서 쓴다)
     * @param phase 움직임 위상 (라디안)
     */
    static Transformation transform(Attachment a, float scale, int side, double phase) {
        Vec3 r = a.rotation();
        float rx = (float) Math.toRadians(r.x());
        float ry = (float) Math.toRadians(r.y());
        float rz = (float) Math.toRadians(r.z());
        // 왼쪽 조각 = 오른쪽 조각을 좌우로 비춘 것. 비추면 x축 회전은 그대로, y·z축 회전은 반대가 된다
        Quaternionf turn = side < 0
                ? motion(a, side, phase).mul(new Quaternionf().rotationXYZ(rx, -ry, -rz))
                : motion(a, side, phase).mul(new Quaternionf().rotationXYZ(rx, ry, rz));

        // 중심점은 제자리에 두고 그 둘레로 돌린다: 옮김 = p - R·p (중심점이 모델 가운데면 0).
        // 설정의 오른쪽(+x)은 엔티티 안쪽 좌표의 -x, 왼쪽 조각은 거울이라 다시 +x. 크기를 바꿔도 같은 곳을 잡도록 크기를 곱한다
        Vec3 p = a.pivot();
        Vector3f pivot = new Vector3f((float) (side < 0 ? p.x() : -p.x()), (float) p.y(), (float) p.z()).mul(scale);
        Vector3f translation = new Vector3f(pivot).sub(turn.transform(new Vector3f(pivot)));
        if (a.motion() == Motion.BOB) {
            translation.y += (float) (a.height() * Math.sin(phase));
        }
        // 왼쪽 조각은 모델을 반 바퀴 돌려 반대쪽을 향하게 한다 (얇은 날개는 앞뒤가 같아 거울과 똑같이 보인다)
        Quaternionf rotation = side < 0 ? new Quaternionf(turn).rotateY((float) Math.PI) : turn;
        return new Transformation(translation, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    /** 보이지 않게 크기를 0 으로. */
    static Transformation hidden() {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(), new Quaternionf());
    }

    private static Quaternionf motion(Attachment a, int side, double phase) {
        float wave = (float) Math.sin(phase);
        float angle = (float) Math.toRadians(a.angle());
        // 오른쪽(또는 하나뿐인) 조각 기준. 왼쪽은 비춘 것이라 y축 회전 방향이 반대다
        float mirror = side < 0 ? -1 : 1;
        return switch (a.motion()) {
            // 오른쪽 날개 끝(-x)이 뒤(-z)로 가려면 y축으로 음의 방향으로 돈다
            case FLAP -> new Quaternionf().rotationY(-mirror * ((float) Math.toRadians(a.spread()) + angle * wave));
            case SWAY -> new Quaternionf().rotationY(mirror * angle * wave);
            case SWING -> new Quaternionf().rotationX(angle * wave);
            case SPIN -> new Quaternionf().rotationY(mirror * (float) phase);
            case BOB, NONE -> new Quaternionf();
        };
    }
}
