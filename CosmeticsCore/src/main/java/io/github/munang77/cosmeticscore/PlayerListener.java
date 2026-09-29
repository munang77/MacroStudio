package io.github.munang77.cosmeticscore;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 접속하면 데이터를 읽고, 나가면 모자를 벗기고 저장한다. */
final class PlayerListener implements Listener {

    private final CosmeticsCore plugin;

    PlayerListener(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.manager().handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // 서버가 플레이어 파일을 저장하기 전에 모자를 벗겨서, 플러그인을 지워도 모자 아이템이 남지 않게 한다
        plugin.hats().strip(player);
        plugin.effects().forget(player);
        plugin.manager().handleQuit(player);
        plugin.store().unload(player.getUniqueId());
    }
}
