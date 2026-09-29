package io.github.munang77.cosmeticscore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.munang77.cosmeticscore.api.CosmeticEquipEvent;
import io.github.munang77.cosmeticscore.api.CosmeticUnequipEvent;
import io.github.munang77.cosmeticscore.cosmetic.ArrowTrailCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.ChatColorCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.cosmetic.CosmeticRegistry;
import io.github.munang77.cosmeticscore.cosmetic.JoinEffectCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.KillEffectCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.KillMessageCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.TitleCosmetic;
import io.github.munang77.cosmeticscore.data.PlayerData;
import io.github.munang77.cosmeticscore.hat.HatService;
import io.github.munang77.cosmeticscore.hook.EconomyHook;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

/**
 * 코스메틱 보유 확인, 착용, 해제, 구매, 미리보기. 다른 플러그인도 {@link CosmeticsCore#manager()} 로 쓸 수 있다.
 */
public final class CosmeticManager {

    public static final String ALL_PERMISSION = CosmeticRegistry.PERMISSION_PREFIX + "all";

    /** 잠깐 입어 보는 중인 코스메틱. {@code expiresAt} 은 {@link #clock} 기준. */
    private record Preview(Cosmetic cosmetic, long expiresAt) {
    }

    private final CosmeticsCore plugin;
    private final Map<UUID, Map<Category, Preview>> previews = new ConcurrentHashMap<>();
    /** 미리보기 시계 (서버 틱). 서버가 느려지면 미리보기도 그만큼 길어진다. */
    private volatile long clock;
    /** 잠긴 코스메틱을 마지막으로 미리 본 시각 ({@link #clock} 기준). */
    private final Map<UUID, Long> lastPreview = new ConcurrentHashMap<>();

    CosmeticManager(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    // ── 보유 ─────────────────────────────────────

    /** 무료이거나, 권한이 있거나, 지급/구매했으면 쓸 수 있다. */
    public boolean owns(Player player, Cosmetic cosmetic) {
        if (cosmetic.free()) {
            return true;
        }
        if (player.hasPermission(ALL_PERMISSION) || player.hasPermission(cosmetic.permission())) {
            return true;
        }
        PlayerData data = plugin.store().get(player.getUniqueId());
        return data != null && data.hasUnlocked(cosmetic.id());
    }

    public int ownedCount(Player player, List<Cosmetic> cosmetics) {
        int owned = 0;
        for (Cosmetic c : cosmetics) {
            if (owns(player, c)) {
                owned++;
            }
        }
        return owned;
    }

    // ── 착용 상태 ────────────────────────────────

    /**
     * 지금 보여 줄 코스메틱 (미리보기 중이면 미리보기). 없거나 설정에서 지워졌으면 {@code null}.
     * 비동기 스레드(채팅)에서 불러도 된다.
     */
    public Cosmetic equipped(Player player, Category category) {
        Map<Category, Preview> mine = previews.get(player.getUniqueId());
        if (mine != null) {
            Preview preview = mine.get(category);
            if (preview != null && preview.expiresAt > clock) {
                return preview.cosmetic;
            }
        }
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (data == null) {
            return null;
        }
        Cosmetic cosmetic = plugin.registry().get(data.equipped(category));
        return cosmetic != null && cosmetic.category() == category ? cosmetic : null;
    }

    public <T extends Cosmetic> T equipped(Player player, Category category, Class<T> type) {
        Cosmetic cosmetic = equipped(player, category);
        return type.isInstance(cosmetic) ? type.cast(cosmetic) : null;
    }

    /** 실제로 착용해 둔 것인지 (미리보기는 빼고). */
    public boolean isEquipped(Player player, Cosmetic cosmetic) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        return data != null && cosmetic.id().equals(data.equipped(cosmetic.category()));
    }

    // ── 착용 / 해제 ──────────────────────────────

    /**
     * 착용한다. 결과는 플레이어에게 메시지로 알린다.
     *
     * @return 착용했으면 {@code true}
     */
    public boolean equip(Player player, Cosmetic cosmetic) {
        Messages msg = plugin.messages();
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (data == null) {
            return false;
        }
        if (!owns(player, cosmetic)) {
            msg.send(player, "locked", "name", cosmetic.name());
            return false;
        }
        if (plugin.settings().isDisabled(player.getWorld()) && worldBound(cosmetic.category())) {
            msg.send(player, "disabled-world");
            return false;
        }
        if (cosmetic.id().equals(data.equipped(cosmetic.category()))) {
            msg.send(player, "equipped", "name", cosmetic.name());
            return true;
        }
        CosmeticEquipEvent event = new CosmeticEquipEvent(player, cosmetic);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            msg.send(player, "cancelled");
            return false;
        }

