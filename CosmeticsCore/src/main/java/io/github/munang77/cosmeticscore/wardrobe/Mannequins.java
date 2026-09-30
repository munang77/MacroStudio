package io.github.munang77.cosmeticscore.wardrobe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
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
    // 리플렉션으로 부를 메서드는 처음에 한 번만 찾아 둔다 (없는 버전이면 null)
    private static final Method SET_BODY_YAW = find(LivingEntity.class, "setBodyYaw", float.class);
    private static final Method GET_PROFILE = find(Player.class, "getPlayerProfile");
    private static final Method SET_IMMOVABLE = MANNEQUIN == null ? null : find(MANNEQUIN, "setImmovable", boolean.class);
    private static final List<ProfileSetter> SET_PROFILE = profileSetters();
    private static final Method SET_DESCRIPTION = oneArgument("setDescription");
    private static final Object EMPTY_DESCRIPTION = emptyDescription();

    /**
     * 스킨 넣는 방법 하나. Spigot 은 {@code setProfile(PlayerProfile)}, Paper 는 {@code setProfile(ResolvableProfile)} 이라
     * 매개변수가 플레이어 프로필을 바로 받지 못하면 {@code resolvableProfile(...)} 로 바꿔 넘긴다.
     */
    private record ProfileSetter(Method set, Method convert) {
    }

    private Mannequins() {
    }

    private static Method find(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    private static Method oneArgument(String name) {
        if (MANNEQUIN != null) {
            for (Method method : MANNEQUIN.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1) {
                    return method;
                }
            }
        }
        return null;
    }

    private static List<ProfileSetter> profileSetters() {
        List<ProfileSetter> out = new ArrayList<>();
        if (MANNEQUIN == null) {
            return out;
        }
        for (Method set : MANNEQUIN.getMethods()) {
            if (!set.getName().equals("setProfile") || set.getParameterCount() != 1) {
                continue;
            }
            Method convert = null;
            for (Method factory : set.getParameterTypes()[0].getMethods()) {
                if (factory.getName().equals("resolvableProfile") && factory.getParameterCount() == 1) {
                    convert = factory;
                }
            }
            out.add(new ProfileSetter(set, convert));
        }
        return out;
    }

    private static Object emptyDescription() {
        try {
            return SET_DESCRIPTION == null ? null : SET_DESCRIPTION.getParameterTypes()[0].getMethod("empty").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
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
                    invoke(SET_IMMOVABLE, m, true);
                    // 이름 아래 붙는 "NPC" 설명을 지운다
                    invoke(SET_DESCRIPTION, m, EMPTY_DESCRIPTION);
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
        // 몸 방향은 머리를 천천히 따라가므로 없는 버전이어도 된다
        invoke(SET_BODY_YAW, mannequin, yaw);
    }

    /** 있으면 부르고, 없거나 실패하면 넘어간다. */
    private static void invoke(Method method, Object target, Object argument) {
        if (method == null || argument == null) {
            return;
        }
        try {
            method.invoke(target, argument);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 없는 버전이면 넘어간다
        }
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    /** 플레이어 스킨을 입힌다 (Paper 는 getPlayerProfile() 의 반환형이 달라서 직접 부르면 Spigot 에서 깨진다). */
    private static void applySkin(LivingEntity mannequin, Player owner, Logger log) {
        if (GET_PROFILE == null) {
            return;
        }
        try {
            Object profile = GET_PROFILE.invoke(owner);
            for (ProfileSetter setter : SET_PROFILE) {
                if (setter.set().getParameterTypes()[0].isInstance(profile)) {
                    setter.set().invoke(mannequin, profile);
                    return;
                }
                Method convert = setter.convert();
                if (convert != null && convert.getParameterTypes()[0].isInstance(profile)) {
                    setter.set().invoke(mannequin, convert.invoke(null, profile));
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.FINE, "마네킹에 스킨을 입히지 못했습니다.", e);
        }
    }
}
