package io.github.munang77.cosmeticscore.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.munang77.cosmeticscore.CosmeticsCore;
import io.github.munang77.cosmeticscore.Settings;
import io.github.munang77.cosmeticscore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * 코스메틱 3D 모델 리소스팩. 켤 때마다 플러그인에 들어 있는 모델과 {@code resourcepack/extra/} 에 넣은 파일을 합쳐
 * {@code resourcepack/CosmeticsCore-pack.zip} 을 만든다.
 *
 * <p>설정에 따라 접속한 플레이어에게 보낸다. 주소를 따로 적지 않으면 플러그인이 작은 웹 서버를 열어 직접 보내는데,
 * 이 서버는 리소스팩 파일 하나만 내어 주고 그 밖의 요청은 모두 거절한다.
 */
public final class ResourcePackService implements Listener {

    /** 다른 플러그인의 리소스팩과 함께 쓰도록 고정 아이디로 추가한다. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("cosmeticscore:pack".getBytes(StandardCharsets.UTF_8));
    /** zip 안 파일 시각을 고정해서, 내용이 같으면 해시도 같게 (클라이언트가 다시 받지 않게). */
    private static final long FIXED_TIME = 315_532_800_000L;
    private static final String ZIP_NAME = "CosmeticsCore-pack.zip";

    private final CosmeticsCore plugin;
    private volatile byte[] zip;
    private volatile byte[] sha1;
    private String url;
    private HttpServer server;
    private ExecutorService executor;

    public ResourcePackService(CosmeticsCore plugin) {
        this.plugin = plugin;
    }

    /** 리소스팩을 (다시) 만들고, 보낼 주소를 정한다. */
    public void start() {
        stop();
        // 만들다 실패하면 예전 주소로 보내지 않게 먼저 비운다
        url = null;
        try {
            build();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "리소스팩을 만들지 못했습니다.", e);
            return;
        }
        Settings settings = plugin.settings();
        if (!settings.packSend()) {
            return;
        }
        String custom = settings.packUrl();
        if (!custom.isEmpty()) {
            url = custom;
            plugin.getLogger().info("리소스팩을 " + url + " 에서 받게 합니다 (해시 " + hash() + ")");
            return;
        }
        if (!settings.packSelfHost()) {
            plugin.getLogger().warning("resource-pack.url 이 비어 있고 self-host 도 꺼져 있어 리소스팩을 보내지 않습니다.");
            return;
        }
        String address = settings.packAddress().isEmpty() ? Bukkit.getIp() : settings.packAddress();
        if (address == null || address.isBlank()) {
            plugin.getLogger().warning("리소스팩을 직접 보내려면 config.yml 의 resource-pack.self-host.address 에 "
                    + "플레이어가 접속하는 서버 주소(공인 IP 나 도메인)를 적어 주세요.");
            return;
        }
        try {
            host(settings.packPort());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, settings.packPort() + " 번 포트로 리소스팩을 내보내지 못했습니다. "
                    + "다른 프로그램이 쓰는지, 방화벽에서 열려 있는지 확인하세요.", e);
            return;
        }
        url = "http://" + address.trim() + ":" + settings.packPort() + "/" + hash() + ".zip";
        plugin.getLogger().info("리소스팩을 직접 보냅니다: " + url);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 만든 리소스팩 파일. */
    public Path file() {
        return plugin.getDataFolder().toPath().resolve("resourcepack").resolve(ZIP_NAME);
    }

    public String hash() {
        byte[] h = sha1;
        return h == null ? "" : HexFormat.of().formatHex(h);
    }

    /** 보낼 수 있으면 보낸다. @return 보냈으면 {@code true} */
    public boolean send(Player player) {
        String target = url;
        byte[] h = sha1;
        if (target == null || h == null) {
            return false;
        }
        player.addResourcePack(PACK_ID, target, h, Text.color(plugin.settings().packPrompt()),
                plugin.settings().packRequired());
        return true;
    }

    // ── 만들기 ───────────────────────────────────

    /** 들어 있는 모델 + extra 폴더를 합쳐 zip 을 쓴다 (같은 경로면 extra 가 이긴다). */
    private void build() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (InputStream index = plugin.getResource("pack/index.txt")) {
            if (index == null) {
                throw new IOException("플러그인에 pack/index.txt 가 없습니다");
            }
            List<String> paths = new String(index.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            for (String path : paths) {
                try (InputStream in = plugin.getResource("pack/" + path)) {
                    if (in != null) {
                        files.put(path, in.readAllBytes());
                    }
                }
            }
        }
        Path folder = plugin.getDataFolder().toPath().resolve("resourcepack");
        Path extra = folder.resolve("extra");
        Files.createDirectories(extra);
        Path readme = extra.resolve("README.txt");
        if (Files.notExists(readme)) {
            Files.writeString(readme, """
                    이 폴더에 넣은 파일은 CosmeticsCore 리소스팩에 합쳐집니다 (/cos 리로드 또는 서버 재시작).
                    리소스팩 안의 경로 그대로 넣으세요. 예) assets/myserver/models/item/crown.json
                    같은 경로의 파일이 있으면 이 폴더의 것이 이깁니다.
                    """, StandardCharsets.UTF_8);
        }
        try (Stream<Path> walk = Files.walk(extra)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (p.equals(readme)) {
                    continue;
                }
                String rel = extra.relativize(p).toString().replace('\\', '/');
                files.put(rel, Files.readAllBytes(p));
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(FIXED_TIME);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        byte[] data = bytes.toByteArray();
        try {
            sha1 = MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        zip = data;
        Files.write(folder.resolve(ZIP_NAME), data);
        plugin.getLogger().info("리소스팩을 만들었습니다: plugins/" + plugin.getName() + "/resourcepack/" + ZIP_NAME
                + " (" + files.size() + "개 파일, " + data.length / 1024 + "KB)");
    }

    // ── 직접 보내기 ──────────────────────────────

    /** 리소스팩 파일 하나만 내어 주는 웹 서버. */
    private void host(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 16);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CosmeticsCore-ResourcePack");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        http.createContext("/", this::serve);
        http.start();
        server = http;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] data = zip;
            String expected = "/" + hash() + ".zip";
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equals(method);
            if (data == null || !("GET".equals(method) || head)
                    || !expected.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, head ? -1 : data.length);
            if (!head) {
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(data);
                }
            }
        }
    }

    // ── 보내기 ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (url == null) {
            return;
        }
        Player player = event.getPlayer();
        // 접속 직후에는 다른 플러그인의 리소스팩과 겹치지 않게 잠깐 기다린다
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        // 다른 플러그인의 리소스팩 결과에는 답하지 않는다
        if (url == null || !PACK_ID.equals(event.getID())) {
            return;
        }
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.DECLINED) {
            plugin.messages().send(event.getPlayer(), "resource-pack.declined");
        } else if (status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            plugin.messages().send(event.getPlayer(), "resource-pack.failed");
        }
    }
}
