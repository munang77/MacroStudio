package io.github.munang77.cosmeticscore.cosmetic;

/**
 * 몸에 붙는 장식(백팩, 날개, 꼬리, 허리, 상체)을 어디에, 어떤 각도로, 어떻게 움직이며 붙일지.
 *
 * @param offset   카테고리 기본 자리에서 더 옮길 거리 (칸)
 * @param rotation 모델을 돌릴 각도 (x, y, z; 도)
 * @param pivot    돌거나 흔들릴 때 제자리에 있는 점 (모델 가운데 기준, 크기 1 일 때의 칸)
 * @param mirror   모델을 반대쪽에 거울처럼 하나 더 붙인다 (날개 한 짝 모델로 한 쌍 만들기)
 * @param motion   움직임
 * @param speed    움직임 빠르기 (1 = 기본)
 * @param angle    흔들리는 각도 (도)
 * @param spread   날개가 뒤로 젖혀진 기본 각도 (도, FLAP)
 * @param height   오르내리는 높이 (칸, BOB)
 */
public record Attachment(Vec3 offset, Vec3 rotation, Vec3 pivot, boolean mirror,
                         Motion motion, double speed, double angle, double spread, double height) {

    /** 오른쪽(+x), 위(+y), 앞(+z) 기준의 세 값. */
    public record Vec3(double x, double y, double z) {

        public static final Vec3 ZERO = new Vec3(0, 0, 0);

        public Vec3 plus(Vec3 o) {
            return new Vec3(x + o.x, y + o.y, z + o.z);
        }
    }

    /** 아무 설정도 없는 기본값. */
    public static final Attachment NONE = new Attachment(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, false,
            Motion.NONE, 1, 0, 0, 0);

    /** 카테고리마다 따로 적지 않았을 때 쓰는 움직임과 거울 여부. */
    public static Attachment defaults(Category category) {
        return switch (category) {
            case WINGS -> new Attachment(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, true, Motion.FLAP, 1, 16, 20, 0);
            case TAIL -> new Attachment(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, false, Motion.SWAY, 1, 14, 0, 0);
            default -> NONE;
        };
    }
}
