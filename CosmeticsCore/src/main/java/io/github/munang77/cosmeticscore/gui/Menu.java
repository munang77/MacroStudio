package io.github.munang77.cosmeticscore.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * 코스메틱 메뉴의 공통 뼈대. 플레이어마다 열어 둔 메뉴를 기억해서 알아본다. 상자 같은 블록 인벤토리의
 * {@code getHolder()} 는 블록 상태를 통째로 복사하므로, 서버의 모든 클릭에서 그걸 부르지 않기 위해서다.
 */
public abstract class Menu implements InventoryHolder {

    /** 플레이어별로 지금 열려 있는 메뉴 (메인 스레드 전용). */
    private static final Map<UUID, Menu> OPEN = new HashMap<>();

    protected final CosmeticsCore plugin;
    protected final Player viewer;
    protected final Inventory inventory;

    protected Menu(CosmeticsCore plugin, Player viewer, int size, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** {@code who} 가 보고 있는 {@code top} 인벤토리가 코스메틱 메뉴면 그 메뉴. */
    public static Menu of(HumanEntity who, Inventory top) {
        Menu menu = OPEN.get(who.getUniqueId());
        return menu != null && menu.inventory.equals(top) ? menu : null;
    }

    /** 창이 닫혔을 때. */
    static void closed(HumanEntity who, Inventory inventory) {
        Menu menu = OPEN.get(who.getUniqueId());
        if (menu != null && menu.inventory.equals(inventory)) {
            OPEN.remove(who.getUniqueId());
        }
    }

    static void forget(HumanEntity who) {
        OPEN.remove(who.getUniqueId());
    }

    public final void open() {
        render();
        viewer.openInventory(inventory);
        OPEN.put(viewer.getUniqueId(), this);
    }

    /** 칸을 (다시) 채운다. */
    protected abstract void render();

    /** 메뉴 칸을 눌렀을 때. 아이템 이동은 이미 막혀 있다. */
    protected abstract void click(int slot, ClickType click);

    /** 다른 메뉴로 넘어간다. 클릭 이벤트 도중에 창을 바꾸면 안 되므로 다음 틱에 연다. */
    protected void openLater(Menu next) {
        next.openNextTick(() -> { });
    }

    /** 다음 틱에 이 메뉴를 열고, 열린 뒤 {@code afterOpen} 을 한다. */
    protected void openNextTick(Runnable afterOpen) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                open();
                afterOpen.run();
            }
        });
    }

    /** 창을 닫고 나서 할 일. 클릭 이벤트 도중에 창을 닫으면 안 되므로 다음 틱에 한다. */
    protected void closeLater(Runnable after) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                viewer.closeInventory();
                after.run();
            }
        });
    }

    /** 빈칸을 설정의 채움 아이템으로 채운다. */
    protected void fill() {
        fill(plugin.settings().filler());
    }

    /** 빈칸을 {@code filler} 로 채운다 ({@code null} 이면 그대로 둔다). */
    protected void fill(Material filler) {
        if (filler == null) {
            return;
        }
        ItemStack pane = new ItemBuilder(filler).name(" ").hideTooltipExtras().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, pane);
            }
        }
    }

    /** 옷장 열기 버튼. */
    protected ItemStack wardrobeButton() {
        return new ItemBuilder(Material.ARMOR_STAND)
                .name(plugin.messages().get("menu.wardrobe.name"))
                .lore(plugin.messages().list("menu.wardrobe.lore"))
                .hideTooltipExtras()
                .build();
    }

    /** "등급: 전설" 설명 줄. */
    protected String rarityLine(Cosmetic cosmetic) {
        return plugin.messages().get("menu.item.rarity", "rarity", plugin.settings().rarity(cosmetic.rarity()).name());
    }

    protected void sound(String key, float pitch) {
        viewer.playSound(viewer.getLocation(), key, 0.6f, pitch);
    }
}
