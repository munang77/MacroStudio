package io.github.munang77.cosmeticscore.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.ChatColor;

/** 색코드 변환과 {자리표시자} 치환. */
public final class Text {

    private static final char SECTION = '§';
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private Text() {
    }

    /** {@code &a}, {@code &l} 같은 코드와 {@code &#RRGGBB} 헥스 색을 실제 색으로 바꾼다. */
    public static String color(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = HEX.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (m.find()) {
            StringBuilder hex = new StringBuilder(14).append(SECTION).append('x');
            for (char c : m.group(1).toCharArray()) {
                hex.append(SECTION).append(Character.toLowerCase(c));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(hex.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static List<String> color(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(color(line));
        }
        return out;
    }

    /** 색을 모두 뺀 글자. */
    public static String plain(String text) {
        String stripped = ChatColor.stripColor(color(text));
        return stripped == null ? "" : stripped;
    }

    /** {@code replace("{a} {b}", "a", "1", "b", "2")} → {@code "1 2"} */
    public static String replace(String text, String... pairs) {
        String out = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out = out.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return out;
    }

    public static List<String> replace(List<String> lines, String... pairs) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(replace(line, pairs));
        }
        return out;
    }
}
