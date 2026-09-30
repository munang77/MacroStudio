package io.github.munang77.cosmeticscore.wardrobe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.PurchaseConfirm;
import io.github.munang77.cosmeticscore.chat.ChatService;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.cosmetic.DisplayCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.HatCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.ParticleCosmetic;
import io.github.munang77.cosmeticscore.cosmetic.TitleCosmetic;
import io.github.munang77.cosmeticscore.display.DisplayService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;

/**
 * 한 플레이어의 옷장. 플레이어 앞에 본인에게만 보이는 마네킹을 세우고, 고른 코스메틱을 입혀 보여 준다.
 * 마네킹 주변의 떠 있는 버튼(우클릭)이나 마우스 휠로 고르고, 웅크리면 나간다.
 */
final class WardrobeSession {

    /** 떠 있는 버튼이 하는 일. */
    enum Action {
        PREV_ITEM, NEXT_ITEM, PREV_CATEGORY, NEXT_CATEGORY, EQUIP, ROTATE, EXIT
    }

    /** 버튼 하나: 누르는 판(Interaction)과 글자. */
    record Button(Action action, Interaction hitbox, TextDisplay label) {
    }

    private static final float TURN_PER_TICK = 1.5f;

    final Player player;
    final LivingEntity mannequin;
    /** 플레이어가 서 있는 자리 (움직이지 못하게 되돌리는 곳). */
    final Location view;
    /** 옷장 자리로 옮겨 왔다면 원래 자리 (그 자리에서 열었으면 {@code null}). */
    final Location origin;
    final List<Button> buttons = new ArrayList<>();
    /** 버튼·글자처럼 이 옷장이 소환한 것 (소환하자마자 적어 둬서, 중간에 실패해도 닫을 때 모두 지운다). */
    final List<Entity> spawned = new ArrayList<>();
    /** 마네킹 위 설명 (카테고리, 이름, 등급, 가진 상태). */
    TextDisplay info;
    final long openedAt;

    private final CosmeticsCore plugin;
    private final List<Category> categories;
    /** 마네킹에 입혀 보고 있는 것 (카테고리별, 없으면 비어 있음). */
    private final Map<Category, Cosmetic> selected = new EnumMap<>(Category.class);
    private int categoryIndex;
    float yaw;
    boolean rotating = true;
    private final PurchaseConfirm confirm = new PurchaseConfirm();
    private final Host host = new Host();

    WardrobeSession(CosmeticsCore plugin, Player player, LivingEntity mannequin, Location view, Location origin,
                    List<Category> categories, Category start, long now) {
        this.plugin = plugin;
        this.player = player;
        this.mannequin = mannequin;
        this.view = view;
        this.origin = origin;
        this.categories = categories;
        this.openedAt = now;
        this.yaw = mannequin.getLocation().getYaw();
        CosmeticManager manager = plugin.manager();
        for (Category c : categories) {
            Cosmetic worn = manager.equipped(player, c);
            if (worn != null) {
                selected.put(c, worn);
            }
        }
        int i = categories.indexOf(start);
        this.categoryIndex = Math.max(0, i);
    }

    DisplayService.Host host() {
        return host;
    }

    Category category() {
        return categories.get(categoryIndex);
    }

    Cosmetic current() {
        return selected.get(category());
    }

    /** 지금 카테고리에서 앞뒤 코스메틱으로 넘긴다 ("없음" 칸 포함). */
    void cycleItem(int delta) {
        Category category = category();
        List<Cosmetic> list = plugin.registry().of(category);
        int size = list.size() + 1;
        int index = list.indexOf(selected.get(category)) + 1;
        int next = Math.floorMod(index + delta, size);
        if (next == 0) {
            selected.remove(category);
        } else {
            selected.put(category, list.get(next - 1));
        }
        confirm.reset();
        applyLook(category);
    }

    void cycleCategory(int delta) {
        categoryIndex = Math.floorMod(categoryIndex + delta, categories.size());
        confirm.reset();
    }

    /** 고른 것을 실제로 입는다. 없으면 이 카테고리를 벗고, 안 가진 것이면 두 번 눌러 산다. */
    boolean equip() {
        CosmeticManager manager = plugin.manager();
        Cosmetic cosmetic = current();
        if (cosmetic == null) {
            return manager.unequip(player, category(), true);
        }
        if (manager.status(player, cosmetic) == CosmeticManager.Status.BUYABLE
                && (!confirm.confirm(plugin, player, cosmetic, "confirm-purchase-wardrobe")
                || !manager.purchase(player, cosmetic))) {
            return false;
        }
        // 잠긴 것은 equip 이 안내하고 거절한다
        return manager.equip(player, cosmetic);
    }

    // ── 모습 ─────────────────────────────────────

