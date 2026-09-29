package io.github.munang77.cosmeticscore.chat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.ChatColorCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.JoinEffectCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.KillMessageCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.TitleCosmetic;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 글자로 보이는 코스메틱: 칭호(채팅/탭), 채팅 색, 입장/퇴장 효과, 킬 메시지. */
public final class ChatService implements Listener {

    private final CosmeticsCore plugin;
    /** 탭 이름을 바꿔 둔 플레이어. 이 플러그인이 바꾼 이름만 되돌린다. */
    private final Set<UUID> renamed = ConcurrentHashMap.newKeySet();

    public ChatService(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    // ── 칭호 ─────────────────────────────────────

    /** 착용 중인 칭호 글자 (색 포함). 없으면 빈 문자열. 비동기 스레드에서 불러도 된다. */
    public String title(Player player) {
        TitleCosmetic title = plugin.manager().equipped(player, Category.TITLE, TitleCosmetic.class);
        return title == null ? "" : title.title();
    }

    /** 탭 목록 이름을 착용 정보에 맞춘다. */
    public void applyTab(Player player) {
        String title = plugin.settings().titleTabList() ? title(player) : "";
        if (title.isEmpty()) {
            resetTab(player);
            return;
        }
        player.setPlayerListName(title + ChatColor.RESET + " " + player.getName());
        renamed.add(player.getUniqueId());
    }

    /** 이 플러그인이 바꾼 탭 이름을 원래대로 돌린다. */
    public void resetTab(Player player) {
        if (renamed.remove(player.getUniqueId())) {
            player.setPlayerListName(null);
        }
    }

    public void resetAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            resetTab(player);
        }
    }

    // ── 채팅 ─────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatColorCosmetic color = plugin.manager().equipped(player, Category.CHAT_COLOR, ChatColorCosmetic.class);
        if (color != null) {
            event.setMessage(color.apply(event.getMessage()));
        }
        if (!plugin.settings().titleChat()) {
            return;
        }
        String title = title(player);
        if (!title.isEmpty()) {
            // 형식 문자열이라 % 는 %% 로 바꿔야 한다
            event.setFormat(title.replace("%", "%%") + ChatColor.RESET + " " + event.getFormat());
        }
    }

    // ── 입장 / 퇴장 ──────────────────────────────

    /** 데이터는 LOWEST 에서 읽으므로 그 뒤에 처리한다. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        JoinEffectCosmetic effect = joinEffect(player);
        if (effect == null) {
            return;
        }
        if (effect.joinMessage() != null && event.getJoinMessage() != null) {
            event.setJoinMessage(Text.replace(effect.joinMessage(), "player", player.getName()));
        }
        play(player, effect, true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        renamed.remove(player.getUniqueId());
        JoinEffectCosmetic effect = joinEffect(player);
        if (effect != null && effect.quitMessage() != null && event.getQuitMessage() != null) {
            event.setQuitMessage(Text.replace(effect.quitMessage(), "player", player.getName()));
        }
    }

    private JoinEffectCosmetic joinEffect(Player player) {
        if (!plugin.settings().joinMessages() || plugin.manager().isHidden(player)) {
            return null;
        }
        return plugin.manager().equipped(player, Category.JOIN_EFFECT, JoinEffectCosmetic.class);
    }

    /** 소리와 폭죽. {@code everyone} 이 거짓이면 본인만 듣는다 (미리보기). */
    private void play(Player player, JoinEffectCosmetic effect, boolean everyone) {
        if (effect.sound() != null) {
            if (everyone) {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    other.playSound(other.getLocation(), effect.sound(), effect.volume(), effect.pitch());
                }
            } else {
                player.playSound(player.getLocation(), effect.sound(), effect.volume(), effect.pitch());
            }
        }
        if (effect.firework() != null && !plugin.settings().isDisabled(player.getWorld())) {
            plugin.effects().spawnFirework(player.getLocation().add(0, 1, 0), effect.firework());
        }
    }

    /** 미리보기: 입장 메시지를 본인에게만 보여 주고 효과를 낸다. */
    public void previewJoin(Player player, JoinEffectCosmetic effect) {
        if (effect.joinMessage() != null) {
            player.sendMessage(Text.replace(effect.joinMessage(), "player", player.getName()));
        }
        if (effect.quitMessage() != null) {
            player.sendMessage(Text.replace(effect.quitMessage(), "player", player.getName()));
        }
        play(player, effect, false);
    }

    // ── 킬 메시지 ────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || !plugin.settings().killMessages()
                || event.getDeathMessage() == null) {
            return;
        }
        KillMessageCosmetic message = plugin.manager().equipped(killer, Category.KILL_MESSAGE, KillMessageCosmetic.class);
        if (message != null) {
            event.setDeathMessage(Text.replace(message.message(),
                    "killer", killer.getName(), "victim", victim.getName()));
        }
    }
}
