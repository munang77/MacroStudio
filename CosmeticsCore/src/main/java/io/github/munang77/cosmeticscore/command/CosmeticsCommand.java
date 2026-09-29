package io.github.munang77.cosmeticscore.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.github.munang77.cosmeticscore.CosmeticManager;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Messages;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.data.PlayerData;
import io.github.munang77.cosmeticscore.data.YamlStorage;
import io.github.munang77.cosmeticscore.gui.CategoryMenu;
import io.github.munang77.cosmeticscore.gui.CrateMenu;
import io.github.munang77.cosmeticscore.gui.MainMenu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /cos} 명령어. 하위 명령은 영어와 한글 둘 다 받는다.
 * 지급/회수/열쇠는 접속하지 않은 플레이어에게도 된다 (상점 연동용).
 */
public final class CosmeticsCommand implements TabExecutor {

    private static final String USE = "cosmeticscore.use";
    private static final String ADMIN = "cosmeticscore.admin";

    private enum Sub {
        HELP(false, "help", "도움말", "?"),
        MENU(false, "menu", "메뉴", "open", "열기"),
        EQUIP(false, "equip", "착용", "wear"),
        UNEQUIP(false, "unequip", "해제", "remove", "off"),
        LIST(false, "list", "목록"),
        TOGGLE(false, "toggle", "보기"),
        BUY(false, "buy", "구매"),
        PREVIEW(false, "preview", "미리보기"),
        CRATE(false, "crate", "뽑기"),
        KEYS(false, "keys", "열쇠"),
        GIVE(true, "give", "지급"),
        TAKE(true, "take", "회수"),
        RELOAD(true, "reload", "리로드"),
        MIGRATE(true, "migrate", "이전");

        final boolean admin;
        final String[] names;

        Sub(boolean admin, String... names) {
            this.admin = admin;
            this.names = names;
        }

        static Sub find(String input) {
            String s = input.toLowerCase(Locale.ROOT);
            for (Sub sub : values()) {
                for (String name : sub.names) {
                    if (name.equals(s)) {
                        return sub;
                    }
                }
            }
            return null;
        }
    }

    private static final List<String> ALL_WORDS = List.of("all", "전체");

    private final CosmeticsCore plugin;

    public CosmeticsCommand(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.messages();
        if (!sender.hasPermission(USE) && !sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new MainMenu(plugin, player).open();
            } else {
                help(sender);
            }
            return true;
        }

        Sub sub = Sub.find(args[0]);
        if (sub == null) {
            Category category = Category.parse(join(args, 0), msg::category);
            if (category != null && sender instanceof Player player) {
                new CategoryMenu(plugin, player, category, 0).open();
            } else {
                help(sender);
            }
            return true;
        }
        if (sub.admin && !sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return true;
        }

        switch (sub) {
            case HELP -> help(sender);
            case MENU -> ifPlayer(sender, player -> new MainMenu(plugin, player).open());
            case EQUIP -> withCosmetic(sender, args, (player, c) -> plugin.manager().equip(player, c));
            case UNEQUIP -> unequip(sender, args);
            case LIST -> list(sender, args);
            case TOGGLE -> toggle(sender);
            case BUY -> withCosmetic(sender, args, (player, c) -> plugin.manager().purchase(player, c));
            case PREVIEW -> withCosmetic(sender, args, (player, c) -> plugin.manager().preview(player, c));
            case CRATE -> ifPlayer(sender, player -> CrateMenu.openCrate(plugin, player));
            case KEYS -> keys(sender, args);
            case GIVE -> grant(sender, args, true);
            case TAKE -> grant(sender, args, false);
            case RELOAD -> {
                int count = plugin.reload();
                if (count < 0) {
                    msg.send(sender, "reload-failed");
                } else {
                    msg.send(sender, "reloaded", "count", String.valueOf(count));
                }
            }
            case MIGRATE -> migrate(sender);
        }
        return true;
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private void help(CommandSender sender) {
        Messages msg = plugin.messages();
        msg.sendList(sender, "help");
        if (sender.hasPermission(ADMIN)) {
            msg.sendList(sender, "help-admin");
        }
    }

    private void ifPlayer(CommandSender sender, Consumer<Player> action) {
        if (sender instanceof Player player) {
            action.accept(player);
        } else {
            plugin.messages().send(sender, "player-only");
        }
    }

    /** {@code /cos <명령> <아이디>} 꼴. */
    private void withCosmetic(CommandSender sender, String[] args, BiConsumer<Player, Cosmetic> action) {
        ifPlayer(sender, player -> {
            if (args.length < 2) {
                help(sender);
                return;
            }
            Cosmetic cosmetic = plugin.registry().get(args[1]);
            if (cosmetic == null) {
                plugin.messages().send(sender, "unknown-cosmetic", "id", args[1]);
                return;
            }
            action.accept(player, cosmetic);
        });
    }

