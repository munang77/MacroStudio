package io.github.munang77.cosmeticscore.util;

/** 설정 파일의 색 문자열을 RGB 로 바꾼다. */
public final class Colors {

    private Colors() {
    }

    /**
     * {@code "#FF8800"}, {@code "FF8800"}, {@code "255,136,0"} 을 0xRRGGBB 로 바꾼다.
     *
     * @throws IllegalArgumentException 형식이 틀렸을 때
     */
    public static int parseRgb(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("색이 비어 있습니다");
        }
        String s = input.trim();
        if (s.contains(",")) {
            String[] parts = s.split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("색은 \"R,G,B\" 형식이어야 합니다: " + input);
            }
            int rgb = 0;
            for (String part : parts) {
                int v;
                try {
                    v = Integer.parseInt(part.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("색 숫자가 잘못됐습니다: " + input);
                }
                if (v < 0 || v > 255) {
                    throw new IllegalArgumentException("색 숫자는 0~255 여야 합니다: " + input);
                }
                rgb = (rgb << 8) | v;
            }
            return rgb;
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            throw new IllegalArgumentException("색은 \"#RRGGBB\" 형식이어야 합니다: " + input);
        }
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("색은 \"#RRGGBB\" 형식이어야 합니다: " + input);
        }
    }

    /** 색상환을 {@code steps} 칸으로 나눈 무지개 색 (0xRRGGBB). */
    public static int[] rainbow(int steps) {
        int[] out = new int[steps];
        for (int i = 0; i < steps; i++) {
            out[i] = hsvToRgb(i / (double) steps, 0.85, 1.0);
        }
        return out;
    }

    /** 여러 색 사이를 {@code t}(0~1) 만큼 이은 색. */
    public static int lerp(int[] stops, double t) {
        if (stops.length == 1) {
            return stops[0];
        }
        double pos = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int i = Math.min((int) Math.floor(pos), stops.length - 2);
        double f = pos - i;
        int a = stops[i];
        int b = stops[i + 1];
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    /** 채도·명도 고정 색상환 변환 (h, s, v 는 0~1). */
    static int hsvToRgb(double h, double s, double v) {
        double sector = (h - Math.floor(h)) * 6.0;
        int i = (int) Math.floor(sector);
        double f = sector - i;
        double p = v * (1 - s);
        double q = v * (1 - s * f);
        double t = v * (1 - s * (1 - f));
        double r;
        double g;
        double b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) Math.round(r * 255) << 16) | ((int) Math.round(g * 255) << 8) | (int) Math.round(b * 255);
    }
}
