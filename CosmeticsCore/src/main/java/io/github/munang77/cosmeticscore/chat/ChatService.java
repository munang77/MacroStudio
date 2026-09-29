package io.github.munang77.cosmeticscore.chat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.ChatColorCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
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

    /** 이름 앞에 붙일 칭호 ("칭호§r "). 없으면 빈 문자열. */
    public String titlePrefix(Player player) {
        return prefixOf(title(player));
    }

    private static String prefixOf(String title) {
        return title.isEmpty() ? "" : title + ChatColor.RESET + " ";
    }

    /** 탭 목록 이름을 착용 정보에 맞춘다. */
    public void applyTab(Player player) {
        String prefix = plugin.settings().titleTabList() ? titlePrefix(player) : "";
        if (prefix.isEmpty()) {
            resetTab(player);
            return;
        }
        player.setPlayerListName(prefix + player.getName());
        renamed.add(player.getUniqueId());
    }

    /** 이 플러그인이 바꾼 탭 이름을 원래대로 돌린다. */
    public void resetTab(Player player) {
        if (renamed.remove(player.getUniqueId())) {
            player.setPlayerListName(null);
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
        if (plugin.settings().titleChat()) {
            // 형식 문자열이라 % 는 %% 로 바꿔야 한다
            event.setFormat(titlePrefix(player).replace("%", "%%") + event.getFormat());
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
        String message = effect.joinMessage(player.getName());
        if (message != null && event.getJoinMessage() != null) {
            event.setJoinMessage(message);
        }
        play(player, effect, true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        renamed.remove(player.getUniqueId());
        JoinEffectCosmetic effect = joinEffect(player);
        String message = effect == null ? null : effect.quitMessage(player.getName());
        if (message != null && event.getQuitMessage() != null) {
            event.setQuitMessage(message);
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

    /**
     * 글자로 보이는 코스메틱(칭호, 채팅 색, 킬 메시지, 입장 효과)의 미리보기. 남들에게 보이지 않게 본인에게만,
     * 실제로 쓰일 때와 같은 방법으로 만든 글자를 보낸다.
     */
    public void preview(Player player, Cosmetic cosmetic) {
        Messages msg = plugin.messages();
        String name = player.getName();
        String sample = Text.plain(msg.get("preview-chat-sample"));
        if (cosmetic instanceof TitleCosmetic title) {
            player.sendMessage(msg.get("preview-chat-format", "title", title.title(), "player", name, "message", sample));
        } else if (cosmetic instanceof ChatColorCosmetic color) {
            player.sendMessage(msg.get("preview-chat-format", "title", title(player), "player", name,
                    "message", color.apply(sample)));
        } else if (cosmetic instanceof KillMessageCosmetic kill) {
            player.sendMessage(kill.format(name, msg.get("preview-victim")));
        } else if (cosmetic instanceof JoinEffectCosmetic effect) {
            for (String line : new String[] {effect.joinMessage(name), effect.quitMessage(name)}) {
                if (line != null) {
                    player.sendMessage(line);
                }
            }
            play(player, effect, false);
        }
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
            event.setDeathMessage(message.format(killer.getName(), victim.getName()));
        }
    }
}
