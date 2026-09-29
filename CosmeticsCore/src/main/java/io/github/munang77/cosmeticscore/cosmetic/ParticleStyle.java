package io.github.munang77.cosmeticscore.cosmetic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import io.github.munang77.cosmeticscore.util.Facing;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** 파티클 코스메틱이 몸 주변에 점을 찍는 모양. */
public enum ParticleStyle {

    /** 몸 주변 아무 곳에나 두어 개. */
    AURA(1) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double height = f.sneaking ? 1.4 : 1.8;
            for (int i = 0; i < 2; i++) {
                out.accept(f.at(r.nextDouble(-0.45, 0.45), r.nextDouble(0.1, height), r.nextDouble(-0.45, 0.45)));
            }
        }
    },

    /** 머리 위 고리. */
    HALO(1) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            double y = f.sneaking ? 1.75 : 2.15;
            int n = 12;
            double turn = f.step * 0.05;
            for (int i = 0; i < n; i++) {
                double a = turn + Math.PI * 2 * i / n;
                out.accept(f.at(Math.cos(a) * 0.32, y, Math.sin(a) * 0.32));
            }
        }
    },

    /** 걸을 때만 발밑에. */
    TRAIL(1) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            if (!f.moving) {
                return;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 2; i++) {
                out.accept(f.at(r.nextDouble(-0.2, 0.2), 0.1, r.nextDouble(-0.2, 0.2)));
            }
        }
    },

    /** 발밑에서 머리까지 올라가는 두 줄기 소용돌이. */
    SPIRAL(1) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            int cycle = 30;
            double height = (f.sneaking ? 1.6 : 2.0) * (Math.floorMod(f.step, cycle) / (double) cycle);
            double a = f.step * 0.4;
            double radius = 0.6;
            out.accept(f.at(Math.cos(a) * radius, height, Math.sin(a) * radius));
            out.accept(f.at(Math.cos(a + Math.PI) * radius, height, Math.sin(a + Math.PI) * radius));
        }
    },

    /** 허리 높이에서 도는 세 개의 점. */
    ORBIT(1) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            double y = f.sneaking ? 0.8 : 1.0;
            double radius = 0.8;
            for (int k = 0; k < 3; k++) {
                double a = f.step * 0.25 + k * (Math.PI * 2 / 3);
                out.accept(f.at(Math.cos(a) * radius, y, Math.sin(a) * radius));
            }
        }
    },

    /** 등 뒤 날개. 점이 많아서 4프레임에 한 번만 그리고, 조금씩 퍼덕인다. */
    WINGS(4) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            double top = f.sneaking ? 1.8 : 2.1;
            double flap = 0.25 + 0.2 * Math.sin(f.step * 0.35);
            for (double[] p : WING_POINTS) {
                double side = p[0];
                double up = top - p[1];
                double back = 0.3 + side * flap;
                out.accept(f.behind(-side, up, back));
                out.accept(f.behind(side, up, back));
            }
        }
    },

    /** 머리 위 왕관: 둥근 테두리와 뾰족한 장식 네 개. */
    CROWN(2) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            double y = f.sneaking ? 1.7 : 2.05;
            int n = 10;
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                out.accept(f.at(Math.cos(a) * 0.3, y, Math.sin(a) * 0.3));
            }
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 + Math.PI / 2 * i;
                out.accept(f.at(Math.cos(a) * 0.3, y + 0.18, Math.sin(a) * 0.3));
            }
        }
    },

    /** 발밑에서 위로 넓어지며 도는 회오리. */
    TORNADO(1) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            int rings = 6;
            double top = f.sneaking ? 1.7 : 2.1;
            for (int i = 0; i < rings; i++) {
                double h = top * i / (rings - 1);
                double r = 0.15 + h * 0.3;
                double a = f.step * 0.35 + i * 0.9;
                out.accept(f.at(Math.cos(a) * r, h, Math.sin(a) * r));
            }
        }
    },

    /** 등 뒤 커다란 하트 모양. */
    HEART_SHAPE(5) {
        @Override
        void points(Frame f, Consumer<Location> out) {
            double center = f.sneaking ? 1.2 : 1.45;
            for (double[] p : HEART_POINTS) {
                out.accept(f.behind(p[0], center + p[1], 0.5));
            }
        }
    };

    /** 오른쪽 날개 모양. 윗줄부터, 왼쪽 칸이 몸 쪽. */
    private static final String[] WING_SHAPE = {
            "....XX",
            "..XXXX",
            ".XXXXX",
            "XXXXX.",
            "XXXX..",
            "XXX...",
            "X.....",
    };
    private static final double WING_SPACING = 0.2;
    /** (몸에서 떨어진 거리, 위에서부터 내려온 거리) 목록. */
    static final List<double[]> WING_POINTS = buildWing();

    /** 하트 곡선 위의 점 (옆, 위). */
    static final List<double[]> HEART_POINTS = buildHeart();

    private final int period;

    ParticleStyle(int period) {
        this.period = period;
    }

    /** 이번 프레임에 그릴 차례인지. */
    public boolean shouldRender(int step) {
        return Math.floorMod(step, period) == 0;
    }

    /**
     * 한 프레임 분량의 점을 {@code out} 으로 넘긴다.
     *
     * @param base     플레이어 발 위치
     * @param yaw      플레이어가 보는 방향
     * @param step     프레임 번호 (애니메이션용)
     * @param moving   지난 프레임 이후 움직였는지
     * @param sneaking 웅크리고 있는지
     */
    public void render(Location base, float yaw, int step, boolean moving, boolean sneaking, Consumer<Location> out) {
        points(new Frame(base, yaw, step, moving, sneaking), out);
    }

    abstract void points(Frame f, Consumer<Location> out);

    private static List<double[]> buildWing() {
        List<double[]> pts = new ArrayList<>();
        for (int row = 0; row < WING_SHAPE.length; row++) {
            String line = WING_SHAPE[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == 'X') {
                    pts.add(new double[] {0.15 + col * WING_SPACING, row * WING_SPACING});
                }
            }
        }
        return List.copyOf(pts);
    }

    private static List<double[]> buildHeart() {
        List<double[]> pts = new ArrayList<>();
        int n = 28;
        double scale = 0.035;
        for (int i = 0; i < n; i++) {
            double t = Math.PI * 2 * i / n;
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            pts.add(new double[] {x * scale, y * scale});
        }
        return List.copyOf(pts);
    }

    /** 한 프레임의 입력. */
    static final class Frame {
        final Location base;
        final float yaw;
        final int step;
        final boolean moving;
        final boolean sneaking;
        private Vector forward;
        private Vector right;

        Frame(Location base, float yaw, int step, boolean moving, boolean sneaking) {
            this.base = base;
            this.yaw = yaw;
            this.step = step;
            this.moving = moving;
            this.sneaking = sneaking;
        }

        Location at(double dx, double dy, double dz) {
            return base.clone().add(dx, dy, dz);
        }

        /** 몸 기준 자리: 오른쪽으로 {@code lateral}, 위로 {@code up}, 등 뒤로 {@code back}. */
        Location behind(double lateral, double up, double back) {
            if (forward == null) {
                forward = Facing.forward(yaw);
                right = Facing.right(yaw);
            }
            return at(right.getX() * lateral - forward.getX() * back, up,
                    right.getZ() * lateral - forward.getZ() * back);
        }
    }
}
