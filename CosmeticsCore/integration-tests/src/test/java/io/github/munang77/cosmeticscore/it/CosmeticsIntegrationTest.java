package io.github.munang77.cosmeticscore.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.data.PlayerData;
import io.github.munang77.cosmeticscore.gui.CategoryMenu;
import io.github.munang77.cosmeticscore.gui.MainMenu;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * MockBukkit(가짜 서버) 위에서 플러그인을 실제로 켜고 기능을 끝까지 돌려 본다.
 *
 * <p>MockBukkit 에 없는 기능(폭죽 소환, 번개, TextDisplay 빌보드)은 플러그인이 경고만 남기고
 * 넘어가는지 확인하는 쪽으로 다룬다.
 */
class CosmeticsIntegrationTest {
    ServerMock server;
    CosmeticsCore plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(CosmeticsCore.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static List<String> drain(PlayerMock p) {
        List<String> out = new ArrayList<>();
        String m;
        while ((m = p.nextMessage()) != null) {
            out.add(m);
        }
        return out;
    }

    private static boolean said(List<String> messages, String part) {
        return messages.stream().anyMatch(m -> org.bukkit.ChatColor.stripColor(m).contains(part));
    }

    /** 저장소 스레드의 작업과 그 뒤 메인 스레드 처리까지 끝낸다. */
    private void settle() {
        server.getScheduler().waitAsyncTasksFinished();
        plugin.store().flush();
        server.getScheduler().performTicks(1);
    }

    private boolean isHat(ItemStack item) {
        return plugin.hats().isHat(item);
    }

    private List<Display> displays() {
        return new ArrayList<>(server.getWorlds().get(0).getEntitiesByClass(Display.class));
    }

    @Test
    void enablesWithAllBundledCosmetics() {
        assertTrue(plugin.isEnabled());
        assertEquals(74, plugin.registry().all().size());
        for (Category c : Category.values()) {
            assertFalse(plugin.registry().of(c).isEmpty(), c + " 기본 코스메틱 없음");
        }
        assertTrue(plugin.store().storage().describe().startsWith("YAML"));
    }

    @Test
    void opEquipsHatByCommandAndItIsProtected() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        assertTrue(p.performCommand("cos 착용 crown"));
        ItemStack helmet = p.getInventory().getHelmet();
        assertTrue(isHat(helmet));
        assertEquals(Material.GOLDEN_HELMET, helmet.getType());
        assertTrue(said(drain(p), "착용"));

        // 모자 아이템이 어떻게든 인벤토리로 새어 나와도 옮길 수 없고, 다음 틱에 지워진다
        ItemStack stray = plugin.hats().create((io.github.munang77.cosmeticscore.cosmetic.HatCosmetic) plugin.registry().get("crown"));
        p.getInventory().setItem(0, stray);
        InventoryView view = p.openInventory(server.createInventory(null, 27));
        // MockBukkit 의 클릭 흉내는 눌린 아이템을 채우지 않아서 실제 서버처럼 직접 만든다
        InventoryClickEvent click = new InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR,
                27 + 27, ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        click.setCurrentItem(stray);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "모자 아이템 클릭이 막혀야 함");
        server.getScheduler().performTicks(1);
        assertNull(p.getInventory().getItem(0), "새어 나온 모자는 지워져야 함");
        assertTrue(isHat(p.getInventory().getHelmet()), "머리 칸 모자는 그대로");
        p.closeInventory();

