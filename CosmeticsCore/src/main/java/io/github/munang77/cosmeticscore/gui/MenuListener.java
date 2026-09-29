package io.github.munang77.cosmeticscore.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** 메뉴 안에서는 아이템을 못 옮기게 하고, 누른 칸을 메뉴에 넘긴다. */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        int raw = event.getRawSlot();
        // 더블클릭은 왼쪽 클릭 두 번 뒤에 한 번 더 오므로 무시한다 (구매 확인이 한 번에 넘어가지 않게)
        if (raw < 0 || raw >= top.getSize() || event.getClick() == ClickType.DOUBLE_CLICK
                || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        menu.click(raw, event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
