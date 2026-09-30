package io.github.munang77.cosmeticscore.cosmetic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.munang77.cosmeticscore.cosmetic.Cosmetic.Info;
import io.github.munang77.cosmeticscore.util.Colors;
import io.github.munang77.cosmeticscore.util.Materials;
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
                ItemSpec item = ItemSpec.parse(s, ItemSpec.Keys.ITEM);
                if (item == null) {
                    throw new IllegalArgumentException("material 또는 texture 항목이 없습니다");
                }
                yield new HatCosmetic(info(id, s, category, item, id), item);
            }
            case BACKPACK, WINGS, TAIL, WAIST, TORSO, BALLOON, PET -> parseDisplay(category, id, s);
            case PARTICLE -> new ParticleCosmetic(info(id, s, category, null, id), ParticleSpec.parse(s),
                    enumValue(ParticleStyle.class, s, "style", "AURA"));
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
        ItemSpec item = ItemSpec.parse(s, ItemSpec.Keys.ITEM);
        List<ItemSpec> cycle = new ArrayList<>();
        for (String name : s.getStringList("cycle")) {
            Material m = Materials.item(name);
            if (m == null) {
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
        float scale = (float) Math.clamp(s.getDouble("scale", Attachment.slot(category).scale()), 0.05, 4.0);
        // 이름표는 펫에만, 줄은 풍선에만 있다
        String nameTag = category == Category.PET ? s.getString("name-tag") : null;
        nameTag = nameTag == null || nameTag.isBlank() ? null : Text.color(nameTag);
        Material string = null;
        if (category == Category.BALLOON) {
            String raw = s.getString("string", "WHITE_WOOL");
            if (!raw.isBlank() && !raw.equalsIgnoreCase("none")) {
                string = Materials.block(raw);
                if (string == null) {
                    throw new IllegalArgumentException("string 에 쓸 수 없는 블록입니다: " + raw);
                }
            }
        }
        return new DisplayCosmetic(info(id, s, category, item, id), category, item, cycle,
                clamp(s.getInt("cycle-ticks", 10), 1, 1200), scale, attachment(category, s), nameTag, string);
    }

    /** 붙는 자리와 움직임. 적지 않은 값은 카테고리 기본값 (날개는 퍼덕이는 한 쌍, 꼬리는 살랑임). */
    static Attachment attachment(Category category, ConfigurationSection s) {
        Attachment def = Attachment.slot(category).defaults();
        // offset-y 는 예전 설정과 맞추려고 남겨 둔 줄임말
        double offsetY = Math.clamp(s.getDouble("offset-y", 0), -5, 5);
        Attachment.Vec3 offset = vec3(s, "offset", def.offset(), 5).plus(new Attachment.Vec3(0, offsetY, 0));
        return new Attachment(offset, vec3(s, "rotation", def.rotation(), 360), vec3(s, "pivot", def.pivot(), 5),
                s.getBoolean("mirror", def.mirror()), enumValue(Motion.class, s, "animation", def.motion().name()),
                Math.clamp(s.getDouble("animation-speed", def.speed()), 0, 10),
                Math.clamp(s.getDouble("animation-angle", def.angle()), 0, 180),
                Math.clamp(s.getDouble("spread", def.spread()), -90, 90),
                Math.clamp(s.getDouble("animation-height", def.height()), 0, 2));
    }

    /** {@code [x, y, z]} 세 숫자 (각각 절댓값 {@code max} 이하). */
    private static Attachment.Vec3 vec3(ConfigurationSection s, String key, Attachment.Vec3 def, double max) {
        if (!s.contains(key)) {
            return def;
        }
        List<Double> v = s.getDoubleList(key);
        if (v.size() != 3) {
            throw new IllegalArgumentException(key + " 는 [x, y, z] 세 숫자로 적어야 합니다");
        }
        for (double d : v) {
            if (!Double.isFinite(d) || Math.abs(d) > max) {
                throw new IllegalArgumentException(key + " 값은 -" + (int) max + " ~ " + (int) max + " 사이여야 합니다: " + v);
            }
        }
        return new Attachment.Vec3(v.get(0), v.get(1), v.get(2));
    }

    /** 대소문자와 앞뒤 공백을 무시하고 enum 값을 읽는다. 없으면 쓸 수 있는 값을 모두 알려 준다. */
    private static <E extends Enum<E>> E enumValue(Class<E> type, ConfigurationSection s, String key, String def) {
        String raw = s.getString(key, def);
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String names = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("없는 " + key + " 입니다: " + raw + " (" + names + ")");
        }
    }

    private static KillEffectCosmetic parseKillEffect(String id, ConfigurationSection s) {
        KillEffectCosmetic.Effect effect = enumValue(KillEffectCosmetic.Effect.class, s, "effect", "BURST");
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
        ItemSpec icon = ItemSpec.parse(s, ItemSpec.Keys.ICON);
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

    private static FireworkEffect firework(ConfigurationSection s) {
        FireworkEffect.Type type = enumValue(FireworkEffect.Type.class, s, "firework-type", "BALL_LARGE");
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