        boolean helmetMoved = false;
        if (cosmetic.category() == Category.HAT) {
            HatService.Room room = plugin.hats().makeRoom(player);
            switch (room) {
                case BLOCKED -> {
                    msg.send(player, "helmet-blocked");
                    return false;
                }
                case INVENTORY_FULL -> {
                    msg.send(player, "helmet-inventory-full");
                    return false;
                }
                case CURSED -> {
                    msg.send(player, "helmet-cursed");
                    return false;
                }
                case MOVED -> helmetMoved = true;
                case FREE -> {
                }
            }
        }

        // 미리보기 말고 실제로 입고 있던 것
        Cosmetic previous = plugin.registry().get(data.equipped(cosmetic.category()));
        endPreview(player, cosmetic.category());
        data.setEquipped(cosmetic.category(), cosmetic.id());
        if (previous != null && previous.category() == cosmetic.category()) {
            Bukkit.getPluginManager().callEvent(new CosmeticUnequipEvent(player, previous));
        }
        applyVisuals(player, cosmetic.category());
        plugin.store().save(data);
        msg.send(player, "equipped", "name", cosmetic.name());
        if (helmetMoved) {
            msg.send(player, "helmet-moved");
        }
        return true;
    }

    /** 꺼진 월드에서 못 쓰는 카테고리인지 (말로만 보이는 칭호/채팅 색/메시지는 어디서나 된다). */
    private static boolean worldBound(Category category) {
        return category.isDisplay() || category == Category.HAT || category == Category.PARTICLE
                || category == Category.ARROW_TRAIL || category == Category.KILL_EFFECT;
    }

    /**
     * 한 카테고리를 해제한다.
     *
     * @param notify 플레이어에게 알릴지
     * @return 해제할 게 있었으면 {@code true}
     */
    public boolean unequip(Player player, Category category, boolean notify) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        Messages msg = plugin.messages();
        String id = data == null ? null : data.equipped(category);
        if (id == null) {
            if (notify) {
                msg.send(player, "nothing-equipped", "category", msg.category(category));
            }
            return false;
        }
        Cosmetic previous = plugin.registry().get(id);
        data.setEquipped(category, null);
        applyVisuals(player, category);
        plugin.store().save(data);
        Bukkit.getPluginManager().callEvent(new CosmeticUnequipEvent(player,
                previous != null && previous.category() == category ? previous : null));
        if (notify) {
            msg.send(player, "unequipped", "category", msg.category(category));
        }
        return true;
    }

    public void unequipAll(Player player) {
        for (Category category : Category.values()) {
            unequip(player, category, false);
        }
        plugin.messages().send(player, "unequipped-all");
    }

    /**
     * 더 이상 쓸 수 없는 코스메틱(권한이 사라졌거나 다른 카테고리로 바뀐 아이디)을 벗긴다.
     * 설정에서 잠시 지워진 아이디는 설정을 고치면 돌아오도록 그대로 둔다.
     */
    public void validate(Player player) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (data == null) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<Category, String> entry : data.equippedView().entrySet()) {
            Cosmetic cosmetic = plugin.registry().get(entry.getValue());
            if (cosmetic == null) {
                continue;
            }
            if (cosmetic.category() != entry.getKey() || !owns(player, cosmetic)) {
                data.setEquipped(entry.getKey(), null);
                changed = true;
                Bukkit.getPluginManager().callEvent(new CosmeticUnequipEvent(player,
                        cosmetic.category() == entry.getKey() ? cosmetic : null));
            }
        }
        if (changed) {
            plugin.store().save(data);
        }
    }

    /**
     * 접속했을 때 (또는 플러그인이 켜질 때 이미 접속해 있던 플레이어). 로그인 때 미리 읽어 둔 데이터를 쓰고,
     * 없으면 백그라운드에서 읽은 뒤 마무리한다. 어느 쪽이든 메인 스레드는 기다리지 않는다.
     *
     * @return 데이터가 바로 준비됐으면 {@code true}
     */
    public boolean handleJoin(Player player) {
        if (plugin.store().activate(player.getUniqueId(), player.getName()) != null) {
            finishJoin(player);
            return true;
        }
        plugin.store().loadAsync(player.getUniqueId(), player.getName()).thenAccept(data ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        finishJoin(player);
                    }
                }));
        return false;
    }

    private void finishJoin(Player player) {
        validate(player);
        applyVisuals(player);
    }

    /** 나갈 때. */
    public void handleQuit(Player player) {
        previews.remove(player.getUniqueId());
        lastPreview.remove(player.getUniqueId());
    }

    /** 모자, 탭 이름, 몸에 붙는 장식을 착용 정보에 맞춘다. */
    public void applyVisuals(Player player) {
        plugin.hats().refresh(player);
        plugin.chat().applyTab(player);
        plugin.displays().refresh(player);
    }

    private void applyVisuals(Player player, Category category) {
        if (category == Category.HAT) {
            plugin.hats().refresh(player);
        } else if (category == Category.TITLE) {
            plugin.chat().applyTab(player);
        } else if (category.isDisplay()) {
            plugin.displays().refresh(player);
        }
    }

    // ── 구매 ─────────────────────────────────────

    /**
     * 가격을 내고 산다 (Vault 경제 플러그인 필요).
     *
     * @return 샀으면 {@code true}
     */
    public boolean purchase(Player player, Cosmetic cosmetic) {
        Messages msg = plugin.messages();
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (data == null || data.readOnly()) {
            return false;
        }
        if (owns(player, cosmetic)) {
            msg.send(player, "already-have", "name", cosmetic.name());
            return false;
        }
        if (cosmetic.price() <= 0) {
            msg.send(player, "not-for-sale", "name", cosmetic.name());
            return false;
        }
        EconomyHook economy = plugin.economy();
        if (!economy.available()) {
            msg.send(player, "economy-missing");
            return false;
        }
        String price = economy.format(cosmetic.price());
        if (!economy.has(player, cosmetic.price())) {
            msg.send(player, "not-enough-money", "price", price, "balance", economy.format(economy.balance(player)));
            return false;
        }
        if (!economy.withdraw(player, cosmetic.price())) {
            msg.send(player, "purchase-failed");
            return false;
        }
        data.unlock(cosmetic.id());
        plugin.store().save(data);
        msg.send(player, "purchased", "name", cosmetic.name(), "price", price);
        return true;
    }

    // ── 미리보기 ─────────────────────────────────

    /**
     * 잠긴 코스메틱도 잠깐 체험한다. 몸에 보이는 종류는 몇 초 동안 입혀 주고, 나머지는 본인에게만 예시를
     * 보여 준다. 미리보기를 계속 걸어 공짜로 쓰지 못하도록 사이에 대기 시간을 둔다.
     */
    public void preview(Player player, Cosmetic cosmetic) {
        Messages msg = plugin.messages();
        Settings settings = plugin.settings();
        Category category = cosmetic.category();
        if (!settings.previewEnabled()) {
            msg.send(player, "preview-disabled");
            return;
        }
        if (settings.isDisabled(player.getWorld()) && worldBound(category)) {
            msg.send(player, "disabled-world");
            return;
        }
        if (category == Category.HAT && !plugin.hats().canPreview(player)) {
            msg.send(player, "preview-helmet");
            return;
        }
        Long last = lastPreview.get(player.getUniqueId());
        long cooldown = settings.previewCooldownSeconds() * 20L;
        if (last != null && clock - last < cooldown && !owns(player, cosmetic)) {
            long left = (cooldown - (clock - last) + 19) / 20;
            msg.send(player, "preview-cooldown", "seconds", String.valueOf(Math.max(1, left)));
            return;
        }
        if (!owns(player, cosmetic)) {
            lastPreview.put(player.getUniqueId(), clock);
        }

        if (category.isTimedPreview()) {
            int seconds = settings.previewSeconds();
            previews.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                    .put(category, new Preview(cosmetic, clock + seconds * 20L));
            applyVisuals(player, category);
            msg.send(player, "preview-start", "name", cosmetic.name(), "seconds", String.valueOf(seconds));
            return;
        }
        Location base = player.getLocation();
        double yaw = Math.toRadians(base.getYaw());
        Location front = base.clone().add(-Math.sin(yaw) * 3, 0, Math.cos(yaw) * 3);
        String sample = Text.plain(msg.get("preview-chat-sample"));
        switch (category) {
            case ARROW_TRAIL -> plugin.effects().previewTrail(player, (ArrowTrailCosmetic) cosmetic);
            case KILL_EFFECT -> plugin.effects().play(player, (KillEffectCosmetic) cosmetic, front);
            case KILL_MESSAGE -> player.sendMessage(Text.replace(((KillMessageCosmetic) cosmetic).message(),
                    "killer", player.getName(), "victim", msg.get("preview-victim")));
            case JOIN_EFFECT -> plugin.chat().previewJoin(player, (JoinEffectCosmetic) cosmetic);
            case TITLE -> player.sendMessage(msg.get("preview-chat-format",
                    "title", ((TitleCosmetic) cosmetic).title(), "player", player.getName(), "message", sample));
            case CHAT_COLOR -> player.sendMessage(msg.get("preview-chat-format",
                    "title", plugin.chat().title(player), "player", player.getName(),
                    "message", ((ChatColorCosmetic) cosmetic).apply(sample)));
            default -> {
            }
        }
        msg.send(player, "preview-once", "name", cosmetic.name());
    }

    private void endPreview(Player player, Category category) {
        Map<Category, Preview> mine = previews.get(player.getUniqueId());
        if (mine != null) {
            mine.remove(category);
        }
    }

    /** 1초(20틱)마다: 끝난 미리보기를 걷고 원래 모습으로 돌린다. */
    void tickPreviews() {
        long now = clock += 20;
        for (Map.Entry<UUID, Map<Category, Preview>> entry : previews.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            List<Category> ended = new ArrayList<>();
            Iterator<Map.Entry<Category, Preview>> it = entry.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Category, Preview> p = it.next();
                if (p.getValue().expiresAt <= now) {
                    it.remove();
                    ended.add(p.getKey());
                }
            }
            if (player == null) {
                continue;
            }
            for (Category category : ended) {
                applyVisuals(player, category);
                plugin.messages().send(player, "preview-end");
            }
        }
        previews.values().removeIf(Map::isEmpty);
        if (now % 1200 == 0) {
            plugin.store().sweepStaleLogins();
        }
    }

    /** 모든 미리보기를 끝낸다 (플러그인 종료). */
    void clearPreviews() {
        previews.clear();
    }

    // ── 보이기 ───────────────────────────────────

    /** 효과를 숨겨야 하는 상태인지 (꺼진 월드, 관전자, 투명화, 바니시). */
    public boolean isHidden(Player player) {
        Settings settings = plugin.settings();
        if (settings.isDisabled(player.getWorld())) {
            return true;
        }
        if (!settings.hideWhenInvisible()) {
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.isInvisible()) {
            return true;
        }
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    /** {@code viewer} 가 다른 사람 효과를 보는지. */
    public boolean seesOthers(Player viewer) {
        PlayerData data = plugin.store().get(viewer.getUniqueId());
        return data == null || data.showOthers();
    }

    /**
     * {@code at} 근처에서 효과를 볼 플레이어. 본인은 항상 보고, 다른 사람은 "다른 사람 효과 보기"를 켜 두었고
     * {@code source} 를 볼 수 있을 때만 본다.
     */
    public List<Player> viewers(Player source, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return List.of();
        }
        double range = plugin.settings().viewDistance();
        double rangeSq = range * range;
        List<Player> out = new ArrayList<>();
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(at) > rangeSq) {
                continue;
            }
            if (viewer.equals(source)) {
                out.add(viewer);
                continue;
            }
            if (!seesOthers(viewer) || (source != null && !viewer.canSee(source))) {
                continue;
            }
            out.add(viewer);
        }
        return out;
    }

    /** 카테고리별 착용 이름 (메뉴/플레이스홀더용). */
    public Map<Category, Cosmetic> equippedAll(Player player) {
        Map<Category, Cosmetic> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            Cosmetic cosmetic = equipped(player, c);
            if (cosmetic != null) {
                out.put(c, cosmetic);
            }
        }
        return out;
    }
}
