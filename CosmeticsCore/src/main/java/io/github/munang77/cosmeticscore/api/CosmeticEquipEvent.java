package io.github.munang77.cosmeticscore.api;

import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** 플레이어가 코스메틱을 착용하기 직전. 취소하면 착용하지 않는다. */
public class CosmeticEquipEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Cosmetic cosmetic;
    private boolean cancelled;

    public CosmeticEquipEvent(Player player, Cosmetic cosmetic) {
        super(player);
        this.cosmetic = cosmetic;
    }

    public Cosmetic getCosmetic() {
        return cosmetic;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
