package io.github.munang77.cosmeticscore;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.CosmeticRegistry;
import io.github.munang77.cosmeticscore.util.Materials;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml 을 읽어 둔 값. 다시 불러오면 통째로 새 객체로 바뀐다. */
public final class Settings {

    public static final int MAIN_MENU_SIZE = 45;
    /** 메인 메뉴의 고정 버튼 자리 (카테고리 버튼을 여기에 둘 수 없다). */
    public static final int PROFILE_SLOT = 4;
    public static final int CRATE_SLOT = 22;
    public static final int TOGGLE_SLOT = 39;
    public static final int UNEQUIP_ALL_SLOT = 41;

    /** 등급 이름(색 포함)과 뽑기 가중치. */
    public record Rarity(String key, String name, int weight) {
    }

    private final Set<String> disabledWorlds = new HashSet<>();
    private final int particleInterval;
    private final double viewDistance;
    private final boolean hideWhenInvisible;
    private final int arrowMaxTicks;
    private final boolean killEffectsOnMobs;
    private final boolean moveHelmetToInventory;
    private final boolean titleChat;
    private final boolean titleTabList;
    private final boolean joinMessages;
    private final boolean killMessages;
    private final boolean previewEnabled;
    private final int previewSeconds;
    private final int previewCooldownSeconds;
    private final boolean crateEnabled;
    private final double cratePrice;
    private final Set<String> crateBroadcast = new HashSet<>();
    private final Map<String, Rarity> rarities;
    private final boolean showLocked;
    private final Material lockedIcon;
    private final Material filler;
    private final Map<Category, Integer> categorySlots = new EnumMap<>(Category.class);
    private final Map<Category, Material> categoryIcons = new EnumMap<>(Category.class);

    Settings(FileConfiguration c, Logger log) {
        for (String w : c.getStringList("disabled-worlds")) {
            disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        }
        particleInterval = Math.max(1, c.getInt("particles.interval-ticks", 2));
        viewDistance = Math.max(1, c.getDouble("particles.view-distance", 32));
        hideWhenInvisible = c.getBoolean("particles.hide-when-invisible", true);
        arrowMaxTicks = Math.max(1, c.getInt("arrow-trails.max-ticks", 200));
        killEffectsOnMobs = c.getBoolean("kill-effects.include-mobs", false);
        moveHelmetToInventory = c.getBoolean("hats.move-helmet-to-inventory", true);
        titleChat = c.getBoolean("titles.chat", true);
        titleTabList = c.getBoolean("titles.tab-list", true);
        joinMessages = c.getBoolean("messages.join-effects", true);
        killMessages = c.getBoolean("messages.kill-messages", true);
        previewEnabled = c.getBoolean("preview.enabled", true);
        previewSeconds = Math.max(1, Math.min(120, c.getInt("preview.seconds", 10)));
        previewCooldownSeconds = Math.max(0, c.getInt("preview.cooldown-seconds", 30));
        crateEnabled = c.getBoolean("crate.enabled", true);
        cratePrice = Math.max(0, c.getDouble("crate.price", 0));
        for (String r : c.getStringList("crate.broadcast-rarities")) {
            crateBroadcast.add(r.toLowerCase(Locale.ROOT));
        }
        rarities = readRarities(c.getConfigurationSection("rarities"), log);
        showLocked = c.getBoolean("menu.show-locked", true);
        lockedIcon = material(c.getString("menu.locked-icon", "GRAY_DYE"), "menu.locked-icon", log);
        filler = material(c.getString("menu.filler", "GRAY_STAINED_GLASS_PANE"), "menu.filler", log);

        Set<Integer> used = new HashSet<>(Set.of(PROFILE_SLOT, CRATE_SLOT, TOGGLE_SLOT, UNEQUIP_ALL_SLOT));
        for (Category cat : Category.values()) {
            ConfigurationSection s = c.getConfigurationSection("menu.categories." + cat.key());
            int wanted = s == null ? cat.defaultSlot() : s.getInt("slot", cat.defaultSlot());
            int slot = wanted;
            if (slot < 0 || slot >= MAIN_MENU_SIZE || used.contains(slot)) {
                slot = used.contains(cat.defaultSlot()) ? firstFree(used) : cat.defaultSlot();
                log.warning("[config.yml] menu.categories." + cat.key() + ".slot 값 " + wanted
                        + " 을(를) 쓸 수 없어 " + slot + " 번 칸을 씁니다.");
            }
            used.add(slot);
            categorySlots.put(cat, slot);
            Material icon = s == null ? null : material(s.getString("icon"), "menu.categories." + cat.key() + ".icon", log);
            categoryIcons.put(cat, icon == null ? cat.defaultIcon() : icon);
        }
    }

