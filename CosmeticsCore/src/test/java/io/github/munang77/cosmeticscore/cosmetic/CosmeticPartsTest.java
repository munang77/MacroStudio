package io.github.munang77.cosmeticscore.cosmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.munang77.cosmeticscore.crate.CrateService;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class CosmeticPartsTest {

    private static final String HASH = "4b8e2b5f6f8b6b2d1a2f3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7081";

    @Test
    void gradientRunsFromFirstToLastColorAndKeepsSpaces() {
        String out = ChatColorCosmetic.gradient("가 나다", new int[] {0xFF0000, 0x0000FF}, "");
        assertEquals("가 나다", Text.plain(out));
        assertTrue(out.startsWith("§x§f§f§0§0§0§0가"), out);
        assertTrue(out.endsWith("§x§0§0§0§0§f§f다"), out);
        assertTrue(out.contains(" "));
    }

    @Test
    void singleCharacterGradientUsesFirstColor() {
        assertEquals("§x§0§0§f§f§0§0§l!",
                ChatColorCosmetic.gradient("!", new int[] {0x00FF00, 0x0000FF}, "§l"));
    }

    @Test
    void textureAcceptsHashUrlAndBase64() {
        String url = "http://textures.minecraft.net/texture/" + HASH;
        assertEquals(url, ItemSpec.textureUrl(HASH));
        assertEquals(url, ItemSpec.textureUrl("https://textures.minecraft.net/texture/" + HASH));
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(url, ItemSpec.textureUrl(b64));
    }

    @Test
    void textureRejectsOtherHosts() {
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.textureUrl("http://evil.example.com/texture/" + HASH));
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.textureUrl("not a texture!"));
    }

    private static TitleCosmetic title(String id, String rarity) {
        return new TitleCosmetic(new Cosmetic.Info(id, id, List.of(), ItemSpec.of(Material.NAME_TAG), false,
                "p." + id, rarity, 0, true), "[" + id + "]");
    }

    @Test
    void crateFollowsRarityWeights() {
        List<Cosmetic> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pool.add(title("common" + i, "common"));
        }
        pool.add(title("legend", "legendary"));
        Map<String, Integer> weights = Map.of("common", 90, "legendary", 10);
        Random random = new Random(42);
        Map<String, Integer> hits = new HashMap<>();
        int rolls = 20000;
        for (int i = 0; i < rolls; i++) {
            hits.merge(CrateService.pick(pool, weights::get, random).rarity(), 1, Integer::sum);
        }
        double legendary = hits.getOrDefault("legendary", 0) / (double) rolls;
        assertEquals(0.10, legendary, 0.015, "전설 비율 " + legendary);
    }

    @Test
    void crateSkipsZeroWeightUnlessEverythingIsZero() {
        List<Cosmetic> pool = List.of(title("a", "common"), title("b", "never"));
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertEquals("a", CrateService.pick(pool, r -> r.equals("common") ? 5 : 0, random).id());
        }
        boolean sawB = false;
        for (int i = 0; i < 200 && !sawB; i++) {
            sawB = CrateService.pick(pool, r -> 0, random).id().equals("b");
        }
        assertTrue(sawB, "가중치가 모두 0 이면 전체에서 고르게 뽑아야 함");
    }

    @Test
    void displayCycleWalksThroughItems() {
        DisplayCosmetic balloon = new DisplayCosmetic(new Cosmetic.Info("b", "b", List.of(), ItemSpec.of(Material.RED_WOOL),
                false, "p", "common", 0, true), Category.BALLOON, ItemSpec.of(Material.RED_WOOL),
                List.of(ItemSpec.of(Material.RED_WOOL), ItemSpec.of(Material.BLUE_WOOL)), 10, 0.7f, 0, null, null);
        assertEquals(Material.RED_WOOL, balloon.itemAt(0).material());
        assertEquals(Material.RED_WOOL, balloon.itemAt(9).material());
        assertEquals(Material.BLUE_WOOL, balloon.itemAt(10).material());
        assertEquals(Material.RED_WOOL, balloon.itemAt(20).material());
        assertThrows(IllegalArgumentException.class, () -> new DisplayCosmetic(balloon.icon() == null ? null
                : new Cosmetic.Info("x", "x", List.of(), ItemSpec.of(Material.STONE), false, "p", "common", 0, true),
                Category.HAT, ItemSpec.of(Material.STONE), List.of(), 1, 1, 0, null, null));
    }
}
