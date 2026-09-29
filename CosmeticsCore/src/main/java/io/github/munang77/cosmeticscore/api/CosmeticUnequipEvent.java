package io.github.munang77.cosmeticscore.api;

import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** 플레이어가 코스메틱을 해제한 뒤 (직접 해제, 권한 상실, 설정에서 삭제 등). */
public class CosmeticUnequipEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Cosmetic cosmetic;

    public CosmeticUnequipEvent(Player player, Cosmetic cosmetic) {
        super(player);
        this.cosmetic = cosmetic;
    }

    /** 해제된 코스메틱. 설정에서 지워진 경우에는 {@code null}. */
    public Cosmetic getCosmetic() {
        return cosmetic;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
