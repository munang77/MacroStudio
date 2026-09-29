package io.github.munang77.cosmeticscore;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 로그인할 때 데이터를 미리 읽고, 들어오면 적용하고, 나가면 모자를 벗기고 저장한다. */
final class PlayerListener implements Listener {

    private final CosmeticsCore plugin;

    PlayerListener(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    /** 로그인 스레드에서 읽어 두므로 저장소가 느려도 서버 틱이 멈추지 않는다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            plugin.store().preload(event.getUniqueId(), event.getName());
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            plugin.store().release(event.getPlayer().getUniqueId());
        }
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
