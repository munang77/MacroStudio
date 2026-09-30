package io.github.munang77.cosmeticscore.wardrobe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.Settings;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.util.Facing;
import io.github.munang77.cosmeticscore.util.Visibility;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 옷장. 플레이어 앞에 본인에게만 보이는 마네킹을 세우고 코스메틱을 입혀 본다.
 *
 * <p>관리자가 {@code /cos 옷장 설정} 으로 자리를 정해 두면 그 자리로 옮겨 가서 열고 닫을 때 돌아온다.
 * 정하지 않았으면 서 있는 자리에서 바로 연다. 옷장에 있는 동안에는 움직이거나 블록·아이템을 쓸 수 없고,
 * 웅크리거나, 맞거나, 다른 곳으로 이동하면 닫힌다.
 */
public final class WardrobeService implements Listener {

    private static final String LOCATION_PATH = "wardrobe.location";

    private final CosmeticsCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, WardrobeSession> sessions = new HashMap<>();
    private BukkitTask task;
    private long tick;

    public WardrobeService(CosmeticsCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "wardrobe");
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
        for (WardrobeSession session : new ArrayList<>(sessions.values())) {
            end(session, true, null);
        }
    }

    /** 서버가 갑자기 꺼져서 남은 마네킹과 버튼을 지운다. 켤 때 한 번만. */
    private void removeLeftovers() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isOurs(entity)) {
                    entity.remove();
                }
            }
        }
    }

    private boolean isOurs(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public boolean isOpen(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ── 열기 / 닫기 ──────────────────────────────

    /**
     * 옷장을 연다. 이미 열려 있으면 닫는다.
     *
     * @param start 처음 보여 줄 카테고리 ({@code null} 이면 첫 카테고리)
     * @return 열었으면 {@code true}
     */
    public boolean open(Player player, Category start) {
        Messages msg = plugin.messages();
        Settings settings = plugin.settings();
        WardrobeSession existing = sessions.get(player.getUniqueId());
        if (existing != null) {
            end(existing, true, "wardrobe.closed");
            return false;
        }
        if (!settings.wardrobeEnabled()) {
            msg.send(player, "wardrobe.disabled");
            return false;
        }
        if (plugin.store().get(player.getUniqueId()) == null) {
            return false;
        }
        if (settings.isDisabled(player.getWorld())) {
            msg.send(player, "disabled-world");
            return false;
        }
        if (player.isInsideVehicle() || player.isGliding() || player.isSleeping() || player.isDead()) {
            msg.send(player, "wardrobe.busy");
            return false;
        }
        List<Category> categories = new ArrayList<>();
        for (Category c : Category.values()) {
            if (c.isWardrobe() && !plugin.registry().of(c).isEmpty()) {
                categories.add(c);
            }
        }
        if (categories.isEmpty()) {
            msg.send(player, "wardrobe.empty");
            return false;
        }

        Location fixed = fixedLocation();
        Location view;
        Location origin = null;
        if (fixed != null) {
            if (settings.isDisabled(fixed.getWorld())) {
                msg.send(player, "disabled-world");
                return false;
            }
            view = fixed;
            origin = player.getLocation();
        } else {
            // 공중에 붙잡아 두면 서버가 "날기" 로 보고 내보낼 수 있다
            if (!player.isOnGround() && !player.isFlying() && !player.isInWater()) {
                msg.send(player, "wardrobe.busy");
                return false;
            }
            view = player.getLocation();
            view.setPitch(0);
        }
        Location stage = view.clone().add(Facing.forward(view.getYaw()).multiply(settings.wardrobeDistance()));
        stage.setYaw(Location.normalizeYaw(view.getYaw() + 180));
        stage.setPitch(0);
        if (fixed == null && !hasRoom(view, stage)) {
            msg.send(player, "wardrobe.no-space");
            return false;
        }

        LivingEntity mannequin;
        boolean[] hidden = {true};
        try {
            mannequin = Mannequins.spawn(stage, player, plugin.getLogger(), m -> hidden[0] = prepareMannequin(m));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("옷장 마네킹을 만들지 못했습니다: " + e);
            mannequin = null;
        }
        if (mannequin == null) {
            msg.send(player, "wardrobe.failed");
            return false;
        }
        reveal(player, mannequin, hidden[0]);
        WardrobeSession session = new WardrobeSession(plugin, player, mannequin, view, origin, categories,
                start, tick);
        sessions.put(player.getUniqueId(), session);
        buildButtons(session, stage);
        if (origin != null) {
            session.teleporting = true;
            boolean moved = player.teleport(view);
            session.teleporting = false;
            if (!moved) {
                // 다른 플러그인(지역 보호, 전투 중 등)이 옮기지 못하게 했다
                end(session, false, "wardrobe.failed");
                return false;
            }
            hideOthers(session);
        }
        session.applyLook(null);
        plugin.displays().attach(session.host());
        session.render();
        msg.sendList(player, "wardrobe.help");
        player.playSound(player.getLocation(), "block.chest.open", 0.6f, 1.2f);
        return true;
    }

    /** 옷장을 닫는다. */
    public void close(Player player) {
        WardrobeSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            end(session, true, "wardrobe.closed");
        }
    }

    /**
     * @param restore 옷장 자리로 옮겨 왔다면 원래 자리로 돌려보낼지
     * @param message 알릴 문구 ({@code null} 이면 알리지 않는다)
     */
    private void end(WardrobeSession session, boolean restore, String message) {
        Player player = session.player;
        if (sessions.remove(player.getUniqueId()) != session) {
            return;
        }
        plugin.displays().detach(session.mannequin.getUniqueId());
        for (Entity entity : session.entities()) {
            entity.remove();
        }
        if (session.origin != null) {
            showOthers(session);
            if (restore && player.isOnline()) {
                session.teleporting = true;
                player.teleport(session.origin);
                session.teleporting = false;
            }
        }
        if (message != null && player.isOnline()) {
            plugin.messages().send(player, message);
            player.playSound(player.getLocation(), "block.chest.close", 0.6f, 1.2f);
        }
    }

    // ── 옷장 자리 ────────────────────────────────

    /** 관리자가 정한 옷장 자리 (없거나 월드가 없으면 {@code null}). 형식: {@code 월드,x,y,z,yaw,pitch} */
    public Location fixedLocation() {
        String raw = plugin.getConfig().getString(LOCATION_PATH, "");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] p = raw.split(",");
        if (p.length != 6) {
            plugin.getLogger().warning("[config.yml] " + LOCATION_PATH + " 형식이 잘못됐습니다: " + raw);
            return null;
        }
        World world = Bukkit.getWorld(p[0].trim());
        if (world == null) {
            plugin.getLogger().warning("[config.yml] " + LOCATION_PATH + " 의 월드가 없습니다: " + p[0]);
            return null;
        }
        try {
            return new Location(world, Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()),
                    Double.parseDouble(p[3].trim()), Float.parseFloat(p[4].trim()), Float.parseFloat(p[5].trim()));
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("[config.yml] " + LOCATION_PATH + " 숫자가 잘못됐습니다: " + raw);
            return null;
        }
    }

    /** 옷장 자리를 정한다 ({@code null} 이면 지워서 서 있는 자리에서 열게 한다). */
    public void setFixedLocation(Location at) {
        String value = "";
        if (at != null) {
            value = String.format(Locale.ROOT, "%s,%.3f,%.3f,%.3f,%.1f,%.1f", at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ(), at.getYaw(), 0f);
        }
        // saveConfig() 는 메모리의 설정 전체를 덮어써서, 관리자가 고치고 아직 리로드하지 않은 내용이 사라진다.
        // 그래서 파일을 다시 읽어 이 값만 바꿔 쓴다 (주석은 그대로)
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set(LOCATION_PATH, value);
        try {
            disk.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "config.yml 에 옷장 자리를 저장하지 못했습니다.", e);
        }
        plugin.getConfig().set(LOCATION_PATH, value);
    }

    /** 서 있는 자리에서 열 때: 마네킹 자리와 가는 길이 막혀 있지 않은지. */
    private static boolean hasRoom(Location view, Location stage) {
        Vector step = stage.toVector().subtract(view.toVector());
        double length = step.length();
        step.normalize();
        for (double d = 1; d <= length + 0.01; d += 0.5) {
            Location at = view.clone().add(step.clone().multiply(d));
            if (!passable(at.getBlock()) || !passable(at.getBlock().getRelative(0, 1, 0))) {
                return false;
            }
        }
        return true;
    }

    private static boolean passable(Block block) {
        return !block.getType().isSolid() && !trigger(block);
    }

    /** 마네킹이 서면 눌려 버리는 블록 (압력판, 철사 덫). 문이나 레드스톤 장치를 대신 작동시키지 않게 피한다. */
    private static boolean trigger(Block block) {
        String name = block.getType().name();
        return name.endsWith("PRESSURE_PLATE") || name.equals("TRIPWIRE");
    }

    // ── 만들기 ───────────────────────────────────

    /** @return 기본으로 숨기지 못했으면 {@code false} */
    private boolean prepareMannequin(LivingEntity mannequin) {
        mannequin.setPersistent(false);
        mannequin.setInvulnerable(true);
        mannequin.setSilent(true);
        mannequin.setGravity(false);
        mannequin.setCollidable(false);
        mannequin.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        return Visibility.hideByDefault(mannequin);
    }

    private void reveal(Player owner, Entity entity, boolean hiddenByDefault) {
        Visibility.revealOnlyTo(plugin, owner, entity, hiddenByDefault);
    }

    /** 마네킹 앞(플레이어 쪽)에 떠 있는 버튼들. 마네킹을 가리지 않게 양옆과 발치에 둔다. */
    private void buildButtons(WardrobeSession session, Location stage) {
        Location view = session.view;
        Vector toPlayer = Facing.forward(view.getYaw()).multiply(-0.9);
        Vector right = Facing.right(view.getYaw());
        Location front = stage.clone().add(toPlayer);
        try {
            button(session, WardrobeSession.Action.ROTATE, front, right, -1.1, 1.9, "wardrobe.button.rotate-on");
            button(session, WardrobeSession.Action.EXIT, front, right, 1.1, 1.9, "wardrobe.button.exit");
            button(session, WardrobeSession.Action.PREV_ITEM, front, right, -1.1, 1.2, "wardrobe.button.prev-item");
            button(session, WardrobeSession.Action.NEXT_ITEM, front, right, 1.1, 1.2, "wardrobe.button.next-item");
            button(session, WardrobeSession.Action.PREV_CATEGORY, front, right, -1.1, 0.5, "wardrobe.button.prev-category");
            button(session, WardrobeSession.Action.NEXT_CATEGORY, front, right, 1.1, 0.5, "wardrobe.button.next-category");
            button(session, WardrobeSession.Action.EQUIP, front, right, 0, 0.15, "wardrobe.button.equip");
            session.info = label(session, stage.clone().add(0, 2.45, 0), "", 0.75f, false);
        } catch (RuntimeException e) {
            // 버튼을 못 만들어도 마우스 휠과 웅크리기로 쓸 수 있다
            plugin.getLogger().warning("옷장 버튼을 만들지 못했습니다: " + e);
        }
    }

    private void button(WardrobeSession session, WardrobeSession.Action action, Location front, Vector right,
                        double side, double up, String text) {
        Location at = front.clone().add(right.clone().multiply(side)).add(0, up, 0);
        TextDisplay label = label(session, at, plugin.messages().get(text), 0.9f, true);
        boolean[] hidden = {true};
        Interaction hitbox = at.getWorld().spawn(at.clone().add(0, -0.05, 0), Interaction.class, i -> {
            i.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            hidden[0] = WardrobeSession.prepare(i);
            i.setInteractionWidth(0.7f);
            i.setInteractionHeight(0.4f);
            i.setResponsive(true);
        });
        session.spawned.add(hitbox);
        reveal(session.player, hitbox, hidden[0]);
        session.buttons.add(new WardrobeSession.Button(action, hitbox, label));
    }

    private TextDisplay label(WardrobeSession session, Location at, String text, float scale, boolean boxed) {
        boolean[] hidden = {true};
        TextDisplay label = at.getWorld().spawn(at, TextDisplay.class, t -> {
            // 표시를 먼저 붙여서, 아래에서 실패해도 다음에 켤 때 지울 수 있게
            t.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            hidden[0] = WardrobeSession.prepare(t);
            t.setText(text);
            t.setShadowed(true);
            t.setBackgroundColor(boxed ? Color.fromARGB(150, 20, 20, 30) : Color.fromARGB(0, 0, 0, 0));
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
        session.spawned.add(label);
        reveal(session.player, label, hidden[0]);
        return label;
    }

    /** 정해진 옷장 자리에서는 같은 자리에 겹쳐 선 다른 사람을 서로 안 보이게 한다. */
    private void hideOthers(WardrobeSession session) {
        for (WardrobeSession other : sessions.values()) {
            if (other != session && other.origin != null) {
                session.player.hidePlayer(plugin, other.player);
                other.player.hidePlayer(plugin, session.player);
            }
        }
    }

    private void showOthers(WardrobeSession session) {
        for (WardrobeSession other : sessions.values()) {
            if (other != session && other.origin != null && session.player.isOnline()) {
                session.player.showPlayer(plugin, other.player);
                other.player.showPlayer(plugin, session.player);
            }
        }
    }

    // ── 매 틱 ────────────────────────────────────

    private void tick() {
        tick++;
        if (sessions.isEmpty()) {
            return;
        }
        long max = plugin.settings().wardrobeMaxSeconds() * 20L;
        for (WardrobeSession session : new ArrayList<>(sessions.values())) {
            if (!session.player.isOnline() || !session.mannequin.isValid()) {
                end(session, true, null);
                continue;
            }
            if (max > 0 && tick - session.openedAt > max) {
                end(session, true, "wardrobe.timeout");
                continue;
            }
            session.tick(tick);
            if ((tick - session.openedAt) % 40 == 1) {
                actionBar(session.player, plugin.messages().get("wardrobe.action-bar"));
            }
        }
    }

    private void actionBar(Player player, String text) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
        } catch (RuntimeException ignored) {
            // 액션바를 못 보내는 서버 구현이면 넘어간다
        }
    }

    // ── 조작 ─────────────────────────────────────

    private void act(WardrobeSession session, WardrobeSession.Action action) {
        Player player = session.player;
        switch (action) {
            case PREV_ITEM -> session.cycleItem(-1);
            case NEXT_ITEM -> session.cycleItem(1);
            case PREV_CATEGORY -> session.cycleCategory(-1);
            case NEXT_CATEGORY -> session.cycleCategory(1);
            case ROTATE -> session.rotating = !session.rotating;
            case EQUIP -> {
                if (session.equip()) {
                    player.playSound(player.getLocation(), "entity.player.levelup", 0.6f, 1.6f);
                }
            }
            case EXIT -> {
                end(session, true, "wardrobe.closed");
                return;
            }
        }
        if (action != WardrobeSession.Action.EQUIP) {
            player.playSound(player.getLocation(), "ui.button.click", 0.5f, 1.3f);
        }
        session.render();
    }

    /** 마우스 휠: 코스메틱 넘기기 (손에 든 칸은 그대로). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent event) {
        WardrobeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        int delta = Math.floorMod(event.getNewSlot() - event.getPreviousSlot(), 9);
        if (delta != 0) {
            act(session, delta <= 4 ? WardrobeSession.Action.NEXT_ITEM : WardrobeSession.Action.PREV_ITEM);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickEntity(PlayerInteractEntityEvent event) {
        WardrobeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Entity clicked = event.getRightClicked();
        WardrobeSession.Button button = session.button(clicked);
        if (button != null) {
            act(session, button.action());
        } else if (clicked.equals(session.mannequin)) {
            act(session, WardrobeSession.Action.EQUIP);
        }
    }

    /** 갑옷 거치대 등을 정확히 눌렀을 때 따로 오는 이벤트. 동작은 위에서 한 번만 한다. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickEntityAt(PlayerInteractAtEntityEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 옷장에서는 블록이나 아이템을 쓰지 못한다. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        WardrobeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.isSneaking()) {
            end(session, true, "wardrobe.closed");
        }
    }

    /** 제자리에 붙잡아 둔다 (고개는 돌릴 수 있다). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        WardrobeSession session = sessions.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (session == null || to == null || atView(session, to)) {
            return;
        }
        Location back = session.view.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        event.setTo(back);
    }

    /**
     * 다른 곳으로 옮겨지면 옷장을 닫는다 (원래 자리로 되돌리지 않는다). 움직이려 할 때 {@link #onMove} 가 제자리로
     * 돌려놓으면 서버가 그것을 순간이동으로 처리하므로, 옷장 자리로 가는 순간이동은 무시한다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        WardrobeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !session.teleporting && !atView(session, event.getTo())) {
            end(session, false, "wardrobe.closed");
        }
    }

    private static boolean atView(WardrobeSession session, Location to) {
        Location view = session.view;
        return to != null && to.getWorld() == view.getWorld() && Math.abs(to.getX() - view.getX()) < 0.01
                && Math.abs(to.getY() - view.getY()) < 0.01 && Math.abs(to.getZ() - view.getZ()) < 0.01;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            WardrobeSession session = sessions.get(player.getUniqueId());
            if (session != null) {
                end(session, true, "wardrobe.closed");
            }
        }
    }

    /** 마네킹은 부서지지 않는다 (크리에이티브 플레이어는 무적도 무시하므로 따로 막는다). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMannequinDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 그래도 사라지면 입혀 둔 모자 등을 떨어뜨리지 않는다. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMannequinDeath(EntityDeathEvent event) {
        if (isOurs(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (isOurs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        WardrobeSession session = sessions.get(event.getEntity().getUniqueId());
        if (session != null) {
            end(session, false, null);
        }
    }

    /** 나갈 때는 원래 자리로 돌려놓고 나가게 한다 (다시 들어왔을 때 옷장 자리에 갇히지 않게). */
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        WardrobeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            end(session, true, null);
        }
    }
}
