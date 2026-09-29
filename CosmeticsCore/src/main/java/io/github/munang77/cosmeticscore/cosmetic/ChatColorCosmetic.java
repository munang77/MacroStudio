package io.github.munang77.cosmeticscore.cosmetic;

import io.github.munang77.cosmeticscore.util.Colors;
import io.github.munang77.cosmeticscore.util.Text;

/** 채팅 글자 색: 한 가지 색, 그라데이션, 무지개. */
public final class ChatColorCosmetic extends Cosmetic {

    /** 한 가지 색 코드 (예: "§b§l"). 그라데이션이면 {@code null}. */
    private final String prefix;
    /** 그라데이션 기준 색 (0xRRGGBB). 한 가지 색이면 {@code null}. */
    private final int[] gradient;
    private final String format;

    public ChatColorCosmetic(Info info, String prefix, int[] gradient, String format) {
        super(info, Category.CHAT_COLOR);
        this.prefix = prefix;
        this.gradient = gradient == null ? null : gradient.clone();
        this.format = format == null ? "" : format;
    }

    /** 메시지에 색을 입힌다. */
    public String apply(String message) {
        if (gradient == null) {
            return prefix + message;
        }
        return gradient(message, gradient, format);
    }

    /** 글자마다 색을 조금씩 바꿔 {@code stops} 를 차례로 지나는 그라데이션을 만든다. */
    static String gradient(String text, int[] stops, String format) {
        int[] cps = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder(cps.length * 16);
        int visible = 0;
        for (int cp : cps) {
            if (!Character.isWhitespace(cp)) {
                visible++;
            }
        }
        int index = 0;
        for (int cp : cps) {
            if (Character.isWhitespace(cp)) {
                sb.appendCodePoint(cp);
                continue;
            }
            double t = visible <= 1 ? 0 : index / (double) (visible - 1);
            Text.appendHex(sb, Colors.lerp(stops, t));
            sb.append(format);
            sb.appendCodePoint(cp);
            index++;
        }
        return sb.toString();
    }
}
