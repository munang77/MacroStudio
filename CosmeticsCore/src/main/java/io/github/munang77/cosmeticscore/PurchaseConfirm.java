package io.github.munang77.cosmeticscore;

import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import org.bukkit.entity.Player;

/** 실수로 사지 않도록 5초 안에 한 번 더 눌러야 사게 하는 확인 (메뉴·옷장 공통). 메뉴나 옷장마다 하나씩 둔다. */
public final class PurchaseConfirm {

    private static final long WINDOW_MS = 5000;

    private String pending;
    private long since;

    /**
     * 처음 누르면 안내({@code messageKey})를 보내고 {@code false}, 5초 안에 같은 것을 다시 누르면 {@code true}.
     */
    public boolean confirm(CosmeticsCore plugin, Player player, Cosmetic cosmetic, String messageKey) {
        long now = System.currentTimeMillis();
        if (!cosmetic.id().equals(pending) || now - since > WINDOW_MS) {
            pending = cosmetic.id();
            since = now;
            plugin.messages().send(player, messageKey, "name", cosmetic.name(),
                    "price", plugin.economy().format(cosmetic.price()));
            return false;
        }
        pending = null;
        return true;
    }

    /** 다른 것을 고르면 확인을 처음부터. */
    public void reset() {
        pending = null;
    }
}
