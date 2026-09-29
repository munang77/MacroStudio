package io.github.munang77.cosmeticscore.hook;

import java.util.Locale;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.cosmetic.TitleCosmetic;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * 플레이스홀더 목록.
 * <ul>
 *   <li>{@code %cosmeticscore_title%} 칭호 (색 포함, 없으면 빈칸)</li>
 *   <li>{@code %cosmeticscore_title_space%} 칭호 + 띄어쓰기 (없으면 빈칸) — 이름 앞에 붙이기 좋음</li>
 *   <li>{@code %cosmeticscore_title_plain%} 색을 뺀 칭호</li>
 *   <li>{@code %cosmeticscore_equipped_<카테고리>%} 착용 중인 코스메틱 이름 (예: equipped_hat)</li>
 *   <li>{@code %cosmeticscore_equipped_<카테고리>_id%} 착용 중인 코스메틱 아이디</li>
 *   <li>{@code %cosmeticscore_owned%} / {@code %cosmeticscore_total%} 보유 수 / 전체 수</li>
 *   <li>{@code %cosmeticscore_keys%} 뽑기 열쇠 수</li>
 * </ul>
 */
public final class CosmeticsExpansion extends PlaceholderExpansion {

    private final CosmeticsCore plugin;

    CosmeticsExpansion(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "cosmeticscore";
    }

    @Override
    public String getAuthor() {
        return "munang77";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("total")) {
            return String.valueOf(plugin.registry().all().size());
        }
        Player player = offline == null ? null : offline.getPlayer();
        if (player == null) {
            return "";
        }
        switch (p) {
            case "title":
                return plugin.chat().title(player);
            case "title_space":
                return plugin.chat().titlePrefix(player);
            case "title_plain": {
                TitleCosmetic title = plugin.manager().equipped(player, Category.TITLE, TitleCosmetic.class);
                return title == null ? "" : title.plainTitle();
            }
            case "owned":
                return String.valueOf(plugin.manager().ownedCount(player, plugin.registry().all()));
            case "keys":
                return String.valueOf(plugin.manager().keys(player));
            default:
                break;
        }
        if (p.startsWith("equipped_")) {
            String rest = p.substring("equipped_".length());
            boolean wantId = rest.endsWith("_id");
            if (wantId) {
                rest = rest.substring(0, rest.length() - "_id".length());
            }
            Category category = Category.parse(rest, null);
            if (category == null) {
                return null;
            }
            Cosmetic cosmetic = plugin.manager().equipped(player, category);
            if (cosmetic == null) {
                return "";
            }
            return wantId ? cosmetic.id() : cosmetic.name();
        }
        return null;
    }
}
