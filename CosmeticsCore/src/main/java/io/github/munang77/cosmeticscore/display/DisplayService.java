package io.github.munang77.cosmeticscore.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Attachment;
import io.github.munang77.cosmeticscore.cosmetic.Attachment.Vec3;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.DisplayCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Motion;
import io.github.munang77.cosmeticscore.util.Facing;
import io.github.munang77.cosmeticscore.util.Text;
import io.github.munang77.cosmeticscore.util.Visibility;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 백팩, 날개, 꼬리, 허리, 상체 장식, 풍선, 펫. 서버에 저장되지 않는 디스플레이 엔티티를 매 틱 주인 곁으로 옮긴다.
 *
 * <p>플레이어에게 태우지(passenger) 않고 따로 움직이기 때문에 텔레포트나 탈것을 막지 않는다. 몸 장식은 몸 방향과
 * 웅크리기를 따라가고, 날개는 퍼덕이고 꼬리는 살랑인다. 제자리에 있으면 위치를 다시 보내지 않는다.
 *
 * <p>플레이어 말고도 {@link Host} 를 붙이면 (옷장의 마네킹) 같은 방식으로 입힌다.
 */
public final class DisplayService implements Listener {

    /** 장식을 입는 대상. */
    public interface Host {

        UUID id();

        /** 위치와 월드의 기준 (발). */
        Entity entity();

        /** 펫 이름표의 {@code {player}}. */
        String name();

        /** 지금 보여 줄 장식 (없으면 {@code null}). */
        DisplayCosmetic equipped(Category category);

        /** 모든 장식을 숨긴다 (투명화, 관전자, 꺼진 월드). */
        boolean hidden();

        boolean sneaking();

        /** 겉날개, 수영, 잠자기처럼 누운 자세. 몸 장식만 숨긴다. */
        boolean lying();

        /** 몸 방향 (도). {@code NaN} 이면 머리 방향으로 짐작한다. */
        float bodyYaw();

        /** 이 플레이어에게만 보인다. {@code null} 이면 "다른 사람 효과 보기" 설정에 따라 모두에게. */
        Player onlyFor();
    }

    private static final Category[] PARTS = Arrays.stream(Category.values())
            .filter(Category::isDisplay).toArray(Category[]::new);
    private static final int RESPAWN_COOLDOWN_TICKS = 100;
    /** 몸 장식 움직임을 몇 틱마다 보낼지 (사이는 클라이언트가 부드럽게 잇는다). */
    private static final int MOTION_PERIOD = 2;

