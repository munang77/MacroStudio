package io.github.munang77.cosmeticscore.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.munang77.cosmeticscore.cosmetic.Category;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 플레이어 한 명의 저장 정보: 지급받은 코스메틱, 착용 중인 코스메틱, 보기 설정.
 * 채팅 이벤트처럼 비동기 스레드에서도 읽으므로 동시 접근에 안전한 자료구조를 쓴다.
 */
public final class PlayerData {

    private final UUID uuid;
    private volatile String lastName;
    private final Set<String> unlocked = ConcurrentHashMap.newKeySet();
    private final Map<Category, String> equipped = new ConcurrentHashMap<>();
    private volatile boolean showOthers = true;
    private volatile int keys;
    /** 파일을 못 읽었으면 저장하지 않는다 (빈 데이터로 원본을 덮어쓰지 않도록). */
    private final boolean readOnly;

    public PlayerData(UUID uuid) {
        this(uuid, false);
    }

    PlayerData(UUID uuid, boolean readOnly) {
        this.uuid = uuid;
        this.readOnly = readOnly;
    }

    public UUID uuid() {
        return uuid;
    }

    public String lastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public boolean readOnly() {
        return readOnly;
    }

    // ── 지급 ──

    public boolean hasUnlocked(String id) {
        return unlocked.contains(id);
    }

    /** @return 새로 지급됐으면 {@code true} */
    public boolean unlock(String id) {
        return unlocked.add(id);
    }

    /** @return 실제로 회수됐으면 {@code true} */
    public boolean lock(String id) {
        return unlocked.remove(id);
    }

    public Set<String> unlocked() {
        return Collections.unmodifiableSet(unlocked);
    }

    // ── 착용 ──

    /** @return 착용 중인 코스메틱 아이디, 없으면 {@code null} */
    public String equipped(Category category) {
        return equipped.get(category);
    }

    public void setEquipped(Category category, String id) {
        if (id == null) {
            equipped.remove(category);
        } else {
            equipped.put(category, id);
        }
    }

    public Map<Category, String> equippedView() {
        Map<Category, String> copy = new EnumMap<>(Category.class);
        copy.putAll(equipped);
        return copy;
    }

    // ── 설정 ──

    public boolean showOthers() {
        return showOthers;
    }

    public void setShowOthers(boolean showOthers) {
        this.showOthers = showOthers;
    }

    // ── 뽑기 열쇠 ──

    public int keys() {
        return keys;
    }

    /** 열쇠 수를 더한다 (음수면 뺀다). 0 아래로는 내려가지 않는다. */
    public synchronized int addKeys(int amount) {
        keys = (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) keys + amount));
        return keys;
    }

    /** @return 열쇠가 있어서 하나 썼으면 {@code true} */
    public synchronized boolean takeKey() {
        if (keys <= 0) {
            return false;
        }
        keys--;
        return true;
    }

    // ── 저장 형식 ──

    /** YAML 문자열로 만든다. 메인 스레드에서 스냅숏을 뜬 뒤 파일 쓰기는 다른 스레드에 맡긴다. */
    public String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", lastName);
        yaml.set("show-others", showOthers);
        yaml.set("keys", keys);
        List<String> ids = new ArrayList<>(unlocked);
        Collections.sort(ids);
        yaml.set("unlocked", ids);
        for (Category c : Category.values()) {
            String id = equipped.get(c);
            if (id != null) {
                yaml.set("equipped." + c.key(), id);
            }
        }
        return yaml.saveToString();
    }

    public static PlayerData deserialize(UUID uuid, String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        PlayerData data = new PlayerData(uuid);
        data.lastName = yaml.getString("name");
        data.showOthers = yaml.getBoolean("show-others", true);
        data.keys = Math.max(0, yaml.getInt("keys", 0));
        for (String id : yaml.getStringList("unlocked")) {
            data.unlocked.add(id.toLowerCase(java.util.Locale.ROOT));
        }
        ConfigurationSection eq = yaml.getConfigurationSection("equipped");
        if (eq != null) {
            for (Category c : Category.values()) {
                String id = eq.getString(c.key());
                if (id != null && !id.isBlank()) {
                    data.equipped.put(c, id.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return data;
    }
}
