package io.github.munang77.cosmeticscore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import io.github.munang77.cosmeticscore.util.Colors;
import io.github.munang77.cosmeticscore.util.Text;
import org.junit.jupiter.api.Test;

class TextAndColorsTest {

    @Test
    void translatesLegacyAndHexCodes() {
        assertEquals("§a[새싹]", Text.color("&a[새싹]"));
        assertEquals("§x§f§f§6§9§b§4하트", Text.color("&#FF69B4하트"));
        assertEquals("", Text.color((String) null));
    }

    @Test
    void plainStripsColors() {
        assertEquals("[왕]", Text.plain("&6&l[왕]"));
        assertEquals("무지개", Text.plain("&#FF5555무&#FFAA00지&#FFFF55개"));
    }

    @Test
    void replacesPlaceholders() {
        assertEquals("Steve님 모자", Text.replace("{player}님 {category}", "player", "Steve", "category", "모자"));
    }

    @Test
    void parsesColorFormats() {
        assertEquals(0xFF8800, Colors.parseRgb("#FF8800"));
        assertEquals(0xFF8800, Colors.parseRgb("ff8800"));
        assertEquals(0xFF8800, Colors.parseRgb("255, 136, 0"));
        assertThrows(IllegalArgumentException.class, () -> Colors.parseRgb("#FFF"));
        assertThrows(IllegalArgumentException.class, () -> Colors.parseRgb("300,0,0"));
        assertThrows(IllegalArgumentException.class, () -> Colors.parseRgb(""));
    }

    @Test
    void rainbowStartsRedAndHasDistinctColors() {
        int[] rainbow = Colors.rainbow(36);
        assertEquals(36, rainbow.length);
        assertEquals(0xFF, rainbow[0] >> 16);
        assertEquals(36, Arrays.stream(rainbow).distinct().count());
        assertArrayEquals(Colors.rainbow(6), Colors.rainbow(6));
    }
}
