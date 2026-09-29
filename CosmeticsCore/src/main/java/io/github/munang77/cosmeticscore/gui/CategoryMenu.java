package io.github.munang77.cosmeticscore.gui;

import java.util.ArrayList;
import java.util.List;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** 한 카테고리의 코스메틱 목록 (6줄, 한 쪽에 28개). 누르면 착용/해제. */
public final class CategoryMenu extends Menu {

    private static final int SIZE = 54;
    /** 테두리를 뺀 가운데 4줄 x 7칸. */
    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };
    private static final int BACK = 45;
    private static final int PREVIOUS = 48;
    private static final int UNEQUIP = 49;
    private static final int NEXT = 50;

    private final Category category;
    private final List<Cosmetic> entries;
    private final int page;
    private final int pages;
    private String pendingPurchase;
    private long pendingSince;

    public CategoryMenu(CosmeticsCore plugin, Player viewer, Category category, int page) {
        this(plugin, viewer, category, visible(plugin, viewer, category), page);
    }

    private CategoryMenu(CosmeticsCore plugin, Player viewer, Category category, List<Cosmetic> entries, int page) {
        super(plugin, viewer, SIZE, plugin.messages().get("menu.category-title",
                "category", plugin.messages().category(category),
                "page", String.valueOf(clampPage(page, entries.size()) + 1),
                "pages", String.valueOf(pageCount(entries.size()))));
        this.category = category;
        this.entries = entries;
        this.pages = pageCount(entries.size());
        this.page = clampPage(page, entries.size());
    }

    private static List<Cosmetic> visible(CosmeticsCore plugin, Player viewer, Category category) {
        boolean showLocked = plugin.settings().showLocked();
        List<Cosmetic> out = new ArrayList<>();
        for (Cosmetic c : plugin.registry().of(category)) {
            if (showLocked || plugin.manager().owns(viewer, c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static int pageCount(int size) {
        return Math.max(1, (size + CONTENT.length - 1) / CONTENT.length);
    }

    private static int clampPage(int page, int size) {
        return Math.max(0, Math.min(page, pageCount(size) - 1));
    }

    @Override
    protected void render() {
        inventory.clear();
        Messages msg = plugin.messages();
        String categoryName = msg.category(category);
        CosmeticManager manager = plugin.manager();

        if (entries.isEmpty()) {
            inventory.setItem(22, new ItemBuilder(Material.PAPER)
                    .name(msg.get("menu.empty", "category", categoryName))
                    .build());
        }
        int start = page * CONTENT.length;
        for (int i = 0; i < CONTENT.length && start + i < entries.size(); i++) {
            Cosmetic cosmetic = entries.get(start + i);
            inventory.setItem(CONTENT[i], icon(cosmetic, manager.owns(viewer, cosmetic), manager.isEquipped(viewer, cosmetic)));
        }

        inventory.setItem(BACK, new ItemBuilder(Material.ARROW).name(msg.get("menu.back")).build());
        if (page > 0) {
            inventory.setItem(PREVIOUS, new ItemBuilder(Material.SPECTRAL_ARROW).name(msg.get("menu.previous-page")).build());
        }
        if (page < pages - 1) {
            inventory.setItem(NEXT, new ItemBuilder(Material.SPECTRAL_ARROW).name(msg.get("menu.next-page")).build());
        }
        inventory.setItem(UNEQUIP, new ItemBuilder(Material.BARRIER)
                .name(msg.get("menu.unequip-category.name", "category", categoryName))
                .build());
        fill();
    }

    private ItemStack icon(Cosmetic cosmetic, boolean owned, boolean equipped) {
        Messages msg = plugin.messages();
        Material lockedIcon = plugin.settings().lockedIcon();
        ItemBuilder builder = !owned && lockedIcon != null
                ? new ItemBuilder(lockedIcon)
                : cosmetic.icon().builder(plugin.getLogger());
        List<String> lore = new ArrayList<>(cosmetic.lore());
        lore.add("");
        lore.add(msg.get("menu.item.rarity", "rarity", plugin.settings().rarity(cosmetic.rarity()).name()));
        if (!owned && cosmetic.price() > 0) {
            lore.add(msg.get("menu.item.price", "price", plugin.economy().format(cosmetic.price())));
        }
        lore.addAll(msg.list(equipped ? "menu.item.equipped" : owned ? "menu.item.available"
                : cosmetic.price() > 0 ? "menu.item.buy" : "menu.item.locked"));
        lore.addAll(msg.list("menu.item.preview"));
        return builder.name(cosmetic.name()).lore(lore).glow(equipped).hideTooltipExtras().build();
    }

    @Override
    protected void click(int slot, ClickType click) {
        switch (slot) {
            case BACK -> {
                sound("ui.button.click", 0.9f);
                openLater(new MainMenu(plugin, viewer));
                return;
            }
            case PREVIOUS -> {
                if (page > 0) {
                    sound("item.book.page_turn", 1.0f);
                    openLater(new CategoryMenu(plugin, viewer, category, entries, page - 1));
                }
                return;
            }
            case NEXT -> {
                if (page < pages - 1) {
                    sound("item.book.page_turn", 1.0f);
                    openLater(new CategoryMenu(plugin, viewer, category, entries, page + 1));
                }
                return;
            }
            case UNEQUIP -> {
                if (plugin.manager().unequip(viewer, category, true)) {
                    sound("entity.item.pickup", 0.8f);
                }
                render();
                return;
            }
            default -> {
            }
        }

        int index = indexOf(slot);
        if (index < 0) {
            return;
        }
        int entry = page * CONTENT.length + index;
        if (entry >= entries.size()) {
            return;
        }
        Cosmetic cosmetic = entries.get(entry);
        CosmeticManager manager = plugin.manager();
        if (click.isRightClick()) {
            closeLater(() -> manager.preview(viewer, cosmetic));
            return;
        }
        if (!manager.owns(viewer, cosmetic)) {
            if (cosmetic.price() <= 0) {
                plugin.messages().send(viewer, "locked", "name", cosmetic.name());
                sound("block.note_block.bass", 0.6f);
                return;
            }
            // 실수로 사지 않도록 5초 안에 한 번 더 눌러야 산다
            long now = System.currentTimeMillis();
            if (!cosmetic.id().equals(pendingPurchase) || now - pendingSince > 5000) {
                pendingPurchase = cosmetic.id();
                pendingSince = now;
                plugin.messages().send(viewer, "confirm-purchase", "name", cosmetic.name(),
                        "price", plugin.economy().format(cosmetic.price()));
                sound("block.note_block.pling", 1.2f);
                return;
            }
            pendingPurchase = null;
            if (manager.purchase(viewer, cosmetic)) {
                sound("entity.player.levelup", 1.2f);
            } else {
                sound("block.note_block.bass", 0.6f);
            }
            render();
            return;
        }
        if (manager.isEquipped(viewer, cosmetic)) {
            manager.unequip(viewer, category, true);
            sound("entity.item.pickup", 0.8f);
        } else if (manager.equip(viewer, cosmetic)) {
            sound("entity.player.levelup", 1.6f);
        }
        render();
    }

    private static int indexOf(int slot) {
        for (int i = 0; i < CONTENT.length; i++) {
            if (CONTENT[i] == slot) {
                return i;
            }
        }
        return -1;
    }
}
