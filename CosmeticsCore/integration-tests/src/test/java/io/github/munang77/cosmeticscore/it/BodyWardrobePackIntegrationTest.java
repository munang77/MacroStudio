package io.github.munang77.cosmeticscore.it;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Settings;
import io.github.munang77.cosmeticscore.cosmetic.Category;
import io.github.munang77.cosmeticscore.cosmetic.Cosmetic;
import io.github.munang77.cosmeticscore.gui.MainMenu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 몸 장식(날개·꼬리 등), 옷장, 리소스팩을 가짜 서버에서 끝까지 돌려 본다. */
class BodyWardrobePackIntegrationTest {
    ServerMock server;
    CosmeticsCore plugin;
    World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(CosmeticsCore.class);
        world = server.addSimpleWorld("world");
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

    private List<ItemDisplay> itemDisplays() {
        return new ArrayList<>(world.getEntitiesByClass(ItemDisplay.class));
    }

    /** 빈 공간 높은 곳에 선 플레이어 (발밑만 땅으로 친다). */
    private PlayerMock player(boolean op) {
        PlayerMock p = server.addPlayer();
        p.setOp(op);
        p.teleport(new Location(world, 0.5, 120, 0.5, 0, 0));
        p.setOnGround(true);
        return p;
    }

    private LivingEntity mannequin() {
        for (Entity e : world.getEntities()) {
            if (e instanceof LivingEntity living && !(e instanceof org.bukkit.entity.Player)) {
                return living;
            }
        }
        return null;
    }

    // ── 몸 장식 ──────────────────────────────────

    @Test
    void wingsAreAMirroredPairThatLeansAndHides() {
        PlayerMock p = player(true);
        p.performCommand("cos equip feather_wings");
        server.getScheduler().performTicks(3);
        List<ItemDisplay> wings = itemDisplays();
        assertEquals(2, wings.size(), "날개는 좌우 두 조각");
        assertNotEquals(wings.get(0).getTransformation().getLeftRotation(),
                wings.get(1).getTransformation().getLeftRotation(), "왼쪽은 거울처럼 돌아가 있어야 함");
        for (ItemDisplay d : wings) {
            assertFalse(d.isPersistent());
            assertEquals(7130001, d.getItemStack().getItemMeta().getCustomModelData());
            assertTrue(d.getLocation().distance(p.getLocation()) < 2);
        }

        // 꼬리를 더하면 조각이 하나 늘고, 날개는 그대로
        p.performCommand("cos equip fox_tail");
        server.getScheduler().performTicks(2);
        assertEquals(3, itemDisplays().size());

        // 웅크리면 몸통처럼 앞으로 숙인다
        p.setSneaking(true);
        server.getScheduler().performTicks(2);
        for (ItemDisplay d : itemDisplays()) {
            assertTrue(d.getLocation().getPitch() > 20, "웅크리면 숙여야 함: " + d.getLocation().getPitch());
        }
        p.setSneaking(false);

        // 겉날개로 날 때는 몸 장식을 숨긴다 (크기 0)
        p.setGliding(true);
        server.getScheduler().performTicks(2);
        for (ItemDisplay d : itemDisplays()) {
            assertEquals(0f, d.getTransformation().getScale().x(), 1e-6, "날 때는 숨겨야 함");
        }
        p.setGliding(false);
        server.getScheduler().performTicks(2);
        for (ItemDisplay d : itemDisplays()) {
            assertTrue(d.getTransformation().getScale().x() > 0, "내리면 다시 보여야 함");
        }

        p.performCommand("cos 해제 전체");
        server.getScheduler().performTicks(1);
        assertTrue(itemDisplays().isEmpty());
    }

