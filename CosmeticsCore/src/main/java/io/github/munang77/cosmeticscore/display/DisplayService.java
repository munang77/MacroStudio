package io.github.munang77.cosmeticscore.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.DisplayCosmetic;
import io.github.munang77.cosmeticscore.util.Facing;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private static final Category[] PARTS = Arrays.stream(Category.values())
            .filter(Category::isDisplay).toArray(Category[]::new);
    private static final int RESPAWN_COOLDOWN_TICKS = 100;

    private final CosmeticsCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, Rig> rigs = new HashMap<>();
    /** 이 서비스가 소환한 엔티티. 정리할 때 월드 전체가 아니라 이것만 본다. */
    private final Set<Entity> spawned = new HashSet<>();
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
        spawned.clear();
    }

    /** 서버가 갑자기 꺼져서 남은 장식이 있으면 지운다 (저장되지 않게 만들지만 혹시 몰라서). 켤 때 한 번만. */
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

    /** 보기 설정이 바뀌었을 때 모든 장식의 보이기를 다시 맞춘다. */
    public void syncVisibility() {
        Viewers viewers = new Viewers(plugin.manager());
        for (Map.Entry<UUID, Rig> entry : rigs.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner != null) {
                sync(owner, entry.getValue(), viewers);
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
        Viewers viewers = tick % 20 == 0 ? new Viewers(plugin.manager()) : null;
        Iterator<Map.Entry<UUID, Rig>> it = rigs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Rig> entry = it.next();
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null) {
                entry.getValue().removeAll();
                it.remove();
            } else if (viewers != null) {
                sync(owner, entry.getValue(), viewers);
            }
        }
        if (tick % 100 == 0) {
            sweep();
        }
    }

    /** 소환한 엔티티 중 어느 장식에도 속하지 않게 된 것(소환 도중 오류 등)을 지운다. */
    private void sweep() {
        Set<Entity> tracked = new HashSet<>();
        for (Rig rig : rigs.values()) {
            for (Part part : rig.parts.values()) {
                tracked.addAll(part.entities());
            }
        }
        spawned.removeIf(e -> {
            if (!e.isValid()) {
                return true;
            }
            if (!tracked.contains(e)) {
                e.remove();
                return true;
            }
            return false;
        });
    }

    private void update(Player player) {
        UUID id = player.getUniqueId();
        Rig rig = rigs.get(id);
        CosmeticManager manager = plugin.manager();
        DisplayCosmetic[] wanted = new DisplayCosmetic[PARTS.length];
        boolean any = false;
        for (int i = 0; i < PARTS.length; i++) {
            wanted[i] = manager.equipped(player, PARTS[i], DisplayCosmetic.class);
            any |= wanted[i] != null;
        }
        if (!any && rig == null) {
            return;
        }
        if (any && manager.isHidden(player)) {
            Arrays.fill(wanted, null);
        }
        for (int i = 0; i < PARTS.length; i++) {
            Category category = PARTS[i];
            DisplayCosmetic want = wanted[i];
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
            Part created = spawn(player, rig, want);
            if (created == null) {
                rig.blockedUntil.put(category, tick + RESPAWN_COOLDOWN_TICKS);
                continue;
            }
            rig.blockedUntil.remove(category);
            rig.parts.put(category, created);
            for (UUID viewerId : rig.hiddenFrom) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    created.hideFrom(plugin, viewer);
                }
            }
            sync(player, rig, new Viewers(manager));
        }
        if (rig == null) {
            return;
        }
        if (rig.parts.isEmpty()) {
            rigs.remove(id);
            return;
        }
        Pose pose = rig.pose(player, tick);
        for (Part part : rig.parts.values()) {
            place(pose, part, false);
        }
    }

    // ── 만들기 ───────────────────────────────────

    private Part spawn(Player player, Rig rig, DisplayCosmetic cosmetic) {
        World world = player.getWorld();
        Part part = new Part(cosmetic);
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
            spawned.add(part.main);
            if (cosmetic.stringBlock() != null) {
                part.string = world.spawn(start, BlockDisplay.class, d -> {
                    prepare(d);
                    d.setBlock(cosmetic.stringBlock().createBlockData());
                });
                spawned.add(part.string);
            }
            if (cosmetic.nameTag() != null) {
                String text = Text.replace(cosmetic.nameTag(), "player", player.getName());
                part.tag = world.spawn(start, TextDisplay.class, d -> {
                    prepare(d);
                    d.setText(text);
                    d.setBillboard(Display.Billboard.CENTER);
                });
                spawned.add(part.tag);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning(player.getName() + " 의 " + cosmetic.category().key()
                    + " 장식을 만들지 못했습니다: " + e);
            part.remove();
            return null;
        }
        if (!part.valid(world)) {
            // 다른 플러그인이 소환을 막았다
            part.remove();
            return null;
        }
        place(rig.pose(player, tick), part, true);
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

    /** 이번 틱의 주인 모습 (장식마다 다시 계산하지 않도록 한 번만 구한다). */
    private record Pose(Vector feet, float bodyYaw, Vector forward, Vector right, boolean sneaking) {
    }

    private void place(Pose pose, Part part, boolean snap) {
        DisplayCosmetic c = part.cosmetic;
        if (c.cycles() && tick % c.cycleTicks() == 0) {
            part.main.setItemStack(c.itemAt(tick).create(plugin.getLogger()));
        }
        Vector feet = pose.feet();
        Vector forward = pose.forward();
        Vector right = pose.right();
        boolean sneaking = pose.sneaking();

        switch (c.category()) {
            case BACKPACK -> {
                Vector at = feet.clone()
                        .add(forward.clone().multiply(sneaking ? -0.38 : -0.28))
                        .add(new Vector(0, (sneaking ? 0.85 : 1.1) + c.offsetY(), 0));
                move(part.main, at, pose.bodyYaw());
            }
            case BALLOON -> {
                double bob = Math.sin(tick * 0.08) * 0.12;
                Vector target = feet.clone().add(right.clone().multiply(0.9)).add(forward.clone().multiply(-0.5))
                        .add(new Vector(0, 2.5 + c.offsetY() + bob, 0));
                part.pos = follow(part.pos, target, 0.18, snap);
                move(part.main, part.pos, pose.bodyYaw());
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
                move(part.main, part.pos, pose.bodyYaw());
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

    /** 한 번 확인할 때 쓰는 접속자 목록과 각자의 "다른 사람 효과 보기" 설정 (장식마다 다시 읽지 않는다). */
    private static final class Viewers {
        final List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        final boolean[] seesOthers = new boolean[players.size()];
        final Set<UUID> online = new HashSet<>();

        Viewers(CosmeticManager manager) {
            for (int i = 0; i < players.size(); i++) {
                seesOthers[i] = manager.seesOthers(players.get(i));
                online.add(players.get(i).getUniqueId());
            }
        }
    }

    /** 다른 사람 효과를 끈 플레이어와 주인을 볼 수 없는 플레이어에게는 숨긴다. */
    private void sync(Player owner, Rig rig, Viewers viewers) {
        for (int i = 0; i < viewers.players.size(); i++) {
            Player viewer = viewers.players.get(i);
            if (viewer.equals(owner)) {
                continue;
            }
            UUID vid = viewer.getUniqueId();
            boolean should = viewers.seesOthers[i] && viewer.canSee(owner);
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
        rig.hiddenFrom.retainAll(viewers.online);
    }

    // ── 자료 ─────────────────────────────────────

    /** 한 플레이어의 장식 묶음. */
    private static final class Rig {
        final Map<Category, Part> parts = new EnumMap<>(Category.class);
        final Set<UUID> hiddenFrom = new HashSet<>();
        /** 소환이 막혔던 카테고리는 잠시 다시 시도하지 않는다 (다른 카테고리는 영향 없음). */
        final Map<Category, Long> blockedUntil = new EnumMap<>(Category.class);
        float bodyYaw;
        Vector lastFeet;
        long poseTick = -1;
        Pose pose;

        Rig(Location at) {
            this.bodyYaw = at.getYaw();
            this.lastFeet = at.toVector();
        }

        /**
         * 이번 틱의 모습. 몸통 방향은 머리보다 느리게 따라가고, 50도 넘게 벌어지지 않는다 (걸을 때는 빨리 따라간다).
         * 같은 틱에 여러 번 불려도 한 번만 계산한다.
         */
        Pose pose(Player player, long now) {
            if (pose != null && poseTick == now) {
                return pose;
            }
            Location loc = player.getLocation();
            Vector feet = loc.toVector();
            boolean moving = feet.distanceSquared(lastFeet) > 0.0009;
            lastFeet = feet;
            float head = loc.getYaw();
            float diff = Location.normalizeYaw(head - bodyYaw);
            if (Math.abs(diff) > 50) {
                bodyYaw = Location.normalizeYaw(head - Math.signum(diff) * 50);
            } else {
                bodyYaw = Location.normalizeYaw(bodyYaw + diff * (moving ? 0.35f : 0.08f));
            }
            poseTick = now;
            pose = new Pose(feet, bodyYaw, Facing.forward(bodyYaw), Facing.right(bodyYaw), player.isSneaking());
            return pose;
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
        final DisplayCosmetic cosmetic;
        World world;
        ItemDisplay main;
        BlockDisplay string;
        TextDisplay tag;
        Vector pos;

        Part(DisplayCosmetic cosmetic) {
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

        List<Entity> entities() {
            return Stream.of(main, string, tag).filter(Objects::nonNull).map(Entity.class::cast).toList();
        }

        void remove() {
            for (Entity e : entities()) {
                e.remove();
            }
        }
    }
}
