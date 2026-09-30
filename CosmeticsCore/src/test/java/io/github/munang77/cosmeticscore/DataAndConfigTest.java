package io.github.munang77.cosmeticscore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.CosmeticRegistry;
import io.github.munang77.cosmeticscore.cosmetic.KillEffectCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Motion;
import io.github.munang77.cosmeticscore.cosmetic.ParticleCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.ParticleStyle;
import io.github.munang77.cosmeticscore.cosmetic.TitleCosmetic;
import io.github.munang77.cosmeticscore.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DataAndConfigTest {

    @Test
    void playerDataSurvivesSaveAndLoad() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerData data = new PlayerData(id);
        data.setLastName("Steve");
        assertTrue(data.unlock("crown"));
        assertFalse(data.unlock("crown"));
        data.unlock("red_wings");
        data.setEquipped(Category.HAT, "crown");
        data.setEquipped(Category.PARTICLE, "red_wings");
        data.setShowOthers(false);

        PlayerData back = PlayerData.deserialize(id, data.serialize());
        assertEquals("Steve", back.lastName());
        assertEquals(Set.of("crown", "red_wings"), back.unlocked());
        assertEquals("crown", back.equipped(Category.HAT));
        assertEquals("red_wings", back.equipped(Category.PARTICLE));
        assertNull(back.equipped(Category.TITLE));
        assertFalse(back.showOthers());

        back.setEquipped(Category.HAT, null);
        assertTrue(back.lock("crown"));
        PlayerData again = PlayerData.deserialize(id, back.serialize());
        assertNull(again.equipped(Category.HAT));
        assertEquals(Set.of("red_wings"), again.unlocked());
    }

    @Test
    void registryLoadsValidEntriesAndSkipsBrokenOnes() {
        String yaml = """
                particles:
                  good_halo:
                    name: "&6후광"
                    particle: minecraft:flame
                    style: halo
                  rainbow:
                    particle: DUST
                    rainbow: true
                    style: WINGS
                  bad_particle:
                    particle: NOT_A_PARTICLE
                  bad_style:
                    particle: FLAME
                    style: SQUARE
                  "Bad Id":
                    particle: FLAME
                arrow-trails:
                  good_halo:
                    particle: FLAME
                kill-effects:
                  boom:
                    effect: FIREWORK
                    colors: ["#FF0000", "0,255,0"]
                  bad_color:
                    effect: FIREWORK
                    colors: ["#GG0000"]
                titles:
                  king:
                    title: "&6[왕]"
                  empty: {}
                """;
        YamlConfiguration config = new YamlConfiguration();
        assertDoesNotThrowLoad(config, yaml);
        List<String> warnings = new ArrayList<>();
        CosmeticRegistry registry = new CosmeticRegistry();
        int count = registry.load(config, capture(warnings));

        assertEquals(4, count, () -> "경고: " + warnings);
        ParticleCosmetic halo = assertInstanceOf(ParticleCosmetic.class, registry.get("GOOD_HALO"));
        assertEquals(Particle.FLAME, halo.spec().particle());
        assertEquals(ParticleStyle.HALO, halo.style());
        assertEquals("§6후광", halo.name());
        assertEquals("cosmeticscore.cosmetic.good_halo", halo.permission());
        assertFalse(halo.free());

        ParticleCosmetic rainbow = assertInstanceOf(ParticleCosmetic.class, registry.get("rainbow"));
        assertInstanceOf(Particle.DustOptions.class, rainbow.spec().dataAt(0));
        assertFalse(rainbow.spec().dataAt(0).equals(rainbow.spec().dataAt(5)));

        KillEffectCosmetic boom = assertInstanceOf(KillEffectCosmetic.class, registry.get("boom"));
        assertEquals(2, boom.firework().getColors().size());

        TitleCosmetic king = assertInstanceOf(TitleCosmetic.class, registry.get("king"));
        assertEquals("§6[왕]", king.title());
        assertEquals(Category.TITLE, king.category());

        assertEquals(List.of(), registry.of(Category.ARROW_TRAIL), "중복 아이디는 건너뛰어야 함");
        assertEquals(6, warnings.size(), () -> "경고: " + warnings);
    }

    /** 플러그인에 들어 있는 기본 cosmetics.yml 에 오타가 없는지. */
    @Test
    void bundledCosmeticsAreValid() throws Exception {
        YamlConfiguration yaml = bundled("cosmetics.yml");
        int total = 0;
        Set<String> ids = new HashSet<>();
        for (Category category : Category.values()) {
            ConfigurationSection section = yaml.getConfigurationSection(category.section());
            assertNotNull(section, category.section() + " 섹션이 없음");
            for (String id : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(id);
                total++;
                assertTrue(ids.add(id), "아이디가 겹침: " + id);
                String animation = s.getString("animation");
                if (animation != null) {
                    Motion.valueOf(animation);
                }
                int cmd = s.getInt("custom-model-data", 0);
                if (cmd >= 7_130_000 && cmd < 7_140_000) {
                    // 플러그인 리소스팩 모델: 그 아이템의 정의에 이 번호가 있어야 한다
                    String item = s.getString("material").toLowerCase(Locale.ROOT);
                    String definition = resource("pack/assets/minecraft/items/" + item + ".json");
                    assertTrue(definition.contains("\"threshold\": " + cmd + ","), id + " 의 모델 " + cmd + " 이 리소스팩에 없음");
                }
                for (String key : List.of("material", "icon", "block", "string")) {
                    String name = s.getString(key);
                    if (name != null) {
                        assertNotNull(Material.getMaterial(name.toUpperCase(Locale.ROOT)), id + "." + key + " = " + name);
                    }
                }
                for (String name : s.getStringList("cycle")) {
                    assertNotNull(Material.getMaterial(name), id + ".cycle = " + name);
                }
                for (String key : List.of("rarity")) {
                    String rarity = s.getString(key);
                    if (rarity != null) {
                        assertTrue(Set.of("common", "rare", "epic", "legendary").contains(rarity), id + " 등급 " + rarity);
                    }
                }
                String particle = s.getString("particle");
                if (particle != null) {
                    Particle.valueOf(particle.toUpperCase(Locale.ROOT));
                }
                String style = s.getString("style");
                if (style != null) {
                    ParticleStyle.valueOf(style);
                }
                String effect = s.getString("effect");
                if (effect != null) {
                    KillEffectCosmetic.Effect.valueOf(effect);
                }
            }
        }
        assertTrue(total >= 70, "기본 코스메틱 수: " + total);
    }

    /** 리소스팩 목록에 적힌 파일이 모두 들어 있는지. */
    @Test
    void bundledResourcePackIsComplete() throws Exception {
        List<String> files = resource("pack/index.txt").lines().filter(l -> !l.isBlank()).toList();
        assertTrue(files.contains("pack.mcmeta"));
        for (String file : files) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("pack/" + file)) {
                assertNotNull(in, "리소스팩 파일이 없음: " + file);
            }
        }
    }

    private String resource(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " 이 없음");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 기본 messages.yml 에 코드에서 쓰는 문구가 모두 있는지. */
    @Test
    void bundledMessagesHaveEveryKey() throws Exception {
        YamlConfiguration yaml = bundled("messages.yml");
        for (String key : List.of("prefix", "hat-protected", "hat-blocks-helmet", "helmet-cursed",
                "preview-cooldown", "preview-disabled", "preview-chat-format",
                "already-have", "already-owned", "cancelled", "confirm-purchase", "crate-broadcast",
                "crate-complete", "crate-disabled", "crate-no-key", "crate-no-money", "crate-title", "crate-won",
                "disabled-world", "economy-missing", "equipped", "given", "given-target", "helmet-blocked",
                "helmet-inventory-full", "helmet-moved", "help", "help-admin", "keys-given", "keys-own",
                "keys-received", "list-entry-equipped", "list-entry-locked", "list-entry-owned", "list-header",
                "locked", "menu.back", "menu.category-button.lore", "menu.category-button.name",
                "menu.category-title", "menu.crate.lore", "menu.crate.name", "menu.crate.no-price",
                "menu.crate.pointer", "menu.crate.title", "menu.empty", "menu.item.available", "menu.item.buy",
                "menu.item.equipped", "menu.item.locked", "menu.item.preview", "menu.item.price", "menu.item.rarity",
                "menu.main-title", "menu.next-page", "menu.none", "menu.previous-page", "menu.profile.line",
                "menu.profile.name", "menu.profile.owned", "menu.toggle.lore", "menu.toggle.name-off",
                "menu.toggle.name-on", "menu.unequip-all.lore", "menu.unequip-all.name",
                "menu.unequip-category.name", "migrate-done", "migrate-failed", "migrate-start", "migrate-yaml",
                "no-permission", "not-a-number", "not-enough-money", "not-for-sale", "not-owned", "nothing-equipped",
                "player-not-found", "player-only", "preview-chat-sample", "preview-end", "preview-helmet",
                "preview-once", "preview-start", "preview-victim", "purchase-failed", "purchased", "reload-failed",
                "reloaded", "taken", "toggle-off", "toggle-on", "unequipped", "unequipped-all", "unknown-category",
                "unknown-cosmetic", "confirm-purchase-wardrobe", "menu.wardrobe.name", "menu.wardrobe.lore",
                "wardrobe.disabled", "wardrobe.busy", "wardrobe.empty", "wardrobe.no-space", "wardrobe.failed",
                "wardrobe.closed", "wardrobe.timeout", "wardrobe.location-set", "wardrobe.location-cleared",
                "wardrobe.help", "wardrobe.action-bar", "wardrobe.header", "wardrobe.info", "wardrobe.info-none",
                "wardrobe.status.equipped", "wardrobe.status.owned", "wardrobe.status.price", "wardrobe.status.locked",
                "wardrobe.button.prev-item", "wardrobe.button.next-item", "wardrobe.button.prev-category",
                "wardrobe.button.next-category", "wardrobe.button.equip", "wardrobe.button.wearing",
                "wardrobe.button.take-off", "wardrobe.button.buy", "wardrobe.button.locked",
                "wardrobe.button.rotate-on", "wardrobe.button.rotate-off", "wardrobe.button.exit",
                "resource-pack.sent", "resource-pack.not-configured", "resource-pack.declined",
                "resource-pack.failed")) {
            assertTrue(yaml.contains(key), "messages.yml 에 " + key + " 가 없음");
        }
        for (Category category : Category.values()) {
            assertTrue(yaml.contains("categories." + category.key()), "카테고리 이름 없음: " + category.key());
        }
    }

    private static YamlConfiguration bundled(String name) throws Exception {
        try (InputStream in = DataAndConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static void assertDoesNotThrowLoad(YamlConfiguration config, String yaml) {
        try {
            config.loadFromString(yaml);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Logger capture(List<String> warnings) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