    @Test
    void flappingWingsKeepMovingButStillCosmeticsStayPut() {
        PlayerMock p = player(true);
        p.performCommand("cos equip feather_wings");
        p.performCommand("cos equip belt_pouch");
        server.getScheduler().performTicks(3);
        ItemDisplay belt = itemDisplays().stream()
                .filter(d -> d.getItemStack().getItemMeta().getCustomModelData() == 7130007).findFirst().orElseThrow();
        ItemDisplay wing = itemDisplays().stream()
                .filter(d -> d.getItemStack().getItemMeta().getCustomModelData() == 7130001).findFirst().orElseThrow();
        var beltBefore = belt.getTransformation();
        var wingBefore = wing.getTransformation().getLeftRotation();
        server.getScheduler().performTicks(6);
        assertEquals(beltBefore, belt.getTransformation(), "움직임 없는 장식은 그대로");
        assertNotEquals(wingBefore, wing.getTransformation().getLeftRotation(), "날개는 퍼덕여야 함");
    }

    // ── 옷장 ─────────────────────────────────────

    @Test
    void wardrobeShowsMannequinLetsYouBrowseAndEquip() {
        PlayerMock p = player(true);
        Location start = p.getLocation().clone();
        p.performCommand("cos 옷장");
        assertTrue(plugin.wardrobe().isOpen(p), "옷장이 열려야 함: " + drain(p));
        LivingEntity mannequin = mannequin();
        assertNotNull(mannequin, "마네킹이 서야 함");
        assertFalse(mannequin.isPersistent());
        assertTrue(mannequin.getLocation().distance(p.getLocation()) < 4);
        server.getScheduler().performTicks(2);

        // 마우스 휠: 첫 카테고리(모자)에서 다음 코스메틱 → 마네킹이 모자를 쓴다
        PlayerItemHeldEvent scroll = new PlayerItemHeldEvent(p, 0, 1);
        server.getPluginManager().callEvent(scroll);
        assertTrue(scroll.isCancelled(), "손에 든 칸은 그대로");
        EntityEquipment eq = mannequin.getEquipment();
        Cosmetic firstHat = plugin.registry().of(Category.HAT).get(0);
        assertTrue(plugin.hats().isHat(eq.getHelmet()), "마네킹이 모자를 써야 함");
        assertFalse(plugin.manager().isEquipped(p, firstHat), "고르기만 해서는 입지 않음");

        // 마네킹을 우클릭하면 실제로 입는다
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(p, mannequin));
        assertTrue(plugin.manager().isEquipped(p, firstHat));

        // 움직이려 해도 제자리 (고개는 돌릴 수 있다)
        Location to = start.clone().add(3, 0, 0);
        to.setYaw(45);
        org.bukkit.event.player.PlayerMoveEvent move = new org.bukkit.event.player.PlayerMoveEvent(p, start, to);
        server.getPluginManager().callEvent(move);
        assertEquals(start.getX(), move.getTo().getX(), 1e-6);
        assertEquals(45, move.getTo().getYaw(), 1e-6);

