package io.github.munang77.cosmeticscore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;

import io.github.munang77.cosmeticscore.chat.ChatService;
import io.github.munang77.cosmeticscore.command.CosmeticsCommand;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.cosmetic.CosmeticRegistry;
import io.github.munang77.cosmeticscore.crate.CrateService;
import io.github.munang77.cosmeticscore.data.DataStore;
import io.github.munang77.cosmeticscore.data.SqlStorage;
import io.github.munang77.cosmeticscore.data.Storage;
import io.github.munang77.cosmeticscore.data.YamlStorage;
import io.github.munang77.cosmeticscore.display.DisplayService;
import io.github.munang77.cosmeticscore.effect.EffectService;
import io.github.munang77.cosmeticscore.gui.Menu;
import io.github.munang77.cosmeticscore.gui.MenuListener;
import io.github.munang77.cosmeticscore.hat.HatService;
import io.github.munang77.cosmeticscore.hook.EconomyHook;
import io.github.munang77.cosmeticscore.hook.PlaceholderHook;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** CosmeticsCore 본체. 다른 플러그인은 {@code CosmeticsCore.getPlugin(CosmeticsCore.class)} 로 접근한다. */
public class CosmeticsCore extends JavaPlugin {

    private final CosmeticRegistry registry = new CosmeticRegistry();
    private volatile Settings settings;
    private volatile Messages messages;
    private DataStore store;
    private CosmeticManager manager;
    private HatService hats;
    private ChatService chat;
    private EffectService effects;
    private DisplayService displays;
    private EconomyHook economy;
    private CrateService crates;
    private BukkitTask previewTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveIfMissing("cosmetics.yml");
        saveIfMissing("messages.yml");

        Storage storage = createStorage();
        // MySQL 을 서버 여러 대가 같이 쓰면, 서버를 옮길 때 이전 서버의 저장이 먼저 끝나도록 잠깐 기다렸다가 읽는다
        long loginDelay = getConfig().getLong("storage.login-delay-ms",
                storage instanceof SqlStorage sql && sql.shared() ? 300 : 0);
        store = new DataStore(storage, getLogger(), loginDelay);
        manager = new CosmeticManager(this);
        hats = new HatService(this);
        chat = new ChatService(this);
        effects = new EffectService(this);
        displays = new DisplayService(this);
        economy = new EconomyHook(getLogger());
        crates = new CrateService(this);
        int count = loadFiles();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(hats, this);
        pm.registerEvents(chat, this);
        pm.registerEvents(effects, this);
        pm.registerEvents(displays, this);
        pm.registerEvents(new MenuListener(), this);

        PluginCommand command = getCommand("cosmetics");
        if (command != null) {
            CosmeticsCommand executor = new CosmeticsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        effects.start();
        hats.start();
        displays.start();
        previewTask = getServer().getScheduler().runTaskTimer(this, manager::tickPreviews, 20L, 20L);
        // /reload 등으로 켜졌을 때 이미 접속해 있는 플레이어
        for (Player player : getServer().getOnlinePlayers()) {
            manager.handleJoin(player);
        }

        if (pm.getPlugin("PlaceholderAPI") != null) {
            try {
                if (PlaceholderHook.register(this)) {
                    getLogger().info("PlaceholderAPI 연동 완료 (%cosmeticscore_title% 등)");
                }
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "PlaceholderAPI 연동에 실패했습니다.", t);
            }
        }
        getLogger().info("코스메틱 " + Math.max(count, 0) + "개, 저장소: " + store.storage().describe());
    }

    @Override
    public void onDisable() {
        if (previewTask != null) {
            previewTask.cancel();
        }
        if (manager != null) {
            manager.clearPreviews();
        }
        if (effects != null) {
            effects.stop();
        }
        if (hats != null) {
            hats.stop();
        }
        if (displays != null) {
            displays.stop();
        }
        for (Player player : getServer().getOnlinePlayers()) {
            // 플러그인이 꺼지면 메뉴 클릭을 막을 수 없으므로 열린 메뉴를 닫는다
            org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Menu) {
                player.closeInventory();
            }
            if (hats != null) {
                hats.strip(player);
            }
            if (chat != null) {
                chat.resetTab(player);
            }
        }
        if (store != null) {
            store.shutdown();
        }
    }

    /**
     * 설정 파일을 다시 읽고 접속 중인 플레이어에게 반영한다. 저장소 종류는 재시작해야 바뀐다.
     *
     * @return 불러온 코스메틱 수 (cosmetics.yml 형식이 깨졌으면 이전 목록을 유지하고 -1)
     */
    public int reload() {
        int count = loadFiles();
        effects.start();
        for (Player player : getServer().getOnlinePlayers()) {
            manager.validate(player);
            manager.applyVisuals(player);
        }
        return count;
    }

    private int loadFiles() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        messages = new Messages(new File(getDataFolder(), "messages.yml"), getResource("messages.yml"));

        File file = new File(getDataFolder(), "cosmetics.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "cosmetics.yml 을 읽지 못했습니다. 이전 목록을 그대로 씁니다.", e);
            return -1;
        }
        int count = registry.load(yaml, getLogger());
        for (Cosmetic cosmetic : registry.all()) {
            if (!settings.hasRarity(cosmetic.rarity())) {
                getLogger().warning("[cosmetics.yml] " + cosmetic.id() + ": 없는 등급 '" + cosmetic.rarity()
                        + "' 이라 common 으로 봅니다 (config.yml 의 rarities 확인)");
            }
        }
        return count;
    }

    private Storage createStorage() {
        ConfigurationSection s = getConfig().getConfigurationSection("storage");
        Path folder = getDataFolder().toPath();
        String type = s == null ? "YAML" : s.getString("type", "YAML").trim().toUpperCase(Locale.ROOT);
        String table = s == null ? "cosmeticscore_players" : s.getString("table", "cosmeticscore_players");
        try {
            SqlStorage sql;
            switch (type) {
                case "SQLITE" -> sql = SqlStorage.sqlite(folder.resolve("data.db"), table, folder.resolve("broken"), getLogger());
                case "MYSQL", "MARIADB" -> sql = SqlStorage.mysql(
                        s.getString("mysql.host", "localhost"), s.getInt("mysql.port", 3306),
                        s.getString("mysql.database", "minecraft"), s.getString("mysql.username", "root"),
                        s.getString("mysql.password", ""), s.getString("mysql.properties", ""),
                        table, folder.resolve("broken"), getLogger());
                default -> {
                    if (!type.equals("YAML")) {
                        getLogger().warning("storage.type '" + type + "' 을(를) 몰라서 YAML 을 씁니다.");
                    }
                    return new YamlStorage(folder.resolve("data"), getLogger());
                }
            }
            sql.open();
            return sql;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, type + " 저장소에 연결하지 못해 YAML 파일로 저장합니다. 설정을 확인하세요.", e);
            return new YamlStorage(folder.resolve("data"), getLogger());
        }
    }

    private void saveIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public CosmeticRegistry registry() {
        return registry;
    }

    public DataStore store() {
        return store;
    }

    public CosmeticManager manager() {
        return manager;
    }

    public HatService hats() {
        return hats;
    }

    public ChatService chat() {
        return chat;
    }

    public EffectService effects() {
        return effects;
    }

    public DisplayService displays() {
        return displays;
    }

    public EconomyHook economy() {
        return economy;
    }

    public CrateService crates() {
        return crates;
    }
}
