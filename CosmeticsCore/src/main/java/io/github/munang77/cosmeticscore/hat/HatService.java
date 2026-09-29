package io.github.munang77.cosmeticscore.hat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.HatCosmetic;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 모자 코스메틱. 표시가 붙은 장식 아이템을 머리 칸에 씌우고, 그 아이템이 머리 칸 밖으로
 * 나가지 못하게 막는다 (옮기기, 버리기, 손 바꾸기, 죽을 때 떨어뜨리기).
 */
public final class HatService implements Listener {

    /** {@link #makeRoom} 결과. */
    public enum Room {
        /** 머리 칸이 비어 있거나 이미 모자다. */
        FREE,
        /** 쓰고 있던 투구를 인벤토리로 옮겼다. */
        MOVED,
        /** 투구를 쓰고 있고, 옮기지 않도록 설정돼 있다. */
        BLOCKED,
        /** 투구를 옮길 빈칸이 없다. */
        INVENTORY_FULL,
        /** 귀속 저주가 걸린 투구라 벗길 수 없다. */
        CURSED
    }

    private static final long WARN_COOLDOWN_MS = 3000;

    private final CosmeticsCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();
    private BukkitTask task;

    public HatService(CosmeticsCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hat");
    }

    /** 5초마다 모자가 사라지지 않았는지 (/clear 등) 확인한다. */
    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                refresh(p);
            }
        }, 100L, 100L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isHat(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** 방어력 없고, 부서지지 않고, 표시가 붙은 모자 아이템. */
    public ItemStack create(HatCosmetic hat) {
        ItemStack item = hat.item().builder(plugin.getLogger())
                .name(hat.name())
                .lore(hat.lore())
                .unbreakable()
                .hideTooltipExtras()
                .build();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, hat.id());
        item.setItemMeta(meta);
        return item;
    }

    /** 미리보기로 모자를 씌울 수 있는지 (진짜 투구를 쓰고 있으면 안 된다). */
    public boolean canPreview(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        return helmet == null || helmet.getType().isAir() || isHat(helmet);
    }

    /** 모자를 쓸 수 있게 머리 칸을 비운다. */
    public Room makeRoom(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack helmet = inv.getHelmet();
        if (helmet == null || helmet.getType().isAir() || isHat(helmet)) {
            return Room.FREE;
        }
        if (helmet.containsEnchantment(Enchantment.BINDING_CURSE)) {
            return Room.CURSED;
        }
        if (!plugin.settings().moveHelmetToInventory()) {
            return Room.BLOCKED;
        }
        int empty = inv.firstEmpty();
        if (empty < 0) {
            return Room.INVENTORY_FULL;
        }
        inv.setItem(empty, helmet);
        inv.setHelmet(null);
        return Room.MOVED;
    }

    /**
     * 머리 칸을 착용 정보에 맞춘다. 진짜 투구를 쓰고 있으면 건드리지 않고, 머리 칸이 비면 다시 씌운다.
     * 머리 칸 밖으로 새어 나간 모자 아이템도 지운다.
     */
    public void refresh(Player player) {
        removeStrays(player);
        // 투명화·바니시 중에 모자만 떠 있으면 위치가 드러나므로 숨긴다 (꺼진 월드도 여기서 걸린다)
        HatCosmetic want = plugin.manager().isHidden(player)
                ? null : plugin.manager().equipped(player, Category.HAT, HatCosmetic.class);
        PlayerInventory inv = player.getInventory();
        ItemStack helmet = inv.getHelmet();
        boolean wearingHat = isHat(helmet);
        if (want == null) {
            if (wearingHat) {
                inv.setHelmet(null);
            }
            return;
        }
        if (wearingHat || helmet == null || helmet.getType().isAir()) {
            ItemStack hat = create(want);
            if (!hat.equals(helmet)) {
                inv.setHelmet(hat);
            }
        }
    }

    /** 모자를 벗긴다 (접속 종료, 플러그인 종료). 착용 정보는 그대로 둔다. */
    public void strip(Player player) {
        PlayerInventory inv = player.getInventory();
        if (isHat(inv.getHelmet())) {
            inv.setHelmet(null);
        }
        removeStrays(player);
        lastWarning.remove(player.getUniqueId());
    }

    private void removeStrays(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (isHat(storage[i])) {
                inv.setItem(i, null);
            }
        }
        if (isHat(inv.getItemInOffHand())) {
            inv.setItemInOffHand(null);
        }
        if (isHat(inv.getChestplate())) {
            inv.setChestplate(null);
        }
        if (isHat(inv.getLeggings())) {
            inv.setLeggings(null);
        }
        if (isHat(inv.getBoots())) {
            inv.setBoots(null);
        }
        if (isHat(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private void refreshLater(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refresh(player);
            }
        });
    }

    private void warn(Player player, String path) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last > WARN_COOLDOWN_MS) {
            lastWarning.put(player.getUniqueId(), now);
            plugin.messages().send(player, path);
        }
    }

    // ── 보호 ─────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean touches = isHat(event.getCurrentItem()) || isHat(event.getCursor());
        if (!touches && event.getHotbarButton() >= 0) {
            touches = isHat(player.getInventory().getItem(event.getHotbarButton()));
        }
        if (!touches && event.getClick() == ClickType.SWAP_OFFHAND) {
            touches = isHat(player.getInventory().getItemInOffHand());
        }
        if (touches) {
            event.setCancelled(true);
            warn(player, "hat-protected");
            refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (isHat(event.getOldCursor())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                refreshLater(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isHat(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
            refreshLater(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isHat(event.getMainHandItem()) || isHat(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    /** 투구를 들고 우클릭하면 머리 칸과 바뀌는 동작을 막는다. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type.isBlock() || type.getEquipmentSlot() != EquipmentSlot.HEAD) {
            return;
        }
        Player player = event.getPlayer();
        if (isHat(player.getInventory().getHelmet())) {
            event.setUseItemInHand(Event.Result.DENY);
            warn(player, "hat-blocks-helmet");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isHat);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    /** 투명화 물약을 마시거나 풀리면 다음 틱에 모자를 숨기거나 되돌린다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player) {
            refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        refreshLater(event.getPlayer());
    }
}
