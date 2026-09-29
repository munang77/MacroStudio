package io.github.munang77.cosmeticscore.cosmetic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import io.github.munang77.cosmeticscore.cosmetic.Cosmetic.Info;
import io.github.munang77.cosmeticscore.util.Colors;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * cosmetics.yml 에서 읽은 코스메틱 목록. 다시 불러올 때 표를 통째로 바꿔 끼우므로
 * 다른 스레드에서 읽어도 반쯤 바뀐 상태를 보지 않는다.
 */
public final class CosmeticRegistry {

    public static final String PERMISSION_PREFIX = "cosmeticscore.cosmetic.";
    public static final String DEFAULT_RARITY = "common";
    private static final Pattern ID = Pattern.compile("[a-z0-9_\\-]+");

    private volatile Map<String, Cosmetic> byId = Map.of();
    private volatile Map<Category, List<Cosmetic>> byCategory = emptyCategories();

    /**
     * 설정을 읽어 목록을 바꾼다. 잘못된 항목은 경고만 남기고 건너뛴다.
     *
     * @return 읽어 들인 코스메틱 수
     */
    public int load(ConfigurationSection root, Logger log) {
        Map<String, Cosmetic> ids = new LinkedHashMap<>();
        Map<Category, List<Cosmetic>> cats = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            List<Cosmetic> list = new ArrayList<>();
            ConfigurationSection section = root.getConfigurationSection(category.section());
            if (section != null) {
                for (String rawId : section.getKeys(false)) {
                    String where = category.section() + "." + rawId;
                    String id = rawId.toLowerCase(Locale.ROOT);
                    ConfigurationSection s = section.getConfigurationSection(rawId);
                    if (s == null) {
                        log.warning("[cosmetics.yml] " + where + ": 항목 아래에 설정이 없습니다");
                        continue;
                    }
                    if (!ID.matcher(id).matches()) {
                        log.warning("[cosmetics.yml] " + where + ": 아이디는 영문 소문자, 숫자, _, - 만 쓸 수 있습니다");
                        continue;
                    }
                    if (ids.containsKey(id)) {
                        log.warning("[cosmetics.yml] " + where + ": 아이디 '" + id + "' 가 이미 있어서 건너뜁니다");
                        continue;
                    }
                    try {
                        Cosmetic cosmetic = parse(category, id, s);
                        ids.put(id, cosmetic);
                        list.add(cosmetic);
                    } catch (IllegalArgumentException e) {
                        log.warning("[cosmetics.yml] " + where + ": " + e.getMessage());
                    }
                }
            }
            cats.put(category, Collections.unmodifiableList(list));
        }
        byCategory = Collections.unmodifiableMap(cats);
        byId = Collections.unmodifiableMap(ids);
        return ids.size();
    }

    /** @return 없으면 {@code null} */
    public Cosmetic get(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Cosmetic> of(Category category) {
        return byCategory.get(category);
    }

    public Collection<Cosmetic> all() {
        return byId.values();
    }

    private static Map<Category, List<Cosmetic>> emptyCategories() {
        Map<Category, List<Cosmetic>> m = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            m.put(c, List.of());
        }
        return Collections.unmodifiableMap(m);
    }

    // ── 항목별 읽기 ──────────────────────────────

    private static Cosmetic parse(Category category, String id, ConfigurationSection s) {
        return switch (category) {
            case HAT -> {
                ItemSpec item = ItemSpec.parse(s, ItemSpec.Keys.ITEM, null);
                if (item == null) {
                    throw new IllegalArgumentException("material 또는 texture 항목이 없습니다");
                }
                yield new HatCosmetic(info(id, s, category, item, id), item);
            }
            case BACKPACK, BALLOON, PET -> parseDisplay(category, id, s);
            case PARTICLE -> new ParticleCosmetic(info(id, s, category, null, id), ParticleSpec.parse(s), style(s));
            case ARROW_TRAIL -> new ArrowTrailCosmetic(info(id, s, category, null, id), ParticleSpec.parse(s),
                    clamp(s.getInt("amount", 1), 1, 10));
            case KILL_EFFECT -> parseKillEffect(id, s);
            case KILL_MESSAGE -> new KillMessageCosmetic(info(id, s, category, null, id),
                    Text.color(required(s, "message")));
            case TITLE -> {
                String title = required(s, "title");
                yield new TitleCosmetic(info(id, s, category, null, title), Text.color(title));
            }
            case CHAT_COLOR -> parseChatColor(id, s);
            case JOIN_EFFECT -> parseJoinEffect(id, s);
        };
    }

    private static DisplayCosmetic parseDisplay(Category category, String id, ConfigurationSection s) {
        ItemSpec item = ItemSpec.parse(s, ItemSpec.Keys.ITEM, null);
        List<ItemSpec> cycle = new ArrayList<>();
        for (String name : s.getStringList("cycle")) {
            Material m = Material.matchMaterial(name.trim());
            if (m == null || m.isAir() || !m.isItem()) {
                throw new IllegalArgumentException("cycle 에 쓸 수 없는 아이템입니다: " + name);
            }
            cycle.add(ItemSpec.of(m));
        }
        if (item == null) {
            if (cycle.isEmpty()) {
                throw new IllegalArgumentException("material, texture, cycle 중 하나는 있어야 합니다");
            }
            item = cycle.get(0);
        }
        float defaultScale = switch (category) {
            case BACKPACK -> 0.6f;
            case BALLOON -> 0.7f;
            default -> 0.5f;
        };
        float scale = (float) Math.max(0.05, Math.min(4.0, s.getDouble("scale", defaultScale)));
        String nameTag = s.getString("name-tag");
        nameTag = nameTag == null || nameTag.isBlank() ? null : Text.color(nameTag);
        String string = null;
        if (category == Category.BALLOON) {
            string = s.getString("string", "WHITE_WOOL");
            if (string.isBlank() || string.equalsIgnoreCase("none")) {
                string = null;
            } else {
                Material m = Material.matchMaterial(string.trim());
                if (m == null || !m.isBlock()) {
                    throw new IllegalArgumentException("string 에 쓸 수 없는 블록입니다: " + string);
                }
                string = m.name();
            }
        }
        return new DisplayCosmetic(info(id, s, category, item, id), category, item, cycle,
                clamp(s.getInt("cycle-ticks", 10), 1, 1200), scale, s.getDouble("offset-y", 0), nameTag, string);
    }

    private static ParticleStyle style(ConfigurationSection s) {
        String styleName = s.getString("style", "AURA");
        try {
            return ParticleStyle.valueOf(styleName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("없는 style 입니다: " + styleName + " (" + names(ParticleStyle.values()) + ")");
        }
    }

    private static KillEffectCosmetic parseKillEffect(String id, ConfigurationSection s) {
        String effectName = s.getString("effect", "BURST");
        KillEffectCosmetic.Effect effect;
        try {
            effect = KillEffectCosmetic.Effect.valueOf(effectName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("없는 effect 입니다: " + effectName + " (LIGHTNING, FIREWORK, BURST)");
        }
        FireworkEffect firework = effect == KillEffectCosmetic.Effect.FIREWORK ? firework(s) : null;
        ParticleSpec burst = effect == KillEffectCosmetic.Effect.BURST ? ParticleSpec.parse(s) : null;
        return new KillEffectCosmetic(info(id, s, Category.KILL_EFFECT, null, id), effect, firework, burst,
                clamp(s.getInt("amount", 30), 1, 500), Math.max(0, s.getDouble("spread", 0.5)),
                Math.max(0, s.getDouble("speed", 0.05)), sound(s), (float) s.getDouble("volume", 1.0),
                (float) s.getDouble("pitch", 1.0));
    }

    private static ChatColorCosmetic parseChatColor(String id, ConfigurationSection s) {
        StringBuilder format = new StringBuilder();
        if (s.getBoolean("bold", false)) {
            format.append("§l");
        }
        if (s.getBoolean("italic", false)) {
            format.append("§o");
        }
        int[] stops = null;
        String prefix = null;
        if (s.getBoolean("rainbow", false)) {
            stops = Colors.rainbow(7);
        } else if (!s.getStringList("gradient").isEmpty()) {
            List<String> raw = s.getStringList("gradient");
            stops = new int[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                stops[i] = Colors.parseRgb(raw.get(i));
            }
        } else {
            prefix = Text.color(required(s, "color")) + format;
        }
        return new ChatColorCosmetic(info(id, s, Category.CHAT_COLOR, null, id), prefix, stops, format.toString());
    }

    private static JoinEffectCosmetic parseJoinEffect(String id, ConfigurationSection s) {
        String join = s.getString("message");
        String quit = s.getString("quit-message");
        FireworkEffect firework = s.contains("colors") || s.contains("firework-type") ? firework(s) : null;
        return new JoinEffectCosmetic(info(id, s, Category.JOIN_EFFECT, null, id),
                join == null || join.isBlank() ? null : Text.color(join),
                quit == null || quit.isBlank() ? null : Text.color(quit),
                sound(s), (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0), firework);
    }

    // ── 공통 ─────────────────────────────────────

    private static Info info(String id, ConfigurationSection s, Category category, ItemSpec fallbackIcon,
                             String defaultName) {
        ItemSpec icon = ItemSpec.parse(s, ItemSpec.Keys.ICON, null);
        if (icon == null) {
            icon = fallbackIcon != null ? fallbackIcon : ItemSpec.of(category.defaultIcon());
        }
        String name = Text.color(s.getString("name", defaultName));
        List<String> lore = Text.color(s.getStringList("lore"));
        boolean free = s.getBoolean("free", false);
        String permission = s.getString("permission", PERMISSION_PREFIX + id);
        String rarity = s.getString("rarity", DEFAULT_RARITY).trim().toLowerCase(Locale.ROOT);
        double price = Math.max(0, s.getDouble("price", 0));
        boolean crate = s.getBoolean("crate", !free);
        return new Info(id, name, lore, icon, free, permission, rarity, price, crate);
    }

    private static String required(ConfigurationSection s, String key) {
        String value = s.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " 항목이 없습니다");
        }
        return value;
    }

    private static String sound(ConfigurationSection s) {
        String sound = s.getString("sound");
        return sound == null || sound.isBlank() ? null : sound.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String names(Enum<?>[] values) {
        StringBuilder sb = new StringBuilder();
        for (Enum<?> v : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(v.name());
        }
        return sb.toString();
    }

    private static FireworkEffect firework(ConfigurationSection s) {
        String typeName = s.getString("firework-type", "BALL_LARGE");
        FireworkEffect.Type type;
        try {
            type = FireworkEffect.Type.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("없는 firework-type 입니다: " + typeName
                    + " (BALL, BALL_LARGE, STAR, BURST, CREEPER)");
        }
        List<Color> colors = colors(s.getStringList("colors"));
        if (colors.isEmpty()) {
            colors = List.of(Color.RED, Color.YELLOW);
        }
        return FireworkEffect.builder()
                .with(type)
                .withColor(colors)
                .withFade(colors(s.getStringList("fade-colors")))
                .flicker(s.getBoolean("flicker", false))
                .trail(s.getBoolean("trail", false))
                .build();
    }

    private static List<Color> colors(List<String> raw) {
        List<Color> out = new ArrayList<>(raw.size());
        for (String c : raw) {
            out.add(Color.fromRGB(Colors.parseRgb(c)));
        }
        return out;
    }
}
