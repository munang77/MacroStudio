package io.github.munang77.cosmeticscore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.munang77.cosmeticscore.api.CosmeticEquipEvent;
import io.github.munang77.cosmeticscore.api.CosmeticUnequipEvent;
import io.github.munang77.cosmeticscore.cosmetic.ArrowTrailCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.cosmetic.CosmeticRegistry;
import io.github.munang77.cosmeticscore.cosmetic.KillEffectCosmetic;
import io.github.munang77.cosmeticscore.data.PlayerData;
import io.github.munang77.cosmeticscore.hat.HatService;
import io.github.munang77.cosmeticscore.hook.EconomyHook;
import io.github.munang77.cosmeticscore.util.Facing;
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
    /** {@link #viewers} 가 돌려 쓰는 위치 객체. */
    private final Location scratch = new Location(null, 0, 0, 0);

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

    /** 메뉴와 옷장에서 보여 주는 상태. */
    public enum Status {
        EQUIPPED, OWNED, BUYABLE, LOCKED
    }

    /** 착용 중 / 가짐 / 살 수 있음 / 잠김. */
    public Status status(Player player, Cosmetic cosmetic) {
        if (isEquipped(player, cosmetic)) {
            return Status.EQUIPPED;
        }
        if (owns(player, cosmetic)) {
            return Status.OWNED;
        }
        return cosmetic.price() > 0 ? Status.BUYABLE : Status.LOCKED;
    }

    public int ownedCount(Player player, Collection<Cosmetic> cosmetics) {
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
        return data == null ? null : stored(data, category);
    }

    /** 저장된 착용 아이디의 코스메틱. 설정에서 지워졌거나 다른 카테고리로 바뀌었으면 {@code null}. */
    private Cosmetic stored(PlayerData data, Category category) {
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
        if (plugin.settings().isDisabled(player.getWorld()) && cosmetic.category().isWorldBound()) {
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
            if (room.failure() != null) {
                msg.send(player, room.failure());
                return false;
            }
            helmetMoved = room == HatService.Room.MOVED;
        }

        // 미리보기 말고 실제로 입고 있던 것
        Cosmetic previous = stored(data, cosmetic.category());
        endPreview(player, cosmetic.category());
        data.setEquipped(cosmetic.category(), cosmetic.id());
        if (previous != null) {
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

    /**
     * 한 카테고리를 해제한다.
     *
     * @param notify 플레이어에게 알릴지
     * @return 해제할 게 있었으면 {@code true}
     */
    public boolean unequip(Player player, Category category, boolean notify) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        Messages msg = plugin.messages();
        if (data == null || !clear(player, data, category)) {
            if (notify) {
                msg.send(player, "nothing-equipped", "category", msg.category(category));
            }
            return false;
        }
        applyVisuals(player, category);
        plugin.store().save(data);
        if (notify) {
            msg.send(player, "unequipped", "category", msg.category(category));
        }
        return true;
    }

    /** 모두 해제한다. 저장과 모습 맞추기는 한 번만 한다. */
    public void unequipAll(Player player) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (data != null) {
            boolean changed = false;
            for (Category category : Category.values()) {
                changed |= clear(player, data, category);
            }
            if (changed) {
                applyVisuals(player);
                plugin.store().save(data);
            }
        }
        plugin.messages().send(player, "unequipped-all");
    }

    /** 착용 칸을 비우고 해제 이벤트를 낸다. @return 비울 게 있었으면 {@code true} */
    private boolean clear(Player player, PlayerData data, Category category) {
        if (data.equipped(category) == null) {
            return false;
        }
        Cosmetic previous = stored(data, category);
        data.setEquipped(category, null);
        Bukkit.getPluginManager().callEvent(new CosmeticUnequipEvent(player, previous));
        return true;
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
            resync(player);
            return true;
        }
        plugin.store().loadAsync(player.getUniqueId(), player.getName()).thenAccept(data ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        resync(player);
                    }
                }));
        return false;
    }

    /** 착용 정보를 다시 확인하고(권한·설정 변경) 모습을 맞춘다. 접속, 리로드, 회수 뒤에 부른다. */
    public void resync(Player player) {
        validate(player);
        applyVisuals(player);
    }

    /**
     * "다른 사람 효과 보기"를 뒤집는다.
     *
     * @return 데이터가 아직 없으면 {@code false}
     */
    public boolean toggleShowOthers(Player player) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        if (data == null) {
            return false;
        }
        data.setShowOthers(!data.showOthers());
        plugin.store().save(data);
        plugin.displays().syncVisibility();
        plugin.messages().send(player, data.showOthers() ? "toggle-on" : "toggle-off");
        return true;
    }

    /** 뽑기 열쇠 수. */
    public int keys(Player player) {
        PlayerData data = plugin.store().get(player.getUniqueId());
        return data == null ? 0 : data.keys();
    }

    /** 나갈 때. */
    public void handleQuit(Player player) {
        // lastPreview 는 남겨 둔다: 다시 들어와서 대기 시간을 초기화하지 못하게
        previews.remove(player.getUniqueId());
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
        if (settings.isDisabled(player.getWorld()) && category.isWorldBound()) {
            msg.send(player, "disabled-world");
            return;
        }
        if (category == Category.HAT && !plugin.hats().canPreview(player)) {
            msg.send(player, "preview-helmet");
            return;
        }
        if (!owns(player, cosmetic)) {
            Long last = lastPreview.get(player.getUniqueId());
            long cooldown = settings.previewCooldownSeconds() * 20L;
            if (last != null && clock - last < cooldown) {
                long left = (cooldown - (clock - last) + 19) / 20;
                msg.send(player, "preview-cooldown", "seconds", String.valueOf(Math.max(1, left)));
                return;
            }
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
        if (cosmetic instanceof ArrowTrailCosmetic trail) {
            plugin.effects().previewTrail(player, trail);
        } else if (cosmetic instanceof KillEffectCosmetic effect) {
            Location base = player.getLocation();
            plugin.effects().play(player, effect, base.add(Facing.forward(base.getYaw()).multiply(3)));
        } else {
            // 칭호, 채팅 색, 킬 메시지, 입장 효과: 글자는 실제로 쓰는 쪽에서 똑같이 만든다
            plugin.chat().preview(player, cosmetic);
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
        long cooldown = plugin.settings().previewCooldownSeconds() * 20L;
        lastPreview.values().removeIf(at -> now - at >= cooldown);
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
            // 매 틱 불리므로 위치 객체를 새로 만들지 않고 하나를 돌려 쓴다 (메인 스레드 전용)
            if (viewer.getLocation(scratch).distanceSquared(at) > rangeSq) {
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
}
