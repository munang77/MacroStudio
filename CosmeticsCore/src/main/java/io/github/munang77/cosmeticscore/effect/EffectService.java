package io.github.munang77.cosmeticscore.effect;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.ArrowTrailCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.KillEffectCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.ParticleCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.ParticleSpec;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 파티클, 화살 궤적, 킬 이펙트.
 *
 * <p>파티클은 월드 전체가 아니라 보는 사람마다 따로 보낸다. 그래야 "다른 사람 효과 보기"를 끈
 * 플레이어에게는 보내지 않을 수 있다.
 */
public final class EffectService implements Listener {

    private final CosmeticsCore plugin;
    private final NamespacedKey fireworkKey;
    private final Map<UUID, Location> lastPositions = new HashMap<>();
    private final Map<UUID, TrackedProjectile> projectiles = new HashMap<>();
    private BukkitTask particleTask;
    private BukkitTask trailTask;
    private int particleStep;
    private int trailStep;

    public EffectService(CosmeticsCore plugin) {
        this.plugin = plugin;
        this.fireworkKey = new NamespacedKey(plugin, "kill_firework");
    }

    /** 반복 작업을 (다시) 시작한다. 설정을 다시 불러오면 간격이 바뀔 수 있어 매번 새로 건다. */
    public void start() {
        stop();
        int interval = plugin.settings().particleInterval();
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, interval, interval);
        trailTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTrails, 1L, 1L);
    }

    public void stop() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        if (trailTask != null) {
            trailTask.cancel();
            trailTask = null;
        }
        projectiles.clear();
        lastPositions.clear();
    }

    public void forget(Player player) {
        lastPositions.remove(player.getUniqueId());
    }

    // ── 몸 주변 파티클 ───────────────────────────

    private void tickParticles() {
        particleStep++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            Location last = lastPositions.put(player.getUniqueId(), loc);
            ParticleCosmetic cosmetic = plugin.manager().equipped(player, Category.PARTICLE, ParticleCosmetic.class);
            if (cosmetic == null || !cosmetic.style().shouldRender(particleStep) || plugin.manager().isHidden(player)) {
                continue;
            }
            boolean moving = last != null && last.getWorld() == loc.getWorld() && last.distanceSquared(loc) > 0.0004;
            List<Player> viewers = plugin.manager().viewers(player, loc);
            if (viewers.isEmpty()) {
                continue;
            }
            ParticleSpec spec = cosmetic.spec();
            int step = particleStep;
            cosmetic.style().render(loc, loc.getYaw(), step, moving, player.isSneaking(), point -> {
                for (Player viewer : viewers) {
                    spec.spawn(viewer, point, 1, 0, 0, step);
                }
            });
        }
    }

    // ── 화살 궤적 ────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof AbstractArrow) || !(projectile.getShooter() instanceof Player shooter)) {
            return;
        }
        ArrowTrailCosmetic cosmetic = plugin.manager().equipped(shooter, Category.ARROW_TRAIL, ArrowTrailCosmetic.class);
        if (cosmetic == null || plugin.manager().isHidden(shooter)) {
            return;
        }
        projectiles.put(projectile.getUniqueId(), new TrackedProjectile(projectile, shooter.getUniqueId(), cosmetic));
    }

    private void tickTrails() {
        if (projectiles.isEmpty()) {
            return;
        }
        trailStep++;
        int maxTicks = plugin.settings().arrowMaxTicks();
        Iterator<TrackedProjectile> it = projectiles.values().iterator();
        while (it.hasNext()) {
            TrackedProjectile tracked = it.next();
            Projectile projectile = tracked.projectile;
            if (!projectile.isValid() || ++tracked.age > maxTicks
                    || (projectile instanceof AbstractArrow arrow && arrow.isInBlock())) {
                it.remove();
                continue;
            }
            Location at = projectile.getLocation();
            Player shooter = Bukkit.getPlayer(tracked.shooter);
            for (Player viewer : plugin.manager().viewers(shooter, at)) {
                tracked.cosmetic.spec().spawn(viewer, at, tracked.cosmetic.amount(), 0.05, 0, trailStep);
            }
        }
    }

    // ── 킬 이펙트 ────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }
        if (!(victim instanceof Player) && !plugin.settings().killEffectsOnMobs()) {
            return;
        }
        KillEffectCosmetic cosmetic = plugin.manager().equipped(killer, Category.KILL_EFFECT, KillEffectCosmetic.class);
        if (cosmetic == null || plugin.settings().isDisabled(victim.getWorld())) {
            return;
        }
        play(killer, cosmetic, victim.getLocation());
    }

    /** 킬 이펙트를 {@code at} 자리에서 터뜨린다. 효과가 실패해도 사망 처리나 명령어는 멈추지 않는다. */
    public void play(Player killer, KillEffectCosmetic cosmetic, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        Location center = at.clone().add(0, 1, 0);
        try {
            switch (cosmetic.effect()) {
                case LIGHTNING -> world.strikeLightningEffect(at);
                case FIREWORK -> spawnFirework(center, cosmetic.firework());
                case BURST -> {
                    for (Player viewer : plugin.manager().viewers(killer, center)) {
                        cosmetic.burst().spawn(viewer, center, cosmetic.amount(), cosmetic.spread(), cosmetic.speed(),
                                particleStep);
                    }
                }
            }
            if (cosmetic.sound() != null) {
                world.playSound(center, cosmetic.sound(), cosmetic.volume(), cosmetic.pitch());
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("킬 이펙트 " + cosmetic.id() + " 를 보여 주지 못했습니다: " + e);
        }
    }

    /** 아무도 다치게 하지 않는 폭죽을 바로 터뜨린다. */
    public void spawnFirework(Location at, FireworkEffect effect) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        try {
            Firework firework = world.spawn(at, Firework.class, fw -> {
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(effect);
                meta.setPower(0);
                fw.setFireworkMeta(meta);
                fw.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
            });
            firework.detonate();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("폭죽을 터뜨리지 못했습니다: " + e);
        }
    }

    /** 화살 궤적 미리보기: 앞으로 화살이 날아가는 모양으로 1.5초 동안 그린다. */
    public void previewTrail(Player player, ArrowTrailCosmetic cosmetic) {
        Location eye = player.getEyeLocation();
        Vector velocity = eye.getDirection().multiply(1.6);
        Location pos = eye.clone();
        new BukkitRunnable() {
            private int age;

            @Override
            public void run() {
                if (!player.isOnline() || age++ >= 30) {
                    cancel();
                    return;
                }
                pos.add(velocity);
                velocity.multiply(0.99).add(new Vector(0, -0.05, 0));
                for (Player viewer : plugin.manager().viewers(player, pos)) {
                    cosmetic.spec().spawn(viewer, pos, cosmetic.amount(), 0.05, 0, age);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 이 플러그인이 터뜨린 폭죽은 아무도 다치게 하지 않는다. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(fireworkKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    private static final class TrackedProjectile {
        final Projectile projectile;
        final UUID shooter;
        final ArrowTrailCosmetic cosmetic;
        int age;

        TrackedProjectile(Projectile projectile, UUID shooter, ArrowTrailCosmetic cosmetic) {
            this.projectile = projectile;
            this.shooter = shooter;
            this.cosmetic = cosmetic;
        }
    }
}