    private void unequip(CommandSender sender, String[] args) {
        ifPlayer(sender, player -> {
            if (args.length < 2 || ALL_WORDS.contains(args[1].toLowerCase(Locale.ROOT))) {
                plugin.manager().unequipAll(player);
                return;
            }
            String input = join(args, 1);
            Category category = Category.parse(input, plugin.messages()::category);
            if (category == null) {
                plugin.messages().send(sender, "unknown-category", "category", input);
                return;
            }
            plugin.manager().unequip(player, category, true);
        });
    }

    private void list(CommandSender sender, String[] args) {
        ifPlayer(sender, player -> {
            Messages msg = plugin.messages();
            List<Category> categories = List.of(Category.values());
            if (args.length >= 2) {
                String input = join(args, 1);
                Category only = Category.parse(input, msg::category);
                if (only == null) {
                    msg.send(sender, "unknown-category", "category", input);
                    return;
                }
                categories = List.of(only);
            }
            CosmeticManager manager = plugin.manager();
            for (Category category : categories) {
                List<Cosmetic> all = plugin.registry().of(category);
                player.sendMessage(msg.get("list-header", "category", msg.category(category),
                        "owned", String.valueOf(manager.ownedCount(player, all)), "total", String.valueOf(all.size())));
                for (Cosmetic c : all) {
                    String path = manager.isEquipped(player, c) ? "list-entry-equipped"
                            : manager.owns(player, c) ? "list-entry-owned" : "list-entry-locked";
                    player.sendMessage(msg.get(path, "id", c.id(), "name", c.name()));
                }
            }
        });
    }

    private void toggle(CommandSender sender) {
        ifPlayer(sender, player -> {
            PlayerData data = plugin.store().get(player.getUniqueId());
            if (data == null) {
                return;
            }
            data.setShowOthers(!data.showOthers());
            plugin.store().save(data);
            plugin.displays().syncVisibility();
            plugin.messages().send(player, data.showOthers() ? "toggle-on" : "toggle-off");
        });
    }

