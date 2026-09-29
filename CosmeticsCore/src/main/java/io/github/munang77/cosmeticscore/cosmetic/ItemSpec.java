package io.github.munang77.cosmeticscore.cosmetic;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.munang77.cosmeticscore.util.ItemBuilder;
import io.github.munang77.cosmeticscore.util.Materials;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/**
 * 설정에서 읽은 아이템 모양: 아이템 종류, 리소스팩 모델, 머리 텍스처.
 * 모자, 백팩, 풍선, 펫, 메뉴 아이콘이 모두 이걸로 만든다.
 */
public final class ItemSpec {

    /** 설정 키 이름 묶음. */
    public enum Keys {
        /** material, custom-model-data, item-model, texture */
        ITEM("material", "custom-model-data", "item-model", "texture"),
        /** icon, icon-model-data, icon-item-model, icon-texture */
        ICON("icon", "icon-model-data", "icon-item-model", "icon-texture");

        final String material;
        final String modelData;
        final String itemModel;
        final String texture;

        Keys(String material, String modelData, String itemModel, String texture) {
            this.material = material;
            this.modelData = modelData;
            this.itemModel = itemModel;
            this.texture = texture;
        }
    }

    private static final String TEXTURE_HOST = "textures.minecraft.net";
    private static final Pattern TEXTURE_HASH = Pattern.compile("[0-9a-fA-F]{32,128}");
    private static final Pattern URL_IN_JSON = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private final Material material;
    private final Integer modelData;
    private final NamespacedKey itemModel;
    private final String textureUrl;
    private ItemStack base;

    public ItemSpec(Material material, Integer modelData, NamespacedKey itemModel, String textureUrl) {
        this.material = material;
        this.modelData = modelData;
        this.itemModel = itemModel;
        this.textureUrl = textureUrl;
    }

    public static ItemSpec of(Material material) {
        return new ItemSpec(material, null, null, null);
    }

    /**
     * 아이템 설정을 읽는다. 텍스처만 적으면 아이템은 자동으로 플레이어 머리가 된다.
     *
     * @return 아무것도 적혀 있지 않으면 {@code null}
     */
    public static ItemSpec parse(ConfigurationSection s, Keys keys) {
        String texture = s.getString(keys.texture);
        String textureUrl = texture == null || texture.isBlank() ? null : textureUrl(texture.trim());
        String name = s.getString(keys.material);
        Material material;
        if (name != null && !name.isBlank()) {
            material = Materials.item(name);
            if (material == null) {
                throw new IllegalArgumentException(keys.material + " 에 쓸 수 없는 아이템입니다: " + name);
            }
        } else if (textureUrl != null) {
            material = Material.PLAYER_HEAD;
        } else {
            return null;
        }
        if (textureUrl != null && material != Material.PLAYER_HEAD) {
            throw new IllegalArgumentException(keys.texture + " 는 PLAYER_HEAD 에만 쓸 수 있습니다");
        }
        Integer modelData = s.contains(keys.modelData) ? Integer.valueOf(s.getInt(keys.modelData)) : null;
        if (modelData != null && modelData == 0) {
            modelData = null;
        }
        NamespacedKey itemModel = null;
        String model = s.getString(keys.itemModel);
        if (model != null && !model.isBlank()) {
            itemModel = NamespacedKey.fromString(model.trim().toLowerCase(Locale.ROOT));
            if (itemModel == null) {
                throw new IllegalArgumentException(keys.itemModel + " 형식이 잘못됐습니다 (예: myserver:crown): " + model);
            }
        }
        return new ItemSpec(material, modelData, itemModel, textureUrl);
    }

    /**
     * 머리 텍스처 값을 textures.minecraft.net 주소로 바꾼다. 주소, 해시, base64(Value) 를 모두 받는다.
     *
     * @throws IllegalArgumentException 알아볼 수 없을 때
     */
    static String textureUrl(String value) {
        String url;
        if (TEXTURE_HASH.matcher(value).matches()) {
            url = "http://" + TEXTURE_HOST + "/texture/" + value.toLowerCase(Locale.ROOT);
        } else if (value.startsWith("http://") || value.startsWith("https://")) {
            url = value;
        } else {
            String json;
            try {
                json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("texture 를 알아볼 수 없습니다 (주소, 해시, base64 값 중 하나): " + shorten(value));
            }
            Matcher m = URL_IN_JSON.matcher(json);
            if (!m.find()) {
                throw new IllegalArgumentException("texture base64 값 안에 주소가 없습니다");
            }
            url = m.group(1);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("texture 주소 형식이 잘못됐습니다: " + shorten(url));
        }
        if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("texture 는 " + TEXTURE_HOST + " 주소만 쓸 수 있습니다: " + shorten(url));
        }
        return "http://" + TEXTURE_HOST + uri.getPath();
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    public Material material() {
        return material;
    }

    /** 이 모양대로 빌더를 시작한다. 이름/설명은 호출한 쪽에서 붙인다. */
    public ItemBuilder builder(Logger log) {
        return new ItemBuilder(base(log).clone());
    }

    /** 이 모양의 아이템 (새 복사본). */
    public ItemStack create(Logger log) {
        return base(log).clone();
    }

    /**
     * 모델·텍스처까지 입힌 기본 아이템. 매 틱 바뀌는 풍선이나 메뉴 아이콘이 매번 머리 프로필을 새로 만들지 않도록
     * 한 번만 만들어 둔다 (메인 스레드에서만 쓴다).
     */
    private ItemStack base(Logger log) {
        if (base == null) {
            ItemBuilder builder = new ItemBuilder(material).modelData(modelData).itemModel(itemModel, log);
            if (textureUrl != null && builder.meta() instanceof SkullMeta skull) {
                applyTexture(skull, log);
            }
            base = builder.build();
        }
        return base;
    }

    @SuppressWarnings("deprecation")
    private void applyTexture(SkullMeta skull, Logger log) {
        try {
            UUID id = UUID.nameUUIDFromBytes(textureUrl.getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createPlayerProfile(id, "cosmetic");
            PlayerTextures textures = profile.getTextures();
            URL url = URI.create(textureUrl).toURL();
            textures.setSkin(url);
            profile.setTextures(textures);
            skull.setOwnerProfile(profile);
        } catch (Exception | LinkageError e) {
            log.log(Level.WARNING, "머리 텍스처를 적용하지 못했습니다: " + textureUrl, e);
        }
    }
}
