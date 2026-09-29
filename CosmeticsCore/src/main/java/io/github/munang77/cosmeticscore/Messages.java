package io.github.munang77.cosmeticscore;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;

/** messages.yml. 파일에 없는 문구는 플러그인에 들어 있는 기본 문구를 쓴다. */
public final class Messages {

    private final YamlConfiguration yaml;
    private final String prefix;

    Messages(File file, InputStream defaults) {
        this.yaml = YamlConfiguration.loadConfiguration(file);
        if (defaults != null) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        this.prefix = Text.color(yaml.getString("prefix"));
    }

    /**
     * 색이 입혀지고 자리표시자가 채워진 문구. 기본값을 주는 {@code getString(path, def)} 은 플러그인 기본 문구를
     * 무시하므로 쓰지 않는다 (예전 messages.yml 에 새 문구가 없어도 기본 문구가 나와야 한다).
     */
    public String get(String path, String... pairs) {
        String raw = yaml.getString(path);
        return Text.replace(Text.color(raw == null ? path : raw), pairs);
    }

    public List<String> list(String path, String... pairs) {
        return Text.replace(Text.color(yaml.getStringList(path)), pairs);
    }

    /** 머리말을 붙여 보낸다. 문구를 비워 두면 보내지 않는다. */
    public void send(CommandSender to, String path, String... pairs) {
        String text = get(path, pairs);
        if (!text.isEmpty()) {
            to.sendMessage(prefix + text);
        }
    }

    /** 접속한 모든 플레이어에게 머리말을 붙여 보낸다. */
    public void broadcast(String path, String... pairs) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, path, pairs);
        }
    }

    public void sendList(CommandSender to, String path) {
        for (String line : list(path)) {
            to.sendMessage(line);
        }
    }

    /** 색이 입혀진 머리말. */
    public String prefix() {
        return prefix;
    }

    /** 카테고리 표시 이름 (색 없음). */
    public String category(Category category) {
        String raw = yaml.getString("categories." + category.key());
        return Text.plain(raw == null ? category.key() : raw);
    }
}
