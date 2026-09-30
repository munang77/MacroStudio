package io.github.munang77.cosmeticscore.wardrobe;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 옷장의 마네킹. 1.21.9 이상에서는 플레이어 스킨을 그대로 입는 바닐라 마네킹을, 그보다 낮으면 플레이어 머리와
 * 갑옷을 입힌 갑옷 거치대를 쓴다. 마네킹 API 는 버전마다 달라서 리플렉션으로 부른다.
 */
final class Mannequins {

    private static final Class<? extends LivingEntity> MANNEQUIN = findMannequin();
    private static volatile Method setBodyYaw;
    private static volatile boolean bodyYawLooked;

    private Mannequins() {
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends LivingEntity> findMannequin() {
        try {
            Class<?> type = Class.forName("org.bukkit.entity.Mannequin");
            return LivingEntity.class.isAssignableFrom(type) ? (Class<? extends LivingEntity>) type : null;
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** 스킨을 입는 바닐라 마네킹을 쓸 수 있는지. */
    static boolean skinned() {
        return MANNEQUIN != null;
    }

    /**
     * {@code owner} 를 닮은 마네킹을 소환한다. {@code setup} 은 월드에 들어가기 전에 부른다.
     *
     * @return 소환이 막혔으면 {@code null}
     */
    static LivingEntity spawn(Location at, Player owner, Logger log, Consumer<LivingEntity> setup) {
        World world = at.getWorld();
        if (MANNEQUIN != null) {
            try {
                LivingEntity entity = world.spawn(at, MANNEQUIN, m -> {
                    setup.accept(m);
                    applySkin(m, owner, log);
                    call(m, "setImmovable", true);
                    clearDescription(m);
                });
                if (entity.isValid()) {
                    return entity;
                }
                entity.remove();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "마네킹을 만들지 못해 갑옷 거치대를 씁니다.", e);
            }
        }
        ArmorStand stand = world.spawn(at, ArmorStand.class, a -> {
            setup.accept(a);
            a.setArms(true);
            a.setBasePlate(false);
            a.setGravity(false);
            EntityEquipment eq = a.getEquipment();
            EntityEquipment from = owner.getEquipment();
            if (eq != null && from != null) {
                eq.setChestplate(copy(from.getChestplate()));
                eq.setLeggings(copy(from.getLeggings()));
                eq.setBoots(copy(from.getBoots()));
            }
        });
        return stand.isValid() ? stand : null;
    }

    /** 모자를 안 썼을 때 머리에 둘 것 (갑옷 거치대는 플레이어 머리, 마네킹은 스킨이 있으니 비운다). */
    static ItemStack bareHead(LivingEntity mannequin, Player owner) {
        if (!(mannequin instanceof ArmorStand)) {
            return null;
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            head.setItemMeta(skull);
        }
        return head;
    }

    /** 몸 방향까지 돌린다 (갑옷 거치대는 머리 방향이 곧 몸 방향). */
    static void face(LivingEntity mannequin, float yaw) {
        mannequin.setRotation(yaw, 0);
        if (mannequin instanceof ArmorStand) {
            return;
        }
        Method method = bodyYawMethod();
        if (method != null) {
            try {
                method.invoke(mannequin, yaw);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 몸 방향은 머리를 천천히 따라가므로 없어도 된다
            }
        }
    }

    private static Method bodyYawMethod() {
        if (!bodyYawLooked) {
            try {
                setBodyYaw = LivingEntity.class.getMethod("setBodyYaw", float.class);
            } catch (NoSuchMethodException e) {
                setBodyYaw = null;
            }
            bodyYawLooked = true;
        }
        return setBodyYaw;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    /**
     * 플레이어 스킨을 입힌다. Spigot 은 {@code setProfile(PlayerProfile)}, Paper 는
     * {@code setProfile(ResolvableProfile)} 이라 매개변수 모양을 보고 맞춰 넘긴다.
     */
    private static void applySkin(LivingEntity mannequin, Player owner, Logger log) {
        try {
            // Paper 는 getPlayerProfile() 의 반환형이 달라서 직접 부르면 Spigot 에서 깨진다
            Object profile = owner.getClass().getMethod("getPlayerProfile").invoke(owner);
            for (Method method : MANNEQUIN.getMethods()) {
                if (!method.getName().equals("setProfile") || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> param = method.getParameterTypes()[0];
                if (param.isInstance(profile)) {
                    method.invoke(mannequin, profile);
                    return;
                }
                for (Method factory : param.getMethods()) {
                    if (factory.getName().equals("resolvableProfile") && factory.getParameterCount() == 1
                            && factory.getParameterTypes()[0].isInstance(profile)) {
                        method.invoke(mannequin, factory.invoke(null, profile));
                        return;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.FINE, "마네킹에 스킨을 입히지 못했습니다.", e);
        }
    }

    /** 이름 아래 붙는 "NPC" 설명을 지운다. */
    private static void clearDescription(Entity mannequin) {
        try {
            for (Method method : MANNEQUIN.getMethods()) {
                if (method.getName().equals("setDescription") && method.getParameterCount() == 1) {
                    Class<?> param = method.getParameterTypes()[0];
                    method.invoke(mannequin, param.getMethod("empty").invoke(null));
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 설명이 남아도 쓰는 데는 지장 없다
        }
    }

    private static void call(Object target, String name, boolean value) {
        try {
            MANNEQUIN.getMethod(name, boolean.class).invoke(target, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 없는 버전이면 넘어간다
        }
    }
}
