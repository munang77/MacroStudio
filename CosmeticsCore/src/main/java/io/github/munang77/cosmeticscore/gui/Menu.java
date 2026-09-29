package io.github.munang77.cosmeticscore.gui;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** 코스메틱 메뉴의 공통 뼈대. 인벤토리 주인(holder)으로 메뉴인지 알아본다. */
public abstract class Menu implements InventoryHolder {

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

    public final void open() {
        render();
        viewer.openInventory(inventory);
    }

    /** 칸을 (다시) 채운다. */
    protected abstract void render();

    /** 메뉴 칸을 눌렀을 때. 아이템 이동은 이미 막혀 있다. */
    protected abstract void click(int slot, ClickType click);

    /** 다른 메뉴로 넘어간다. 클릭 이벤트 도중에 창을 바꾸면 안 되므로 다음 틱에 연다. */
    protected void openLater(Menu next) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                next.open();
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

    protected void fill() {
        Material filler = plugin.settings().filler();
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

    protected void sound(String key, float pitch) {
        viewer.playSound(viewer.getLocation(), key, 0.6f, pitch);
    }
}
