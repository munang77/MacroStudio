package io.github.munang77.cosmeticscore.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** 한 사람에게만 보이는 엔티티 (옷장 마네킹, 버튼, 마네킹 장식). */
public final class Visibility {

    private Visibility() {
    }

    /** 기본으로 아무에게도 안 보이게 한다. 월드에 넣기 전(소환 준비 중)에 불러야 다른 사람에게 한 번도 보내지지 않는다. */
    public static void hideByDefault(Entity entity) {
        try {
            entity.setVisibleByDefault(false);
        } catch (RuntimeException e) {
            // 지원하지 않는 서버 구현 (테스트용 가짜 서버 등): revealOnlyTo 가 한 사람씩 숨긴다
        }
    }

    /** 소환한 뒤: 주인에게 보이게 하고, 기본으로 숨겨지지 않았으면 지금 접속한 다른 사람에게서 하나씩 숨긴다. */
    public static void revealOnlyTo(Plugin plugin, Player owner, Entity entity) {
        boolean hidden;
        try {
            hidden = !entity.isVisibleByDefault();
        } catch (RuntimeException e) {
            hidden = false;
        }
        if (!hidden) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.equals(owner)) {
                    player.hideEntity(plugin, entity);
                }
            }
        }
        owner.showEntity(plugin, entity);
    }
}