    private static int firstFree(Set<Integer> used) {
        for (int i = 9; i < MAIN_MENU_SIZE; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        throw new IllegalStateException("메인 메뉴에 빈칸이 없습니다");
    }

    private static Map<String, Rarity> readRarities(ConfigurationSection section, Logger log) {
        Map<String, Rarity> out = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String k = key.toLowerCase(Locale.ROOT);
                String name = Text.color(section.getString(key + ".name", key));
                int weight = Math.max(0, section.getInt(key + ".weight", 0));
                out.put(k, new Rarity(k, name, weight));
            }
        }
        if (!out.containsKey(CosmeticRegistry.DEFAULT_RARITY)) {
            out.put(CosmeticRegistry.DEFAULT_RARITY, new Rarity(CosmeticRegistry.DEFAULT_RARITY, Text.color("&7일반"), 60));
            if (section != null) {
                log.warning("[config.yml] rarities 에 common 이 없어 기본값을 씁니다.");
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Material material(String name, String path, Logger log) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material m = Materials.item(name);
        if (m == null) {
            log.warning("[config.yml] " + path + ": 쓸 수 없는 아이템입니다: " + name);
        }
        return m;
    }

    public boolean isDisabled(World world) {
        return world != null && !disabledWorlds.isEmpty() && disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public int particleInterval() {
        return particleInterval;
    }

    public double viewDistance() {
        return viewDistance;
    }

    public boolean hideWhenInvisible() {
        return hideWhenInvisible;
    }

    public int arrowMaxTicks() {
        return arrowMaxTicks;
    }

    public boolean killEffectsOnMobs() {
        return killEffectsOnMobs;
    }

    public boolean moveHelmetToInventory() {
        return moveHelmetToInventory;
    }

    public boolean titleChat() {
        return titleChat;
    }

    public boolean titleTabList() {
        return titleTabList;
    }

    public boolean joinMessages() {
        return joinMessages;
    }

    public boolean killMessages() {
        return killMessages;
    }

    public boolean previewEnabled() {
        return previewEnabled;
    }

    public int previewSeconds() {
        return previewSeconds;
    }

    /** 잠긴 코스메틱을 다시 미리 보려면 기다려야 하는 시간 (가진 코스메틱은 제한 없음). */
    public int previewCooldownSeconds() {
        return previewCooldownSeconds;
    }

    public boolean crateEnabled() {
        return crateEnabled;
    }

    /** 0 이면 돈으로는 뽑기를 열 수 없다 (열쇠만). */
    public double cratePrice() {
        return cratePrice;
    }

    public boolean broadcastCrate(String rarity) {
        return crateBroadcast.contains(rarity);
    }

    /** 등급 정보. 모르는 등급이면 common. */
    public Rarity rarity(String key) {
        Rarity r = rarities.get(key);
        return r != null ? r : rarities.get(CosmeticRegistry.DEFAULT_RARITY);
    }

    public boolean hasRarity(String key) {
        return rarities.containsKey(key);
    }

    public boolean showLocked() {
        return showLocked;
    }

    /** @return 원래 아이콘을 그대로 쓰려면 {@code null} */
    public Material lockedIcon() {
        return lockedIcon;
    }

    /** @return 빈칸으로 두려면 {@code null} */
    public Material filler() {
        return filler;
    }

    public int categorySlot(Category category) {
        return categorySlots.get(category);
    }

    public Material categoryIcon(Category category) {
        return categoryIcons.get(category);
    }
}