        // 해제하면 벗겨진다
        assertTrue(p.performCommand("cos 해제 모자"));
        assertNull(p.getInventory().getHelmet());
    }

    @Test
    void realHelmetMovesToInventory() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        p.performCommand("cos equip astronaut");
        assertTrue(isHat(p.getInventory().getHelmet()));
        assertTrue(p.getInventory().contains(Material.DIAMOND_HELMET), "투구가 인벤토리로 옮겨져야 함");
        assertTrue(said(drain(p), "인벤토리로"));
    }

    @Test
    void lockedAndFreeCosmeticsForNormalPlayer() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos equip crown");
        assertNull(p.getInventory().getHelmet());
        assertTrue(said(drain(p), "잠긴"));
        p.performCommand("cos equip pumpkin_head");
        assertEquals(Material.CARVED_PUMPKIN, p.getInventory().getHelmet().getType());
    }

    @Test
    void quitStripsHatAndRejoinRestoresIt() throws Exception {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos equip pumpkin_head");
        assertTrue(isHat(p.getInventory().getHelmet()));
        p.disconnect();
        assertNull(p.getInventory().getHelmet(), "나가면 모자를 벗겨야 함");
        plugin.store().flush();
        Path file = plugin.getDataFolder().toPath().resolve("data").resolve(p.getUniqueId() + ".yml");
        assertTrue(Files.readString(file).contains("hat: pumpkin_head"));
        p.reconnect();
        assertTrue(isHat(p.getInventory().getHelmet()), "다시 들어오면 모자를 씌워야 함");
    }

    @Test
    void deathDoesNotDropHat() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos equip pumpkin_head");
        List<ItemStack> drops = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
                drops.addAll(e.getDrops());
            }
        }, plugin);
        p.getInventory().addItem(new ItemStack(Material.DIRT));
        p.setHealth(0);
        assertTrue(drops.stream().noneMatch(this::isHat), "모자가 떨어지면 안 됨: " + drops);
        assertTrue(drops.stream().anyMatch(i -> i.getType() == Material.DIRT));
    }

    @Test
    void titleAndChatColorApplyToChatAndTab() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.performCommand("cos equip king");
        p.performCommand("cos equip aqua_chat");
        assertTrue(p.getPlayerListName().contains("[왕]"), p.getPlayerListName());
        List<String> formats = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onChat(AsyncPlayerChatEvent e) {
                formats.add(e.getFormat());
                messages.add(e.getMessage());
            }
        }, plugin);
        p.chat("안녕 100%");
        server.getScheduler().waitAsyncEventsFinished();
        assertFalse(formats.isEmpty());
        assertTrue(formats.get(0).startsWith("§6§l[왕]"), formats.get(0));
        assertEquals("§b안녕 100%", messages.get(0));
        // 형식 문자열이 깨지지 않는지 (%)
        assertDoesNotThrow(() -> String.format(formats.get(0), p.getDisplayName(), messages.get(0)));
        p.performCommand("cos 해제 칭호");
        assertEquals(p.getName(), p.getPlayerListName());
    }

    @Test
    void giveAndTakeOnlineAndOffline() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        PlayerMock p = server.addPlayer("Steve");
        admin.performCommand("cos 지급 Steve crown");
        settle();
        assertTrue(said(drain(p), "새 코스메틱"));
        p.performCommand("cos equip crown");
        assertTrue(isHat(p.getInventory().getHelmet()));
        admin.performCommand("cos 회수 Steve crown");
        settle();
        assertNull(p.getInventory().getHelmet(), "회수하면 벗겨져야 함");

        // 같은 지급을 두 번 해도 한 번만 들어간다 (열쇠)
        admin.performCommand("cos 열쇠 Steve 3");
        settle();
        assertEquals(3, plugin.store().get(p.getUniqueId()).keys());

        // 접속하지 않은 플레이어에게 지급
        p.disconnect();
        admin.performCommand("cos give Steve red_wings");
        server.getScheduler().waitAsyncTasksFinished();
        plugin.store().flush();
        server.getScheduler().performTicks(2);
        assertTrue(said(drain(admin), "지급했습니다"), "오프라인 지급 안내가 없음");
        p.reconnect();
        assertTrue(plugin.store().get(p.getUniqueId()).hasUnlocked("red_wings"));
    }

    @Test
    void balloonPetBackpackSpawnFollowAndDespawn() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.performCommand("cos equip red_balloon");
        p.performCommand("cos equip cake_pet");
        p.performCommand("cos equip chest_backpack");
        server.getScheduler().performTicks(5);
        List<Display> all = displays();
        assertEquals(1, all.stream().filter(d -> d instanceof BlockDisplay).count(), "풍선 줄");
        assertEquals(3, all.stream().filter(d -> d instanceof ItemDisplay).count(), "풍선+펫+백팩");
        for (Display d : all) {
            assertFalse(d.isPersistent(), "저장되면 안 됨");
            assertTrue(d.getLocation().distance(p.getLocation()) < 4, "플레이어 곁에 있어야 함");
        }

        // 한 장식이 실패해도(여기서는 MockBukkit 에 없는 이름표 기능) 다른 장식은 계속 나온다
        p.performCommand("cos equip mini_bee");
        p.performCommand("cos equip ender_backpack");
        server.getScheduler().performTicks(3);
        assertTrue(displays().stream().anyMatch(d -> d instanceof ItemDisplay i
                && i.getItemStack().getType() == Material.ENDER_CHEST), "펫이 실패해도 백팩은 바뀌어야 함");
        p.performCommand("cos equip cake_pet");
        server.getScheduler().performTicks(1);

        // 플레이어가 움직이면 따라온다
        p.teleport(p.getLocation().add(20, 0, 0));
        server.getScheduler().performTicks(3);
        for (Display d : displays()) {
            assertTrue(d.getLocation().distance(p.getLocation()) < 4, "따라와야 함: " + d.getType());
        }

        // 해제하면 사라진다
        p.performCommand("cos 해제 전체");
        server.getScheduler().performTicks(1);
        assertTrue(displays().isEmpty());
    }

    @Test
    void displaysDisappearOnQuitAndWhenInvisible() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos equip cake_pet");
        server.getScheduler().performTicks(2);
        assertEquals(1, displays().size());
        p.setInvisible(true);
        server.getScheduler().performTicks(2);
        assertTrue(displays().isEmpty(), "투명화면 숨겨야 함");
        p.setInvisible(false);
        server.getScheduler().performTicks(2);
        assertEquals(1, displays().size());
        p.disconnect();
        server.getScheduler().performTicks(2);
        assertTrue(displays().isEmpty(), "나가면 지워야 함");
    }

    @Test
    void toggleHidesOthersDisplays() {
        PlayerMock owner = server.addPlayer("Owner");
        PlayerMock viewer = server.addPlayer("Viewer");
        owner.performCommand("cos equip cake_pet");
        server.getScheduler().performTicks(2);
        Display pet = displays().get(0);
        assertTrue(viewer.canSee(pet));
        viewer.performCommand("cos 보기");
        assertFalse(viewer.canSee(pet), "보기를 끄면 남의 펫이 안 보여야 함");
        assertTrue(owner.canSee(pet), "본인은 계속 보여야 함");
        viewer.performCommand("cos 보기");
        assertTrue(viewer.canSee(pet));
    }

    @Test
    void particlesAndTrailsRunWithoutErrors() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        for (String id : List.of("red_wings", "golden_crown", "cloud_tornado", "love_heart", "rainbow_spiral")) {
            p.performCommand("cos equip " + id);
            server.getScheduler().performTicks(12);
        }
        p.performCommand("cos equip rainbow_arrow");
        p.launchProjectile(org.bukkit.entity.Arrow.class);
        server.getScheduler().performTicks(5);
    }

    private void noPreviewCooldown() {
        plugin.getConfig().set("preview.cooldown-seconds", 0);
        plugin.saveConfig();
        plugin.reload();
    }

    @Test
    void previewCooldownBlocksEndlessFreeUse() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos 미리보기 red_wings");
        drain(p);
        p.performCommand("cos 미리보기 angel_wings");
        assertTrue(said(drain(p), "초 뒤에 다시"), "잠긴 코스메틱은 대기 시간이 있어야 함");
        assertSame(plugin.registry().get("red_wings"), plugin.manager().equipped(p, Category.PARTICLE));
        // 가진 코스메틱(무료)은 제한 없이 볼 수 있다
        p.performCommand("cos 미리보기 heart_aura");
        assertFalse(said(drain(p), "초 뒤에 다시"));
        // 30초가 지나면 다시 된다
        server.getScheduler().performTicks(20 * 31);
        drain(p);
        p.performCommand("cos 미리보기 angel_wings");
        assertFalse(said(drain(p), "초 뒤에 다시"));
    }

    @Test
    void relogDoesNotResetPreviewCooldown() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos 미리보기 red_wings");
        p.disconnect();
        p.reconnect();
        drain(p);
        p.performCommand("cos 미리보기 angel_wings");
        assertTrue(said(drain(p), "초 뒤에 다시"), "다시 들어와도 대기 시간이 남아 있어야 함");
    }

    @Test
    void hatHidesWhileInvisible() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos equip pumpkin_head");
        assertTrue(isHat(p.getInventory().getHelmet()));
        p.setInvisible(true);
        server.getScheduler().performTicks(100);
        assertNull(p.getInventory().getHelmet(), "투명화 중에는 모자가 위치를 드러내면 안 됨");
        p.setInvisible(false);
        server.getScheduler().performTicks(100);
        assertTrue(isHat(p.getInventory().getHelmet()), "투명화가 풀리면 다시 씌워야 함");
        assertEquals("pumpkin_head", plugin.store().get(p.getUniqueId()).equipped(Category.HAT));
    }

    @Test
    void titlePreviewIsPrivate() {
        PlayerMock p = server.addPlayer();
        PlayerMock other = server.addPlayer("Other");
        p.performCommand("cos 미리보기 king");
        assertTrue(said(drain(p), "[왕]"), "본인에게 예시가 보여야 함");
        assertEquals(p.getName(), p.getPlayerListName(), "탭 이름은 바뀌면 안 됨");
        assertNull(plugin.manager().equipped(p, Category.TITLE), "채팅에 칭호가 붙으면 안 됨");
        assertFalse(said(drain(other), "[왕]"));
    }

    @Test
    void cursedHelmetIsNotRemoved() {
        PlayerMock p = server.addPlayer();
        ItemStack cursed = new ItemStack(Material.IRON_HELMET);
        cursed.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE, 1);
        p.getInventory().setHelmet(cursed);
        p.performCommand("cos equip pumpkin_head");
        assertEquals(Material.IRON_HELMET, p.getInventory().getHelmet().getType(), "귀속 저주 투구는 그대로여야 함");
        assertTrue(said(drain(p), "귀속 저주"));
    }

    @Test
    void doubleClickDoesNotConfirmPurchaseOrToggleTwice() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos 풍선");
        // 첫 칸 = red_balloon (무료)
        p.simulateInventoryClick(p.getOpenInventory(), ClickType.LEFT, 10);
        p.simulateInventoryClick(p.getOpenInventory(), ClickType.DOUBLE_CLICK, 10);
        assertEquals("red_balloon", plugin.store().get(p.getUniqueId()).equipped(Category.BALLOON),
                "더블클릭이 해제로 이어지면 안 됨");
    }

    @Test
    void previewIsTemporaryAndNotSaved() {
        noPreviewCooldown();
        PlayerMock p = server.addPlayer();
        Cosmetic wings = plugin.registry().get("red_wings");
        assertFalse(plugin.manager().owns(p, wings));
        p.performCommand("cos 미리보기 red_wings");
        assertSame(wings, plugin.manager().equipped(p, Category.PARTICLE));
        assertFalse(plugin.manager().isEquipped(p, wings));
        p.performCommand("cos 미리보기 crown");
        assertTrue(isHat(p.getInventory().getHelmet()));
        p.performCommand("cos 미리보기 slime_balloon");
        server.getScheduler().performTicks(2);
        assertFalse(displays().isEmpty());

        server.getScheduler().performTicks(20 * 11);
        assertNull(plugin.manager().equipped(p, Category.PARTICLE));
        assertNull(p.getInventory().getHelmet(), "미리보기 모자가 벗겨져야 함");
        assertTrue(displays().isEmpty(), "미리보기 풍선이 사라져야 함");
        assertTrue(said(drain(p), "미리보기가 끝났습니다"));
        assertNull(plugin.store().get(p.getUniqueId()).equipped(Category.HAT));
    }

    @Test
    void oneShotPreviewsWork() {
        noPreviewCooldown();
        PlayerMock p = server.addPlayer();
        for (String id : List.of("firework", "blood", "lightning", "lesson", "royal_join", "flame_arrow", "sunset_chat")) {
            p.performCommand("cos preview " + id);
            server.getScheduler().performTicks(35);
        }
        List<String> said = drain(p);
        assertTrue(said(said, "참교육"), "킬 메시지 미리보기");
        assertTrue(said(said, "행차"), "입장 메시지 미리보기");
        assertTrue(said(said, "[미리보기]") && said(said, "미리보기입니다"), "채팅 색 미리보기");
    }

    @Test
    void crateUsesKeyAndUnlocksNewCosmetic() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        PlayerMock p = server.addPlayer("Lucky");
        p.performCommand("cos 뽑기");
        assertTrue(said(drain(p), "열쇠가 없습니다"));

        admin.performCommand("cos 열쇠 Lucky 2");
        settle();
        PlayerData data = plugin.store().get(p.getUniqueId());
        assertEquals(2, data.keys());
        int before = data.unlocked().size();
        p.performCommand("cos 뽑기");
        assertEquals(1, data.keys());
        assertEquals(before + 1, data.unlocked().size());
        String won = data.unlocked().iterator().next();
        assertFalse(plugin.registry().get(won).free(), "무료 코스메틱은 뽑기에 안 나와야 함");
        server.getScheduler().performTicks(80);
        assertTrue(said(drain(p), "뽑기 결과"));
        assertTrue(p.performCommand("cos 열쇠"));
    }

    @Test
    void crateNeverRepeatsAndReportsComplete() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        PlayerMock p = server.addPlayer("Collector");
        admin.performCommand("cos 열쇠 Collector 100");
        settle();
        int pool = plugin.crates().candidates(p).size();
        for (int i = 0; i < pool; i++) {
            p.performCommand("cos 뽑기");
        }
        PlayerData data = plugin.store().get(p.getUniqueId());
        assertEquals(pool, data.unlocked().size(), "중복 없이 전부 모아야 함");
        assertEquals(100 - pool, data.keys());
        drain(p);
        p.performCommand("cos 뽑기");
        assertTrue(said(drain(p), "모두 가지고"));
        assertEquals(100 - pool, data.keys(), "다 모았으면 열쇠를 쓰면 안 됨");
    }

    @Test
    void purchaseWithoutVaultExplainsWhy() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos 구매 crown");
        assertTrue(said(drain(p), "Vault"));
        p.performCommand("cos 구매 enchant_aura");
        assertTrue(said(drain(p), "판매하지 않습니다"));
    }

    @Test
    void menuNavigationEquipsAndPreviews() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.performCommand("cos");
        assertInstanceOf(MainMenu.class, p.getOpenInventory().getTopInventory().getHolder());
        int hatSlot = plugin.settings().categorySlot(Category.HAT);
        InventoryClickEvent e = p.simulateInventoryClick(p.getOpenInventory(), ClickType.LEFT, hatSlot);
        assertTrue(e.isCancelled());
        server.getScheduler().performTicks(1);
        assertInstanceOf(CategoryMenu.class, p.getOpenInventory().getTopInventory().getHolder());
        // 첫 칸(10번) = crown
        p.simulateInventoryClick(p.getOpenInventory(), ClickType.LEFT, 10);
        assertEquals(Material.GOLDEN_HELMET, p.getInventory().getHelmet().getType());
        ItemStack icon = p.getOpenInventory().getTopInventory().getItem(10);
        assertTrue(icon.getItemMeta().getEnchantmentGlintOverride(), "착용 중이면 반짝여야 함");
        // 아이템을 빼낼 수 없다
        assertNull(p.getItemOnCursor().getType() == Material.AIR ? null : p.getItemOnCursor());
        // 우클릭 = 미리보기 (창이 닫힌다)
        p.simulateInventoryClick(p.getOpenInventory(), ClickType.RIGHT, 11);
        server.getScheduler().performTicks(1);
        org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof CategoryMenu, "우클릭하면 창이 닫혀야 함");
        assertTrue(said(drain(p), "미리보기"));
    }

    @Test
    void joinEffectAndKillMessage() {
        PlayerMock p = server.addPlayer("Hero");
        p.setOp(true);
        p.performCommand("cos equip royal_join");
        List<String> joins = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onJoin(PlayerJoinEvent e) {
                joins.add(e.getJoinMessage());
            }
        }, plugin);
        p.disconnect();
        p.reconnect();
        assertFalse(joins.isEmpty());
        assertTrue(joins.get(0) != null && joins.get(0).contains("행차"), String.valueOf(joins));
    }

    @Test
    void reloadAndDisabledWorld() throws Exception {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.performCommand("cos equip crown");
        p.performCommand("cos equip cake_pet");
        server.getScheduler().performTicks(2);
        assertEquals(1, displays().size());
        plugin.getConfig().set("disabled-worlds", List.of(p.getWorld().getName()));
        plugin.saveConfig();
        p.performCommand("cos 리로드");
        assertTrue(said(drain(p), "다시 불러왔습니다"));
        server.getScheduler().performTicks(2);
        assertNull(p.getInventory().getHelmet(), "꺼진 월드에서는 모자가 벗겨져야 함");
        assertTrue(displays().isEmpty(), "꺼진 월드에서는 장식이 없어야 함");
        // 착용 정보는 남아 있다
        assertEquals("crown", plugin.store().get(p.getUniqueId()).equipped(Category.HAT));
    }

    @Test
    void brokenCosmeticsFileKeepsOldList() throws Exception {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        Files.writeString(plugin.getDataFolder().toPath().resolve("cosmetics.yml"), "hats: [broken\n");
        p.performCommand("cos reload");
        assertTrue(said(drain(p), "형식이 잘못돼"));
        assertEquals(74, plugin.registry().all().size());
    }

    @Test
    void disableCleansUp() {
        PlayerMock p = server.addPlayer();
        p.performCommand("cos equip pumpkin_head");
        p.performCommand("cos equip cake_pet");
        server.getScheduler().performTicks(2);
        server.getPluginManager().disablePlugin(plugin);
        assertNull(p.getInventory().getHelmet());
        assertTrue(displays().isEmpty());
    }

    @Test
    void sqliteStorageAndMigration() {
        PlayerMock p = server.addPlayer("Migrant");
        p.performCommand("cos equip cake_pet");
        p.disconnect();
        plugin.store().flush();

        plugin.getConfig().set("storage.type", "SQLITE");
        plugin.saveConfig();
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.store().storage().describe().startsWith("SQLite"), plugin.store().storage().describe());

        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        admin.performCommand("cos 이전");
        plugin.store().flush();
        server.getScheduler().performTicks(2);
        assertTrue(said(drain(admin), "1명의 데이터를 옮겼습니다"));

        p.reconnect();
        assertEquals("cake_pet", plugin.store().get(p.getUniqueId()).equipped(Category.PET));
        p.performCommand("cos equip red_balloon");
        p.disconnect();
        plugin.store().flush();
        p.reconnect();
        assertEquals("red_balloon", plugin.store().get(p.getUniqueId()).equipped(Category.BALLOON), "SQLite 에 저장돼야 함");
    }

    @Test
    void tabCompletionOffersKoreanCommands() {
        PlayerMock p = server.addPlayer();
        List<String> first = server.getCommandTabComplete(p, "cos ");
        assertTrue(first.containsAll(Set.of("착용", "뽑기", "미리보기", "풍선")), first.toString());
        assertFalse(first.contains("지급"), "관리자 명령은 숨겨야 함");
    }
}
