package io.github.munang77.cosmeticscore.util;

import java.util.List;
import java.util.logging.Logger;

import com.google.common.collect.ArrayListMultimap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** 메뉴 아이콘과 모자 아이템을 만든다. */
public final class ItemBuilder {

    private static volatile boolean itemModelWarned;

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(new ItemStack(material));
    }

    public ItemBuilder(ItemStack base) {
        this.item = base;
        this.meta = base.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("아이템 정보를 가질 수 없는 아이템입니다: " + base.getType());
        }
    }

    public ItemMeta meta() {
        return meta;
    }

    public ItemBuilder name(String name) {
        meta.setDisplayName(name);
        return this;
    }

    public ItemBuilder lore(List<String> lore) {
        meta.setLore(lore);
        return this;
    }

    /** 반짝이게 한다. */
    public ItemBuilder glow(boolean glow) {
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        return this;
    }

    @SuppressWarnings("deprecation")
    public ItemBuilder modelData(Integer modelData) {
        if (modelData != null) {
            meta.setCustomModelData(modelData);
        }
        return this;
    }

    /** 1.21.2 이상에서만 적용된다. 그보다 낮은 서버에서는 한 번만 경고하고 무시한다. */
    public ItemBuilder itemModel(NamespacedKey model, Logger log) {
        if (model != null) {
            try {
                meta.setItemModel(model);
            } catch (NoSuchMethodError e) {
                if (!itemModelWarned) {
                    itemModelWarned = true;
                    log.warning("item-model 은 1.21.2 이상 서버에서만 쓸 수 있어 무시합니다.");
                }
            }
        }
        return this;
    }

    public ItemBuilder unbreakable() {
        meta.setUnbreakable(true);
        return this;
    }

    /** 공격력/방어력 같은 속성을 없애고, 이름과 설명 말고는 툴팁에 안 보이게 한다. */
    public ItemBuilder hideTooltipExtras() {
        // 빈 목록을 넣어야 아이템 기본 속성(투구 방어력 등)까지 사라진다
        meta.setAttributeModifiers(ArrayListMultimap.<Attribute, AttributeModifier>create());
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_PLACED_ON, ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM);
        return this;
    }

    public ItemStack build() {
        item.setItemMeta(meta);
        return item;
    }
}