    /** 마네킹에 고른 것을 입힌다 (모자, 칭호 이름표). 몸 장식은 {@link DisplayService} 가 맞춘다. */
    void applyLook(Category changed) {
        if (changed == null || changed == Category.HAT) {
            EntityEquipment eq = mannequin.getEquipment();
            if (eq != null) {
                eq.setHelmet(selected.get(Category.HAT) instanceof HatCosmetic hat
                        ? plugin.hats().create(hat) : Mannequins.bareHead(mannequin, player));
            }
        }
        if (changed == null || changed == Category.TITLE) {
            String prefix = selected.get(Category.TITLE) instanceof TitleCosmetic title
                    ? ChatService.prefixOf(title.title()) : "";
            mannequin.setCustomName(prefix + player.getName());
            mannequin.setCustomNameVisible(true);
        }
        if (changed == null || changed.isDisplay()) {
            plugin.displays().refresh(host);
        }
    }

    /** 매 틱: 마네킹을 돌리고, 실제 파티클과 같은 간격으로 파티클을 뿌린다 (본인에게만). */
    void tick(long now) {
        if (rotating) {
            yaw = Location.normalizeYaw(yaw + TURN_PER_TICK);
        }
        Mannequins.face(mannequin, yaw);
        int interval = plugin.settings().particleInterval();
        if (selected.get(Category.PARTICLE) instanceof ParticleCosmetic particle && now % interval == 0) {
            int step = (int) (now / interval);
            if (particle.style().shouldRender(step)) {
                plugin.effects().drawParticles(particle, mannequin.getLocation(), yaw, step, rotating, false,
                        () -> List.of(player));
            }
        }
    }

    /** 버튼 글자와 마네킹 위 설명을 지금 상태로 맞춘다. */
    void render() {
        Messages msg = plugin.messages();
        Category category = category();
        List<Cosmetic> list = plugin.registry().of(category);
        Cosmetic cosmetic = current();
        String position = (list.indexOf(cosmetic) + 1) + "/" + list.size();
        String header = msg.get("wardrobe.header", "category", msg.category(category), "position", position);
        String info;
        String equip;
        if (cosmetic == null) {
            info = msg.get("wardrobe.info-none");
            equip = msg.get("wardrobe.button.take-off");
        } else {
            String price = plugin.economy().format(cosmetic.price());
            CosmeticManager.Status status = plugin.manager().status(player, cosmetic);
            String state = switch (status) {
                case EQUIPPED -> msg.get("wardrobe.status.equipped");
                case OWNED -> msg.get("wardrobe.status.owned");
                case BUYABLE -> msg.get("wardrobe.status.price", "price", price);
                case LOCKED -> msg.get("wardrobe.status.locked");
            };
            info = msg.get("wardrobe.info", "name", cosmetic.name(),
                    "rarity", plugin.settings().rarity(cosmetic.rarity()).name(), "status", state);
            equip = switch (status) {
                case EQUIPPED -> msg.get("wardrobe.button.wearing");
                case OWNED -> msg.get("wardrobe.button.equip");
                case BUYABLE -> msg.get("wardrobe.button.buy", "price", price);
                case LOCKED -> msg.get("wardrobe.button.locked");
            };
        }
        if (this.info != null) {
            this.info.setText(header + "\n" + info);
        }
        for (Button button : buttons) {
            if (button.action() == Action.EQUIP) {
                button.label().setText(equip);
            } else if (button.action() == Action.ROTATE) {
                button.label().setText(msg.get(rotating ? "wardrobe.button.rotate-on" : "wardrobe.button.rotate-off"));
            }
        }
    }

    /** 누른 엔티티가 이 옷장의 버튼이면 그 버튼. */
    Button button(Entity clicked) {
        for (Button button : buttons) {
            if (button.hitbox().equals(clicked)) {
                return button;
            }
        }
        return null;
    }

    /** 이 옷장이 소환한 것을 모두 지운다. */
    void removeEntities() {
        spawned.forEach(Entity::remove);
        mannequin.remove();
    }

    /** 마네킹에 입히는 장식 (본인에게만 보인다). */
    private final class Host implements DisplayService.Host {

        @Override
        public UUID id() {
            return mannequin.getUniqueId();
        }

        @Override
        public Entity entity() {
            return mannequin;
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public DisplayCosmetic equipped(Category category) {
            return selected.get(category) instanceof DisplayCosmetic d ? d : null;
        }

        @Override
        public boolean hidden() {
            return false;
        }

        @Override
        public boolean sneaking() {
            return false;
        }

        @Override
        public boolean lying() {
            return false;
        }

        @Override
        public float bodyYaw() {
            return yaw;
        }

        @Override
        public Player onlyFor() {
            return player;
        }
    }
}