    private final CosmeticsCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, Rig> rigs = new HashMap<>();
    /** 플레이어가 아닌 대상 (옷장 마네킹). */
    private final Map<UUID, Host> extraHosts = new HashMap<>();
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
        extraHosts.clear();
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
        update(new PlayerHost(player, plugin.manager()));
    }

    /** 플레이어가 아닌 대상에게 장식을 입힌다 ({@link #detach} 로 벗긴다). */
    public void attach(Host host) {
        extraHosts.put(host.id(), host);
        update(host);
    }

    public void detach(UUID id) {
        if (extraHosts.remove(id) != null) {
            dropRig(id);
        }
    }

    private void dropRig(UUID id) {
        Rig rig = rigs.remove(id);
        if (rig != null) {
            rig.removeAll();
        }
    }

    /** 대상의 장식을 바로 다시 맞춘다 (옷장에서 고른 게 바뀌었을 때). */
    public void refresh(Host host) {
        update(host);
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
        dropRig(id);
        // 다시 들어오면 새 플레이어 객체라 숨김 상태가 풀린다 → 기록도 지운다
        for (Rig other : rigs.values()) {
            other.hiddenFrom.remove(id);
        }
    }

    // ── 매 틱 ────────────────────────────────────

    private void tick() {
        tick++;
        CosmeticManager manager = plugin.manager();
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(new PlayerHost(player, manager));
        }
        Iterator<Host> hosts = extraHosts.values().iterator();
        while (hosts.hasNext()) {
            Host host = hosts.next();
            if (host.entity().isValid()) {
                update(host);
            } else {
                dropRig(host.id());
                hosts.remove();
            }
        }
        Viewers viewers = tick % 20 == 0 ? new Viewers(manager) : null;
        Iterator<Map.Entry<UUID, Rig>> it = rigs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Rig> entry = it.next();
            if (extraHosts.containsKey(entry.getKey())) {
                continue;
            }
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

    private void update(Host host) {
        UUID id = host.id();
        Rig rig = rigs.get(id);
        DisplayCosmetic[] wanted = new DisplayCosmetic[PARTS.length];
        boolean any = false;
        for (int i = 0; i < PARTS.length; i++) {
            wanted[i] = host.equipped(PARTS[i]);
            any |= wanted[i] != null;
        }
        if (!any && rig == null) {
            return;
        }
        if (any && host.hidden()) {
            Arrays.fill(wanted, null);
        }
        Entity body = host.entity();
        Viewers viewers = null;
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
                rig = new Rig(body.getLocation());
                rigs.put(id, rig);
            }
            if (part != null && part.cosmetic == want && part.valid(body.getWorld())) {
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
            Part created = spawn(host, rig, want);
            if (created == null) {
                rig.blockedUntil.put(category, tick + RESPAWN_COOLDOWN_TICKS);
                continue;
            }
            rig.blockedUntil.remove(category);
            rig.parts.put(category, created);
            if (host.onlyFor() == null) {
                for (UUID viewerId : rig.hiddenFrom) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null) {
                        created.hideFrom(plugin, viewer);
                    }
                }
                if (body instanceof Player owner) {
                    if (viewers == null) {
                        viewers = new Viewers(plugin.manager());
                    }
                    sync(owner, rig, viewers);
                }
            }
        }
        if (rig == null) {
            return;
        }
        if (rig.parts.isEmpty()) {
            rigs.remove(id);
            return;
        }
        Pose pose = rig.pose(host, tick);
        for (Part part : rig.parts.values()) {
            place(pose, part, false);
        }
    }

    // ── 만들기 ───────────────────────────────────

    private Part spawn(Host host, Rig rig, DisplayCosmetic cosmetic) {
        Entity body = host.entity();
        World world = body.getWorld();
        Part part = new Part(cosmetic);
        part.world = world;
        Location start = body.getLocation();
        Player onlyFor = host.onlyFor();
        try {
            for (int side : cosmetic.mirrored() ? new int[] {1, -1} : new int[] {0}) {
                ItemDisplay display = world.spawn(start, ItemDisplay.class, d -> {
                    prepare(d, onlyFor);
                    d.setItemStack(cosmetic.itemAt(tick).create(plugin.getLogger()));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    d.setTransformation(initial(cosmetic, side));
                });
                part.pieces.add(new Piece(display, side, cosmetic));
                spawned.add(display);
            }
            if (cosmetic.stringBlock() != null) {
                BlockDisplay line = world.spawn(start, BlockDisplay.class, d -> {
                    prepare(d, onlyFor);
                    d.setBlock(cosmetic.stringBlock().createBlockData());
                });
                part.string = new Piece(line, 0, null);
                spawned.add(line);
            }
            if (cosmetic.nameTag() != null) {
                String text = Text.replace(cosmetic.nameTag(), "player", host.name());
                TextDisplay tag = world.spawn(start, TextDisplay.class, d -> {
                    prepare(d, onlyFor);
                    d.setText(text);
                    d.setBillboard(Display.Billboard.CENTER);
                });
                part.tag = new Piece(tag, 0, null);
                spawned.add(tag);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning(host.name() + " 의 " + cosmetic.category().key()
                    + " 장식을 만들지 못했습니다: " + e);
            part.remove();
            return null;
        }
        if (!part.valid(world)) {
            // 다른 플러그인이 소환을 막았다
            part.remove();
            return null;
        }
        if (onlyFor != null) {
            for (Entity e : part.entities()) {
                Visibility.revealOnlyTo(plugin, onlyFor, e);
            }
        }
        place(rig.pose(host, tick), part, true);
        return part;
    }

    private static Transformation initial(DisplayCosmetic cosmetic, int side) {
        return cosmetic.category().isBody()
                ? BodyMath.transform(cosmetic.attachment(), cosmetic.scale(), side, 0)
                : BodyMath.scaled(cosmetic.scale());
    }

    private void prepare(Display display, Player onlyFor) {
        display.setPersistent(false);
        display.setTeleportDuration(2);
        display.setInterpolationDuration(MOTION_PERIOD);
        display.setShadowRadius(0);
        display.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        if (onlyFor != null) {
            Visibility.hideByDefault(display);
        }
    }

    // ── 위치 ─────────────────────────────────────

    /** 이번 틱의 주인 모습 (장식마다 다시 계산하지 않도록 한 번만 구한다). */
    private record Pose(Vector feet, float bodyYaw, Vector forward, Vector right,
                        boolean sneaking, boolean moving, boolean lying) {
    }

    private void place(Pose pose, Part part, boolean snap) {
        DisplayCosmetic c = part.cosmetic;
        if (c.cycles() && tick % c.cycleTicks() == 0) {
            ItemStack item = c.itemAt(tick).create(plugin.getLogger());
            for (Piece piece : part.pieces) {
                ((ItemDisplay) piece.display).setItemStack(item);
            }
        }
        if (c.category().isBody()) {
            placeBody(pose, part, snap);
            return;
        }
        Vector feet = pose.feet();
        Vector forward = pose.forward();
        Vector right = pose.right();
        boolean sneaking = pose.sneaking();
        Piece main = part.pieces.get(0);
        double lift = c.attachment().offset().y();

        if (c.category() == Category.BALLOON) {
            double bob = Math.sin(tick * 0.08) * 0.12;
            Vector target = feet.clone().add(right.clone().multiply(0.9)).add(forward.clone().multiply(-0.5))
                    .add(new Vector(0, 2.5 + lift + bob, 0));
            part.pos = follow(part.pos, target, 0.18, snap);
            main.moveTo(part.pos, pose.bodyYaw(), 0);
            if (part.string != null) {
                Vector anchor = feet.clone().add(right.clone().multiply(0.35)).add(forward.clone().multiply(0.15))
                        .add(new Vector(0, sneaking ? 0.8 : 1.0, 0));
                Vector bottom = part.pos.clone().add(new Vector(0, -0.3 * c.scale(), 0));
                stretch(part.string, anchor, bottom);
            }
        } else if (c.category() == Category.PET) {
            double bob = Math.sin(tick * 0.12) * 0.08;
            Vector target = feet.clone().add(right.clone().multiply(-0.85)).add(forward.clone().multiply(-0.35))
                    .add(new Vector(0, (sneaking ? 0.95 : 1.2) + lift + bob, 0));
            part.pos = follow(part.pos, target, 0.2, snap);
            main.moveTo(part.pos, pose.bodyYaw(), 0);
            if (part.tag != null) {
                part.tag.moveTo(part.pos.clone().add(new Vector(0, 0.35 * c.scale() + 0.3, 0)), 0, 0);
            }
        }
    }

    /** 몸통에 붙는 장식: 몸 방향으로 돌리고, 웅크리면 같이 숙이고, 누우면 숨긴다. */
    private void placeBody(Pose pose, Part part, boolean snap) {
        DisplayCosmetic c = part.cosmetic;
        Attachment a = c.attachment();
        boolean hide = pose.lying();
        boolean animate = a.motion() != Motion.NONE && !hide;
        if (animate) {
            part.phase = (part.phase + BodyMath.step(a, pose.moving())) % (Math.PI * 2);
        }
        boolean sendMotion = animate && (snap || tick % MOTION_PERIOD == 0);
        boolean sneaking = pose.sneaking();
        float pitch = sneaking ? BodyMath.SNEAK_LEAN_DEGREES : 0;
        Vector feet = pose.feet();
        Vector right = pose.right();
        Vector forward = pose.forward();
        for (Piece piece : part.pieces) {
            Vec3 at = sneaking ? piece.sneak : piece.stand;
            piece.moveTo(feet.getX() + right.getX() * at.x() + forward.getX() * at.z(), feet.getY() + at.y(),
                    feet.getZ() + right.getZ() * at.x() + forward.getZ() * at.z(), pose.bodyYaw(), pitch);
            if (hide != part.hidden || sendMotion) {
                piece.display.setInterpolationDelay(0);
                piece.display.setTransformation(hide ? BodyMath.hidden()
                        : BodyMath.transform(a, c.scale(), piece.side, part.phase));
            }
        }
        part.hidden = hide;
    }

    /** 목표 쪽으로 조금씩 따라가서 흔들리는 느낌을 낸다. 너무 멀면(텔레포트 등) 바로 옮긴다. */
    static Vector follow(Vector current, Vector target, double rate, boolean snap) {
        if (snap || current == null || current.distanceSquared(target) > 36) {
            return target.clone();
        }
        return current.clone().add(target.clone().subtract(current).multiply(rate));
    }

    /** 가는 블록을 {@code from} 에서 {@code to} 까지 늘여 풍선 줄을 그린다. */
    private static void stretch(Piece line, Vector from, Vector to) {
        Vector dir = to.clone().subtract(from);
        double length = dir.length();
        if (length < 1e-3 || !line.moveTo(from, 0, 0) && line.lastLength == length) {
            return;
        }
        line.lastLength = length;
        dir.multiply(1 / length);
        Quaternionf rotation = new Quaternionf().rotationTo(0, 1, 0, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        line.display.setInterpolationDelay(0);
        line.display.setTransformation(new Transformation(new Vector3f(), rotation,
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

    /** 플레이어를 {@link Host} 로 본다. */
    private record PlayerHost(Player player, CosmeticManager manager) implements Host {

        @Override
        public UUID id() {
            return player.getUniqueId();
        }

        @Override
        public Entity entity() {
            return player;
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public DisplayCosmetic equipped(Category category) {
            return manager.equipped(player, category, DisplayCosmetic.class);
        }

        @Override
        public boolean hidden() {
            return manager.isHidden(player);
        }

        @Override
        public boolean sneaking() {
            return player.isSneaking();
        }

        @Override
        public boolean lying() {
            return player.isGliding() || player.isSwimming() || player.isSleeping() || player.isRiptiding();
        }

        @Override
        public float bodyYaw() {
            return Float.NaN;
        }

        @Override
        public Player onlyFor() {
            return null;
        }
    }

    /** 한 대상의 장식 묶음. */
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
         * 이번 틱의 모습. 몸 방향을 따로 주지 않으면 머리보다 느리게 따라가고, 50도 넘게 벌어지지 않는다
         * (걸을 때는 빨리 따라간다). 같은 틱에 여러 번 불려도 한 번만 계산한다.
         */
        Pose pose(Host host, long now) {
            if (pose != null && poseTick == now) {
                return pose;
            }
            Location loc = host.entity().getLocation();
            Vector feet = loc.toVector();
            boolean moving = feet.distanceSquared(lastFeet) > 0.0009;
            lastFeet = feet;
            float given = host.bodyYaw();
            if (!Float.isNaN(given)) {
                bodyYaw = given;
            } else {
                float head = loc.getYaw();
                float diff = Location.normalizeYaw(head - bodyYaw);
                if (Math.abs(diff) > 50) {
                    bodyYaw = Location.normalizeYaw(head - Math.signum(diff) * 50);
                } else {
                    bodyYaw = Location.normalizeYaw(bodyYaw + diff * (moving ? 0.35f : 0.08f));
                }
            }
            poseTick = now;
            pose = new Pose(feet, bodyYaw, Facing.forward(bodyYaw), Facing.right(bodyYaw),
                    host.sneaking(), moving, host.lying());
            return pose;
        }

        void removeAll() {
            for (Part part : parts.values()) {
                part.remove();
            }
            parts.clear();
        }
    }

    /** 장식 하나 (본체 한두 조각 + 풍선 줄 / 펫 이름표). */
    private static final class Part {
        final DisplayCosmetic cosmetic;
        final List<Piece> pieces = new ArrayList<>(2);
        World world;
        Piece string;
        Piece tag;
        /** 풍선·펫이 따라가는 자리. */
        Vector pos;
        /** 몸 장식 움직임 위상. */
        double phase;
        /** 누운 자세라 크기를 0 으로 줄여 둔 상태. */
        boolean hidden;

        Part(DisplayCosmetic cosmetic) {
            this.cosmetic = cosmetic;
        }

        boolean valid(World current) {
            if (pieces.isEmpty() || !current.equals(world)) {
                return false;
            }
            for (Piece piece : pieces) {
                if (!piece.display.isValid()) {
                    return false;
                }
            }
            return (string == null || string.display.isValid()) && (tag == null || tag.display.isValid());
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
            List<Entity> out = new ArrayList<>(pieces.size() + 2);
            for (Piece piece : pieces) {
                out.add(piece.display);
            }
            if (string != null) {
                out.add(string.display);
            }
            if (tag != null) {
                out.add(tag.display);
            }
            return out;
        }

        void remove() {
            for (Entity e : entities()) {
                e.remove();
            }
        }
    }

    /** 디스플레이 엔티티 하나와 마지막으로 보낸 자리 (그대로면 다시 보내지 않는다). */
    private static final class Piece {
        final Display display;
        /** 0 = 한 개, 1 = 오른쪽, -1 = 왼쪽. */
        final int side;
        /** 몸 장식이 서 있을 때 / 웅크렸을 때 붙는 자리 (발 기준 오른쪽/위/앞). 몸 장식이 아니면 {@code null}. */
        final Vec3 stand;
        final Vec3 sneak;
        private double x = Double.NaN;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        /** 풍선 줄 길이. */
        double lastLength = -1;

        Piece(Display display, int side, DisplayCosmetic body) {
            this.display = display;
            this.side = side;
            if (body != null && body.category().isBody()) {
                stand = BodyMath.side(BodyMath.anchor(body.category()).plus(body.attachment().offset()), side);
                sneak = BodyMath.lean(stand, true);
            } else {
                stand = null;
                sneak = null;
            }
        }

        /** @return 실제로 옮겼으면 {@code true} */
        boolean moveTo(Vector to, float yaw, float pitch) {
            return moveTo(to.getX(), to.getY(), to.getZ(), yaw, pitch);
        }

        boolean moveTo(double nx, double ny, double nz, float yaw, float pitch) {
            if (Math.abs(nx - x) < 1e-4 && Math.abs(ny - y) < 1e-4 && Math.abs(nz - z) < 1e-4
                    && Math.abs(yaw - this.yaw) < 0.05f && Math.abs(pitch - this.pitch) < 0.05f) {
                return false;
            }
            x = nx;
            y = ny;
            z = nz;
            this.yaw = yaw;
            this.pitch = pitch;
            display.teleport(new Location(display.getWorld(), nx, ny, nz, yaw, pitch));
            return true;
        }
    }
}