        // 웅크리면 닫히고 마네킹과 버튼이 사라진다
        p.simulateSneak(true);
        assertFalse(plugin.wardrobe().isOpen(p));
        server.getScheduler().performTicks(2);
        assertNull(mannequin(), "마네킹이 남으면 안 됨");
        assertTrue(world.getEntitiesByClass(org.bukkit.entity.Interaction.class).isEmpty(), "버튼이 남으면 안 됨");
        assertTrue(world.getEntitiesByClass(org.bukkit.entity.TextDisplay.class).isEmpty(), "글자가 남으면 안 됨");
    }

    @Test
    void wardrobeMannequinWearsBodyCosmeticsOnlyForTheOwner() {
        PlayerMock p = player(true);
        PlayerMock other = player(false);
        p.performCommand("cos 옷장 날개");
        assertTrue(plugin.wardrobe().isOpen(p));
        LivingEntity mannequin = mannequin();
        assertFalse(other.canSee(mannequin), "다른 사람에게는 안 보여야 함");
        assertTrue(p.canSee(mannequin));
        server.getPluginManager().callEvent(new PlayerItemHeldEvent(p, 0, 1));
        server.getScheduler().performTicks(2);
        List<ItemDisplay> wings = itemDisplays();
        assertEquals(2, wings.size(), "마네킹에 날개 한 쌍");
        for (ItemDisplay d : wings) {
            assertFalse(other.canSee(d), "마네킹 장식은 본인만");
            assertTrue(d.getLocation().distance(mannequin.getLocation()) < 2);
        }
        plugin.wardrobe().close(p);
        server.getScheduler().performTicks(2);
        assertTrue(itemDisplays().isEmpty());
    }

    @Test
    void wardrobeNeedsRoomAndClosesWhenHurt() {
        PlayerMock p = player(true);
        // 공중에 떠 있으면 열지 않는다 (붙잡아 두면 서버가 날기로 보고 내보낼 수 있어서)
        p.setOnGround(false);
        p.performCommand("cos 옷장");
        assertFalse(plugin.wardrobe().isOpen(p));
        drain(p);
        p.setOnGround(true);
        if (Material.STONE.isSolid()) {
            // MockBukkit 판마다 블록 성질을 모를 때가 있어, 알 때만 막힌 경우를 본다
            world.getBlockAt(0, 120, 2).setType(Material.STONE);
            p.performCommand("cos 옷장");
            assertFalse(plugin.wardrobe().isOpen(p));
            assertTrue(said(drain(p), "앞이 막혀"));
            world.getBlockAt(0, 120, 2).setType(Material.AIR);
        }
        // 마네킹 자리에 압력판이 있으면 대신 밟지 않도록 열지 않는다
        world.getBlockAt(0, 120, 3).setType(Material.STONE_PRESSURE_PLATE);
        p.performCommand("cos 옷장");
        assertFalse(plugin.wardrobe().isOpen(p), "압력판 위에는 마네킹을 세우지 않음");
        world.getBlockAt(0, 120, 3).setType(Material.AIR);
        drain(p);

        p.performCommand("cos 옷장");
        assertTrue(plugin.wardrobe().isOpen(p));
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityDamageEvent(p,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), 1));
        assertFalse(plugin.wardrobe().isOpen(p), "맞으면 닫혀야 함");
    }

    @Test
    void fixedWardrobeTeleportsThereAndBack() {
        PlayerMock admin = player(true);
        admin.teleport(new Location(world, 100.5, 120, 100.5, 90, 0));
        admin.performCommand("cos 옷장 설정");
        assertTrue(plugin.getConfig().getString("wardrobe.location").startsWith(world.getName() + ","));

        PlayerMock p = player(false);
        p.teleport(new Location(world, -20, 120, -20));
        p.performCommand("cos 옷장");
        assertTrue(plugin.wardrobe().isOpen(p));
        assertEquals(100.5, p.getLocation().getX(), 1e-3, "옷장 자리로 옮겨 가야 함");
        p.performCommand("cos 옷장");
        assertFalse(plugin.wardrobe().isOpen(p));
        assertEquals(-20, p.getLocation().getX(), 1e-3, "닫으면 원래 자리로");

        // 옷장에 있는 채로 나가도 원래 자리에서 다시 시작한다
        p.performCommand("cos 옷장");
        p.disconnect();
        assertEquals(-20, p.getLocation().getX(), 1e-3);

        admin.performCommand("cos 옷장 삭제");
        assertEquals("", plugin.getConfig().getString("wardrobe.location"));
    }

    @Test
    void mainMenuWardrobeButtonOpensWardrobe() {
        PlayerMock p = player(true);
        new MainMenu(plugin, p).open();
        InventoryView view = p.getOpenInventory();
        assertEquals(Material.ARMOR_STAND, view.getTopInventory().getItem(Settings.WARDROBE_SLOT).getType());
        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                Settings.WARDROBE_SLOT, ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        server.getScheduler().performTicks(2);
        assertTrue(plugin.wardrobe().isOpen(p));
    }

    @Test
    void disablingPluginSendsWardrobeUsersBack() {
        PlayerMock admin = player(true);
        admin.teleport(new Location(world, 50.5, 120, 50.5));
        admin.performCommand("cos 옷장 설정");
        PlayerMock p = player(false);
        p.teleport(new Location(world, 7, 120, 7));
        p.performCommand("cos 옷장");
        assertTrue(plugin.wardrobe().isOpen(p));
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(7, p.getLocation().getX(), 1e-3);
        assertNull(mannequin());
    }

    @Test
    void pinningBackIsNotTreatedAsLeaving() {
        PlayerMock admin = player(true);
        admin.teleport(new Location(world, 30.5, 120, 30.5));
        admin.performCommand("cos 옷장 설정");
        PlayerMock p = player(false);
        Location origin = new Location(world, -5, 120, -5);
        p.teleport(origin);
        p.performCommand("cos 옷장");
        assertTrue(plugin.wardrobe().isOpen(p));
        Location view = p.getLocation().clone();

        // 움직이려 하면 서버가 제자리로 "순간이동" 시킨다: 이때 옷장이 닫히면 안 된다
        Location to = view.clone();
        to.setYaw(80);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerTeleportEvent(p, view.clone().add(0.3, 0, 0),
                to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertTrue(plugin.wardrobe().isOpen(p), "제자리로 돌리는 것은 떠나는 게 아님");

        // 다른 곳으로 순간이동하면 닫히고, 원래 자리로 되돌리지 않는다
        p.teleport(new Location(world, 200, 120, 200));
        assertFalse(plugin.wardrobe().isOpen(p));
        assertEquals(200, p.getLocation().getX(), 1e-3);
    }

    @Test
    void blockedTeleportDoesNotLeaveABrokenWardrobe() {
        PlayerMock admin = player(true);
        admin.teleport(new Location(world, 30.5, 120, 30.5));
        admin.performCommand("cos 옷장 설정");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void deny(org.bukkit.event.player.PlayerTeleportEvent event) {
                event.setCancelled(true);
            }
        }, plugin);
        PlayerMock p = player(false);
        p.performCommand("cos 옷장");
        assertFalse(plugin.wardrobe().isOpen(p), "옮기지 못하면 열지 않음");
        assertNull(mannequin(), "마네킹이 남으면 안 됨");
    }

    @Test
    void reloadClosesOpenWardrobes() {
        PlayerMock p = player(true);
        p.performCommand("cos 옷장");
        assertTrue(plugin.wardrobe().isOpen(p));
        plugin.reload();
        assertFalse(plugin.wardrobe().isOpen(p));
        assertNull(mannequin());
    }

    @Test
    void oldConfigIsMigratedToTheNewMenuLayout() throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        old.set("config-version", null);
        old.set("menu.categories.backpack.slot", 11);
        old.set("menu.categories.balloon.slot", 12);
        old.set("menu.categories.backpack.icon", "BARREL");
        old.set("wardrobe", null);
        old.save(file.toFile());

        plugin.reload();
        assertEquals(12, plugin.settings().categorySlot(Category.BACKPACK));
        assertEquals(16, plugin.settings().categorySlot(Category.BALLOON));
        assertEquals(Material.BARREL, plugin.settings().categoryIcon(Category.BACKPACK), "아이콘은 그대로");
        org.bukkit.configuration.file.YamlConfiguration now = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(2, now.getInt("config-version"));
        assertTrue(now.contains("wardrobe.enabled"), "새 설정이 파일에 채워져야 함");
    }

    @Test
    void setWardrobeSpotKeepsUnsavedConfigEdits() throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        // 관리자가 파일을 고치고 아직 리로드하지 않음
        String edited = Files.readString(file).replace("send: false", "send: true");
        Files.writeString(file, edited);
        PlayerMock admin = player(true);
        admin.performCommand("cos 옷장 설정");
        String after = Files.readString(file);
        assertTrue(after.contains("send: true"), "고친 내용이 사라지면 안 됨");
        assertTrue(after.contains("location: " + world.getName() + ",") || after.contains("location: '" + world.getName()),
                "옷장 자리가 저장돼야 함");
    }

    // ── 리소스팩 ─────────────────────────────────

    @Test
    void otherPluginsPackResultsAreIgnored() {
        PlayerMock p = player(false);
        drain(p);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerResourcePackStatusEvent(p,
                java.util.UUID.randomUUID(), org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED));
        assertTrue(drain(p).isEmpty());
    }

    @Test
    void resourcePackIsBuiltWithModelsAndExtras() throws IOException {
        Path zip = plugin.pack().file();
        assertTrue(Files.exists(zip), "리소스팩 zip 이 만들어져야 함");
        List<String> names = entries(Files.newInputStream(zip));
        assertTrue(names.contains("pack.mcmeta"));
        assertTrue(names.contains("assets/cosmeticscore/models/item/angel_wing.json"));
        assertTrue(names.contains("assets/minecraft/items/feather.json"));
        assertTrue(names.contains("assets/minecraft/models/item/feather.json"));
        String before = plugin.pack().hash();
        assertEquals(40, before.length());

        // extra 폴더에 넣은 파일이 합쳐지고, 해시가 바뀐다
        Path extra = plugin.getDataFolder().toPath().resolve("resourcepack/extra/assets/myserver/models/item/crown.json");
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "{}");
        plugin.reload();
        assertTrue(entries(Files.newInputStream(zip)).contains("assets/myserver/models/item/crown.json"));
        assertNotEquals(before, plugin.pack().hash());
        // 내용이 같으면 해시도 같다 (다시 만들어도 클라이언트가 새로 받지 않게)
        String again = plugin.pack().hash();
        plugin.reload();
        assertEquals(again, plugin.pack().hash());
    }

    @Test
    void selfHostServesOnlyThePack() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        plugin.getConfig().set("resource-pack.send", true);
        plugin.getConfig().set("resource-pack.self-host.address", "127.0.0.1");
        plugin.getConfig().set("resource-pack.self-host.port", port);
        plugin.saveConfig();
        plugin.reload();
        try {
            String base = "http://127.0.0.1:" + port + "/";
            HttpURLConnection ok = (HttpURLConnection) URI.create(base + plugin.pack().hash() + ".zip").toURL().openConnection();
            assertEquals(200, ok.getResponseCode());
            byte[] body;
            try (InputStream in = ok.getInputStream()) {
                body = in.readAllBytes();
            }
            assertArrayEquals(Files.readAllBytes(plugin.pack().file()), body);
            for (String path : new String[] {"", "config.yml", "../config.yml", plugin.pack().hash() + ".zip/../x"}) {
                HttpURLConnection bad = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
                assertEquals(404, bad.getResponseCode(), "다른 파일은 주면 안 됨: " + path);
            }
            HttpURLConnection post = (HttpURLConnection) URI.create(base + plugin.pack().hash() + ".zip").toURL().openConnection();
            post.setRequestMethod("POST");
            assertEquals(404, post.getResponseCode());

            // MockBukkit 은 리소스팩 보내기를 흉내 내지 못하므로, 보내는 데까지 갔는지만 본다
            PlayerMock p = player(false);
            try {
                p.performCommand("cos 리소스팩");
                assertTrue(said(drain(p), "보냈습니다"));
            } catch (org.bukkit.command.CommandException e) {
                boolean reachedSend = java.util.Arrays.stream(e.getCause().getStackTrace())
                        .anyMatch(f -> f.getMethodName().equals("addResourcePack"));
                assertTrue(reachedSend, "보내는 단계까지 가야 함: " + e.getCause());
            }
        } finally {
            server.getPluginManager().disablePlugin(plugin);
        }
    }

    private static List<String> entries(InputStream raw) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