    /** {@code /cos 열쇠} 는 내 열쇠 수, {@code /cos 열쇠 <플레이어> <개수>} 는 지급(음수면 회수, 관리자). */
    private void keys(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 3) {
            ifPlayer(sender, player -> {
                PlayerData data = plugin.store().get(player.getUniqueId());
                msg.send(player, "keys-own", "keys", String.valueOf(data == null ? 0 : data.keys()));
            });
            return;
        }
        if (!sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            msg.send(sender, "not-a-number", "value", args[2]);
            return;
        }
        edit(sender, args[1], data -> {
            data.addKeys(amount);
            return amount != 0;
        }, (name, changed) -> {
            msg.send(sender, "keys-given", "player", name, "amount", String.valueOf(amount));
            Player online = Bukkit.getPlayerExact(name);
            if (online != null && amount > 0) {
                msg.send(online, "keys-received", "amount", String.valueOf(amount));
            }
        }, false);
    }

    /** {@code /cos give|take <플레이어> <아이디>} */
    private void grant(CommandSender sender, String[] args, boolean give) {
        Messages msg = plugin.messages();
        if (args.length < 3) {
            help(sender);
            return;
        }
        Cosmetic cosmetic = plugin.registry().get(args[2]);
        if (cosmetic == null) {
            msg.send(sender, "unknown-cosmetic", "id", args[2]);
            return;
        }
        String id = cosmetic.id();
        edit(sender, args[1], give ? data -> data.unlock(id) : data -> data.lock(id), (name, changed) -> {
            String path = give ? (changed ? "given" : "already-owned") : (changed ? "taken" : "not-owned");
            msg.send(sender, path, "player", name, "name", cosmetic.name());
            Player online = Bukkit.getPlayerExact(name);
            if (online != null && changed && give) {
                msg.send(online, "given-target", "name", cosmetic.name());
            }
        }, !give);
    }

    /**
     * 플레이어 데이터를 고친다. 실제 수정은 저장소 스레드에서 한 번만 하므로(접속 중이면 메모리 데이터,
     * 아니면 저장소) 접속·퇴장과 겹쳐도 두 번 적용되거나 사라지지 않는다.
     *
     * @param edit       스레드에 안전한 동작만 한다 (지급/회수/열쇠)
     * @param revalidate 고친 뒤 착용 정보를 다시 확인할지 (회수)
     */
    private void edit(CommandSender sender, String targetName, Predicate<PlayerData> edit,
                      BiConsumer<String, Boolean> done, boolean revalidate) {
        Messages msg = plugin.messages();
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            apply(sender, online.getUniqueId(), online.getName(), targetName, edit, done, revalidate);
            return;
        }
        // 접속하지 않은 플레이어: 이름 조회는 느릴 수 있어서 백그라운드에서
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            UUID uuid = target.getUniqueId();
            String name = target.getName() != null ? target.getName() : targetName;
            if (target.hasPlayedBefore() || target.isOnline()) {
                apply(sender, uuid, name, targetName, edit, done, revalidate);
                return;
            }
            // 이 서버에는 처음이지만 같은 저장소(MySQL)를 쓰는 다른 서버에 들어온 적이 있으면 된다
            plugin.store().exists(uuid).whenComplete((exists, error) -> {
                if (Boolean.TRUE.equals(exists)) {
                    apply(sender, uuid, name, targetName, edit, done, revalidate);
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> msg.send(sender, "player-not-found", "player", targetName));
                }
            });
        });
    }

    private void apply(CommandSender sender, UUID uuid, String name, String typed, Predicate<PlayerData> edit,
                       BiConsumer<String, Boolean> done, boolean revalidate) {
        plugin.store().edit(uuid, edit).whenComplete((changed, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning(name + " 의 데이터를 고치지 못했습니다: " + error.getMessage());
                        plugin.messages().send(sender, "player-not-found", "player", typed);
                        return;
                    }
                    Player now = Bukkit.getPlayer(uuid);
                    if (now != null && changed && revalidate) {
                        plugin.manager().validate(now);
                        plugin.manager().applyVisuals(now);
                    }
                    done.accept(name, changed);
                }));
    }

    private void migrate(CommandSender sender) {
        Messages msg = plugin.messages();
        if (plugin.store().storage() instanceof YamlStorage) {
            msg.send(sender, "migrate-yaml");
            return;
        }
        msg.send(sender, "migrate-start");
        plugin.store().importYaml(plugin.getDataFolder().toPath().resolve("data")).whenComplete((count, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        msg.send(sender, "migrate-failed", "error", String.valueOf(error.getMessage()));
                    } else {
                        msg.send(sender, "migrate-done", "count", String.valueOf(count));
                    }
                }));
    }

    // ── 자동 완성 ────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (Sub sub : Sub.values()) {
                if (!sub.admin || admin) {
                    options.add(sub.names[1]);
                    options.add(sub.names[0]);
                }
            }
            for (Category category : Category.values()) {
                options.add(plugin.messages().category(category).replace(" ", ""));
            }
            return filter(options, args[0]);
        }
        Sub sub = Sub.find(args[0]);
        if (sub == null || (sub.admin && !admin)) {
            return List.of();
        }
        if (args.length == 2) {
            return switch (sub) {
                case EQUIP -> filter(cosmeticIds(sender, true), args[1]);
                case BUY -> filter(cosmeticIds(sender, false), args[1]);
                case PREVIEW -> filter(allIds(), args[1]);
                case UNEQUIP, LIST -> {
                    List<String> options = new ArrayList<>();
                    if (sub == Sub.UNEQUIP) {
                        options.addAll(ALL_WORDS);
                    }
                    for (Category category : Category.values()) {
                        options.add(plugin.messages().category(category).replace(" ", ""));
                        options.add(category.key());
                    }
                    yield filter(options, args[1]);
                }
                case GIVE, TAKE -> filter(onlineNames(), args[1]);
                case KEYS -> admin ? filter(onlineNames(), args[1]) : List.of();
                default -> List.of();
            };
        }
        if (args.length == 3 && (sub == Sub.GIVE || sub == Sub.TAKE)) {
            return filter(allIds(), args[2]);
        }
        if (args.length == 3 && sub == Sub.KEYS && admin) {
            return filter(List.of("1", "5", "10", "-1"), args[2]);
        }
        return List.of();
    }

    private List<String> allIds() {
        List<String> ids = new ArrayList<>();
        for (Cosmetic c : plugin.registry().all()) {
            ids.add(c.id());
        }
        return ids;
    }

    /** @param owned 참이면 가진 것만, 거짓이면 아직 없는 것만 */
    private List<String> cosmeticIds(CommandSender sender, boolean owned) {
        List<String> ids = new ArrayList<>();
        if (sender instanceof Player player) {
            for (Cosmetic c : plugin.registry().all()) {
                if (plugin.manager().owns(player, c) == owned) {
                    ids.add(c.id());
                }
            }
        }
        return ids;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t) && !out.contains(o)) {
                out.add(o);
            }
        }
        return out;
    }
}
