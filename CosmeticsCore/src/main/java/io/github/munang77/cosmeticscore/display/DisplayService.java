package io.github.munang77.cosmeticscore.display;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.DisplayCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.ItemSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 백팩, 풍선, 펫. 서버에 저장되지 않는 디스플레이 엔티티를 매 틱 플레이어 곁으로 옮긴다.
 *
 * <p>플레이어에게 태우지(passenger) 않고 따로 움직이기 때문에 텔레포트나 탈것을 막지 않는다.
 * "다른 사람 효과 보기"를 끈 플레이어에게는 엔티티 자체를 숨긴다.
 */
public final class DisplayService implements Listener {

    private static final Category[] PARTS = {Category.BACKPACK, Category.BALLOON, Category.PET};
    private static final int RESPAWN_COOLDOWN_TICKS = 100;

    private final CosmeticsCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, Rig> rigs = new HashMap<>();
    private BukkitTask task;
    private long tick;

    public DisplayService(CosmeticsCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "cosmetic_display");
    }

    public void start() {
        stop();
        removeLeftovers();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Rig rig : rigs.values()) {
            rig.removeAll();
        }
        rigs.clear();
    }

    /** 서버가 갑자기 꺼져서 남은 장식이 있으면 지운다 (저장되지 않게 만들지만 혹시 몰라서). */
    private void removeLeftovers() {
        for (World world : Bukkit.getWorlds()) {
            for (Display display : world.getEntitiesByClass(Display.class)) {
                if (display.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
    }

    /** 착용 정보가 바뀌었을 때 바로 맞춘다. */
    public void refresh(Player player) {
        update(player);
    }

    /** 이 플러그인이 만든 장식 엔티티인지. */
    public boolean isCosmeticEntity(Entity entity) {
        return entity instanceof Display && entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** 보기 설정이 바뀌었을 때 모든 장식의 보이기를 다시 맞춘다. */
    public void syncVisibility() {
        for (Map.Entry<UUID, Rig> entry : rigs.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner != null) {
                sync(owner, entry.getValue());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Rig rig = rigs.remove(id);
        if (rig != null) {
            rig.removeAll();
        }
        // 다시 들어오면 새 플레이어 객체라 숨김 상태가 풀린다 → 기록도 지운다
        for (Rig other : rigs.values()) {
            other.hiddenFrom.remove(id);
        }
    }

    // ── 매 틱 ────────────────────────────────────

    private void tick() {
        tick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
        Iterator<Map.Entry<UUID, Rig>> it = rigs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Rig> entry = it.next();
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null) {
                entry.getValue().removeAll();
                it.remove();
            } else if (tick % 20 == 0) {
                sync(owner, entry.getValue());
            }
        }
        if (tick % 100 == 0) {
            sweep();
        }
    }

    /** 어느 장식에도 속하지 않은 이 플러그인의 엔티티를 지운다 (소환 도중 오류 등으로 남은 것). */
    private void sweep() {
        Set<UUID> tracked = new HashSet<>();
        for (Rig rig : rigs.values()) {
            for (Part part : rig.parts.values()) {
                for (Entity e : part.entities()) {
                    tracked.add(e.getUniqueId());
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Display display : world.getEntitiesByClass(Display.class)) {
                if (!tracked.contains(display.getUniqueId())
                        && display.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
    }

    private void update(Player player) {
        UUID id = player.getUniqueId();
        Rig rig = rigs.get(id);
        CosmeticManager manager = plugin.manager();
        boolean hidden = manager.isHidden(player);
        for (Category category : PARTS) {
            DisplayCosmetic want = hidden ? null : manager.equipped(player, category, DisplayCosmetic.class);
            Part part = rig == null ? null : rig.parts.get(category);
            if (want == null) {
                if (part != null) {
                    part.remove();
                    rig.parts.remove(category);
                }
                continue;
            }
            if (rig == null) {
                rig = new Rig(player.getLocation());
                rigs.put(id, rig);
            }
            if (part != null && part.cosmetic == want && part.valid(player.getWorld())) {
                continue;
            }
            if (part != null) {
                part.remove();
                rig.parts.remove(category);
            }
            Long blocked = rig.blockedUntil.get(category);
            if (blocked != null && tick < blocked) {
                continue;
            }
            Part spawned = spawn(player, rig, category, want);
            if (spawned == null) {
                rig.blockedUntil.put(category, tick + RESPAWN_COOLDOWN_TICKS);
                continue;
            }
            rig.blockedUntil.remove(category);
            rig.parts.put(category, spawned);
            for (UUID viewerId : rig.hiddenFrom) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    spawned.hideFrom(plugin, viewer);
                }
            }
            sync(player, rig);
        }
        if (rig == null) {
            return;
        }
        if (rig.parts.isEmpty()) {
            rigs.remove(id);
            return;
        }
        rig.updateBody(player);
        for (Part part : rig.parts.values()) {
            place(player, rig, part, false);
        }
    }

    // ── 만들기 ───────────────────────────────────

    private Part spawn(Player player, Rig rig, Category category, DisplayCosmetic cosmetic) {
        World world = player.getWorld();
        Part part = new Part(category, cosmetic);
        part.world = world;
        Location start = player.getLocation();
        float scale = cosmetic.scale();
        try {
            part.main = world.spawn(start, ItemDisplay.class, d -> {
                prepare(d);
                d.setItemStack(cosmetic.itemAt(tick).create(plugin.getLogger()));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(scale, scale, scale), new Quaternionf()));
            });
            if (category == Category.BALLOON && cosmetic.stringBlock() != null) {
                Material block = Material.matchMaterial(cosmetic.stringBlock());
                if (block != null && block.isBlock()) {
                    part.string = world.spawn(start, BlockDisplay.class, d -> {
                        prepare(d);
                        d.setBlock(block.createBlockData());
                    });
                }
            }
            if (category == Category.PET && cosmetic.nameTag() != null) {
                String text = cosmetic.nameTag().replace("{player}", player.getName());
                part.tag = world.spawn(start, TextDisplay.class, d -> {
                    prepare(d);
                    d.setText(text);
                    d.setBillboard(Display.Billboard.CENTER);
                });
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning(player.getName() + " 의 " + category.key() + " 장식을 만들지 못했습니다: " + e);
            part.remove();
            return null;
        }
        if (!part.valid(world)) {
            // 다른 플러그인이 소환을 막았다
            part.remove();
            return null;
        }
        part.shown = cosmetic.itemAt(tick);
        place(player, rig, part, true);
        return part;
    }

    private void prepare(Display display) {
        display.setPersistent(false);
        display.setTeleportDuration(2);
        display.setInterpolationDuration(2);
        display.setShadowRadius(0);
        display.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    // ── 위치 ─────────────────────────────────────

    private void place(Player player, Rig rig, Part part, boolean snap) {
        Location base = player.getLocation();
        double yaw = Math.toRadians(rig.bodyYaw);
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        boolean sneaking = player.isSneaking();
        DisplayCosmetic c = part.cosmetic;
        Vector feet = base.toVector();

        if (c.cycles() && tick % c.cycleTicks() == 0) {
            part.shown = c.itemAt(tick);
            part.main.setItemStack(part.shown.create(plugin.getLogger()));
        }

        switch (part.category) {
            case BACKPACK -> {
                Vector at = feet.clone()
                        .add(forward.clone().multiply(sneaking ? -0.38 : -0.28))
                        .add(new Vector(0, (sneaking ? 0.85 : 1.1) + c.offsetY(), 0));
                move(part.main, at, rig.bodyYaw);
            }
            case BALLOON -> {
                double bob = Math.sin(tick * 0.08) * 0.12;
                Vector target = feet.clone().add(right.clone().multiply(0.9)).add(forward.clone().multiply(-0.5))
                        .add(new Vector(0, 2.5 + c.offsetY() + bob, 0));
                part.pos = follow(part.pos, target, 0.18, snap);
                move(part.main, part.pos, rig.bodyYaw);
                if (part.string != null) {
                    Vector anchor = feet.clone().add(right.clone().multiply(0.35)).add(forward.clone().multiply(0.15))
                            .add(new Vector(0, sneaking ? 0.8 : 1.0, 0));
                    Vector bottom = part.pos.clone().add(new Vector(0, -0.3 * c.scale(), 0));
                    stretch(part.string, anchor, bottom);
                }
            }
            case PET -> {
                double bob = Math.sin(tick * 0.12) * 0.08;
                Vector target = feet.clone().add(right.clone().multiply(-0.85)).add(forward.clone().multiply(-0.35))
                        .add(new Vector(0, (sneaking ? 0.95 : 1.2) + c.offsetY() + bob, 0));
                part.pos = follow(part.pos, target, 0.2, snap);
                move(part.main, part.pos, rig.bodyYaw);
                if (part.tag != null) {
                    move(part.tag, part.pos.clone().add(new Vector(0, 0.35 * c.scale() + 0.3, 0)), 0);
                }
            }
            default -> {
            }
        }
    }

    /** 목표 쪽으로 조금씩 따라가서 흔들리는 느낌을 낸다. 너무 멀면(텔레포트 등) 바로 옮긴다. */
    static Vector follow(Vector current, Vector target, double rate, boolean snap) {
        if (snap || current == null || current.distanceSquared(target) > 36) {
            return target.clone();
        }
        return current.clone().add(target.clone().subtract(current).multiply(rate));
    }

    private static void move(Entity entity, Vector to, float yaw) {
        entity.teleport(new Location(entity.getWorld(), to.getX(), to.getY(), to.getZ(), yaw, 0));
    }

    /** 가는 블록을 {@code from} 에서 {@code to} 까지 늘여 풍선 줄을 그린다. */
    private static void stretch(BlockDisplay line, Vector from, Vector to) {
        Vector dir = to.clone().subtract(from);
        double length = dir.length();
        if (length < 1e-3) {
            return;
        }
        dir.multiply(1 / length);
        Quaternionf rotation = new Quaternionf().rotationTo(0, 1, 0, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        move(line, from, 0);
        line.setInterpolationDelay(0);
        line.setTransformation(new Transformation(new Vector3f(), rotation,
                new Vector3f(0.025f, (float) length, 0.025f), new Quaternionf()));
    }

    // ── 보이기 ───────────────────────────────────

    /** 다른 사람 효과를 끈 플레이어와 주인을 볼 수 없는 플레이어에게는 숨긴다. */
    private void sync(Player owner, Rig rig) {
        Set<UUID> online = new HashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID vid = viewer.getUniqueId();
            online.add(vid);
            if (viewer.equals(owner)) {
                continue;
            }
            boolean should = plugin.manager().seesOthers(viewer) && viewer.canSee(owner);
            if (!should && rig.hiddenFrom.add(vid)) {
                for (Part part : rig.parts.values()) {
                    part.hideFrom(plugin, viewer);
                }
            } else if (should && rig.hiddenFrom.remove(vid)) {
                for (Part part : rig.parts.values()) {
                    part.showTo(plugin, viewer);
                }
            }
        }
        rig.hiddenFrom.retainAll(online);
    }

    // ── 자료 ─────────────────────────────────────

    /** 한 플레이어의 장식 묶음. */
    private static final class Rig {
        final Map<Category, Part> parts = new EnumMap<>(Category.class);
        final Set<UUID> hiddenFrom = new HashSet<>();
        float bodyYaw;
        Vector lastFeet;
        /** 소환이 막혔던 카테고리는 잠시 다시 시도하지 않는다 (다른 카테고리는 영향 없음). */
        final Map<Category, Long> blockedUntil = new EnumMap<>(Category.class);

        Rig(Location at) {
            this.bodyYaw = at.getYaw();
            this.lastFeet = at.toVector();
        }

        /** 몸통 방향: 머리보다 느리게 따라가고, 50도 넘게 벌어지지 않는다 (걸을 때는 빨리 따라간다). */
        void updateBody(Player player) {
            Location loc = player.getLocation();
            Vector feet = loc.toVector();
            boolean moving = lastFeet != null && feet.distanceSquared(lastFeet) > 0.0009;
            lastFeet = feet;
            float head = loc.getYaw();
            float diff = wrap(head - bodyYaw);
            if (Math.abs(diff) > 50) {
                bodyYaw = wrap(head - Math.signum(diff) * 50);
            } else {
                bodyYaw = wrap(bodyYaw + diff * (moving ? 0.35f : 0.08f));
            }
        }

        static float wrap(float degrees) {
            float d = degrees % 360f;
            if (d >= 180f) {
                d -= 360f;
            } else if (d < -180f) {
                d += 360f;
            }
            return d;
        }

        void removeAll() {
            for (Part part : parts.values()) {
                part.remove();
            }
            parts.clear();
        }
    }

    /** 장식 하나 (본체 + 풍선 줄 / 펫 이름표). */
    private static final class Part {
        final Category category;
        final DisplayCosmetic cosmetic;
        World world;
        ItemDisplay main;
        BlockDisplay string;
        TextDisplay tag;
        Vector pos;
        ItemSpec shown;

        Part(Category category, DisplayCosmetic cosmetic) {
            this.category = category;
            this.cosmetic = cosmetic;
        }

        boolean valid(World current) {
            return main != null && main.isValid() && current.equals(world)
                    && (string == null || string.isValid()) && (tag == null || tag.isValid());
        }

        void hideFrom(CosmeticsCore plugin, Player viewer) {
            for (Entity e : entities()) {
                viewer.hideEntity(plugin, e);
            }
        }

        void showTo(CosmeticsCore plugin, Player viewer) {
            for (Entity e : entities()) {
                viewer.showEntity(plugin, e);
            }
        }

        Entity[] entities() {
            int n = (main != null ? 1 : 0) + (string != null ? 1 : 0) + (tag != null ? 1 : 0);
            Entity[] out = new Entity[n];
            int i = 0;
            if (main != null) {
                out[i++] = main;
            }
            if (string != null) {
                out[i++] = string;
            }
            if (tag != null) {
                out[i] = tag;
            }
            return out;
        }

        void remove() {
            for (Entity e : entities()) {
                e.remove();
            }
        }
    }
}
