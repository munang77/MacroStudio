package io.github.munang77.cosmeticscore.cosmetic;

import java.util.Locale;

import io.github.munang77.cosmeticscore.util.Colors;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 설정에서 읽은 파티클 종류와 그 파티클에 필요한 데이터(색, 블록 등). */
public final class ParticleSpec {

    private static final int RAINBOW_STEPS = 36;

    private final Particle particle;
    /** 고정 데이터. 데이터가 필요 없는 파티클이면 {@code null}. */
    private final Object data;
    /** 무지개처럼 시간에 따라 바뀌는 데이터. 없으면 {@code null}. */
    private final Object[] cycle;

    private ParticleSpec(Particle particle, Object data, Object[] cycle) {
        this.particle = particle;
        this.data = data;
        this.cycle = cycle;
    }

    public Particle particle() {
        return particle;
    }

    /** {@code step} 번째 프레임에 쓸 데이터. */
    public Object dataAt(int step) {
        if (cycle != null) {
            return cycle[Math.floorMod(step, cycle.length)];
        }
        return data;
    }

    /** 한 플레이어에게만 파티클을 보낸다 (보기 설정을 존중하기 위해 월드 전체가 아니라 개인별로 보낸다). */
    public void spawn(Player viewer, Location at, int count, double spread, double speed, int step) {
        viewer.spawnParticle(particle, at, count, spread, spread, spread, speed, dataAt(step));
    }

    /**
     * {@code particle}, {@code color}, {@code size}, {@code rainbow}, {@code block}, {@code item}, {@code value} 항목을 읽는다.
     *
     * @throws IllegalArgumentException 설정이 잘못됐을 때 (메시지는 그대로 로그에 찍힌다)
     */
    public static ParticleSpec parse(ConfigurationSection s) {
        String name = s.getString("particle");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("particle 항목이 없습니다");
        }
        Particle particle = parseParticle(name);
        Class<?> type = particle.getDataType();
        boolean rainbow = s.getBoolean("rainbow", false);
        float size = (float) s.getDouble("size", 1.0);

        if (type == Void.class) {
            return new ParticleSpec(particle, null, null);
        }
        if (type == Particle.DustOptions.class) {
            if (rainbow) {
                int[] rgb = Colors.rainbow(RAINBOW_STEPS);
                Object[] cycle = new Object[rgb.length];
                for (int i = 0; i < rgb.length; i++) {
                    cycle[i] = new Particle.DustOptions(Color.fromRGB(rgb[i]), size);
                }
                return new ParticleSpec(particle, null, cycle);
            }
            return new ParticleSpec(particle, new Particle.DustOptions(color(s, "color"), size), null);
        }
        if (type == Particle.DustTransition.class) {
            Color from = color(s, "color");
            Color to = s.contains("to-color") ? color(s, "to-color") : from;
            return new ParticleSpec(particle, new Particle.DustTransition(from, to, size), null);
        }
        if (type == Color.class) {
            if (rainbow) {
                int[] rgb = Colors.rainbow(RAINBOW_STEPS);
                Object[] cycle = new Object[rgb.length];
                for (int i = 0; i < rgb.length; i++) {
                    cycle[i] = Color.fromRGB(rgb[i]);
                }
                return new ParticleSpec(particle, null, cycle);
            }
            return new ParticleSpec(particle, color(s, "color"), null);
        }
        if (type == BlockData.class) {
            Material block = Material.matchMaterial(s.getString("block", "STONE"));
            if (block == null || !block.isBlock()) {
                throw new IllegalArgumentException("block 항목이 블록이 아닙니다: " + s.getString("block"));
            }
            return new ParticleSpec(particle, block.createBlockData(), null);
        }
        if (type == ItemStack.class) {
            Material item = Material.matchMaterial(s.getString("item", "STONE"));
            if (item == null || !item.isItem() || item.isAir()) {
                throw new IllegalArgumentException("item 항목이 아이템이 아닙니다: " + s.getString("item"));
            }
            return new ParticleSpec(particle, new ItemStack(item), null);
        }
        if (type == Float.class) {
            return new ParticleSpec(particle, (float) s.getDouble("value", 1.0), null);
        }
        if (type == Integer.class) {
            return new ParticleSpec(particle, s.getInt("value", 0), null);
        }
        throw new IllegalArgumentException(particle.name() + " 파티클은 지원하지 않습니다 (필요한 데이터: "
                + type.getSimpleName() + ")");
    }

    private static Particle parseParticle(String name) {
        String n = name.trim();
        int colon = n.indexOf(':');
        if (colon >= 0) {
            n = n.substring(colon + 1);
        }
        try {
            return Particle.valueOf(n.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("없는 파티클입니다: " + name);
        }
    }

    private static Color color(ConfigurationSection s, String path) {
        return Color.fromRGB(Colors.parseRgb(s.getString(path, "#FFFFFF")));
    }
}
