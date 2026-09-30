package io.github.munang77.cosmeticscore.gui;

import java.util.ArrayList;
import java.util.List;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.Settings;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** 첫 화면: 내 착용 현황, 카테고리 버튼, 뽑기, 보기 설정, 모두 해제. */
public final class MainMenu extends Menu {

    public MainMenu(CosmeticsCore plugin, Player viewer) {
        super(plugin, viewer, Settings.MAIN_MENU_SIZE, plugin.messages().get("menu.main-title"));
    }

    @Override
    protected void render() {
        inventory.clear();
        Messages msg = plugin.messages();
        Settings settings = plugin.settings();
        CosmeticManager manager = plugin.manager();

        inventory.setItem(Settings.PROFILE_SLOT, profile());

        for (Category category : Category.values()) {
            if (!settings.isShown(category)) {
                continue;
            }
            String name = msg.category(category);
            List<Cosmetic> all = plugin.registry().of(category);
            Cosmetic equipped = manager.equipped(viewer, category);
            ItemStack button = new ItemBuilder(settings.categoryIcon(category))
                    .name(msg.get("menu.category-button.name", "category", name))
                    .lore(msg.list("menu.category-button.lore",
                            "category", name,
                            "owned", String.valueOf(manager.ownedCount(viewer, all)),
                            "total", String.valueOf(all.size()),
                            "equipped", equipped == null ? msg.get("menu.none") : equipped.name()))
                    .glow(equipped != null)
                    .hideTooltipExtras()
                    .build();
            inventory.setItem(settings.categorySlot(category), button);
        }

        if (settings.crateEnabled()) {
            double price = settings.cratePrice();
            int keys = manager.keys(viewer);
            inventory.setItem(Settings.CRATE_SLOT, new ItemBuilder(Material.ENDER_CHEST)
                    .name(msg.get("menu.crate.name"))
                    .lore(msg.list("menu.crate.lore",
                            "keys", String.valueOf(keys),
                            "price", price > 0 ? plugin.economy().format(price) : msg.get("menu.crate.no-price"),
                            "left", String.valueOf(plugin.crates().candidates(viewer).size())))
                    .glow(keys > 0)
                    .hideTooltipExtras()
                    .build());
        }

        if (settings.wardrobeEnabled()) {
            inventory.setItem(Settings.WARDROBE_SLOT, wardrobeButton());
        }

        boolean showOthers = manager.seesOthers(viewer);
        inventory.setItem(Settings.TOGGLE_SLOT, new ItemBuilder(showOthers ? Material.ENDER_EYE : Material.ENDER_PEARL)
                .name(msg.get(showOthers ? "menu.toggle.name-on" : "menu.toggle.name-off"))
                .lore(msg.list("menu.toggle.lore"))
                .hideTooltipExtras()
                .build());
        inventory.setItem(Settings.UNEQUIP_ALL_SLOT, new ItemBuilder(Material.BARRIER)
                .name(msg.get("menu.unequip-all.name"))
                .lore(msg.list("menu.unequip-all.lore"))
                .build());
        fill();
    }

    private ItemStack profile() {
        Messages msg = plugin.messages();
        List<String> lore = new ArrayList<>();
        for (Category category : Category.values()) {
            if (!plugin.settings().isShown(category)) {
                continue;
            }
            Cosmetic equipped = plugin.manager().equipped(viewer, category);
            lore.add(msg.get("menu.profile.line",
                    "category", msg.category(category),
                    "equipped", equipped == null ? msg.get("menu.none") : equipped.name()));
        }
        int owned = plugin.manager().ownedCount(viewer, plugin.registry().all());
        lore.add("");
        lore.add(msg.get("menu.profile.owned", "owned", String.valueOf(owned),
                "total", String.valueOf(plugin.registry().all().size())));
        ItemBuilder head = new ItemBuilder(Material.PLAYER_HEAD)
                .name(msg.get("menu.profile.name", "player", viewer.getName()))
                .lore(lore);
        if (head.meta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(viewer);
        }
        return head.build();
    }

    @Override
    protected void click(int slot, ClickType click) {
        Settings settings = plugin.settings();
        for (Category category : Category.values()) {
            if (settings.isShown(category) && settings.categorySlot(category) == slot) {
                sound("ui.button.click", 1.2f);
                openLater(new CategoryMenu(plugin, viewer, category, 0));
                return;
            }
        }
        if (slot == Settings.WARDROBE_SLOT && settings.wardrobeEnabled()) {
            closeLater(() -> plugin.wardrobe().open(viewer, null));
        } else if (slot == Settings.CRATE_SLOT && settings.crateEnabled()) {
            CrateMenu.openCrate(plugin, viewer);
        } else if (slot == Settings.TOGGLE_SLOT) {
            if (plugin.manager().toggleShowOthers(viewer)) {
                sound("ui.button.click", 1.0f);
                render();
            }
        } else if (slot == Settings.UNEQUIP_ALL_SLOT) {
            plugin.manager().unequipAll(viewer);
            sound("entity.item.pickup", 0.8f);
            render();
        }
    }
}
