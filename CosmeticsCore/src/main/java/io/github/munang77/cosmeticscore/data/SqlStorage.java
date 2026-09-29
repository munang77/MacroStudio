package io.github.munang77.cosmeticscore.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * SQLite 또는 MySQL/MariaDB 표 하나에 {@code (uuid, data)} 로 저장한다. 서버 여러 대가 MySQL 을 같이 쓰면
 * 어느 서버에서든 같은 코스메틱을 쓸 수 있다. 연결은 입출력 스레드에서만 쓰고, 끊기면 다시 연결한다.
 */
public final class SqlStorage implements Storage {

    private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private final String url;
    private final String user;
    private final String password;
    private final String table;
    private final boolean mysql;
    private final Path brokenDir;
    private final Logger log;
    private Connection connection;

    private SqlStorage(String url, String user, String password, String table, boolean mysql, Path brokenDir,
                       Logger log) {
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("storage.table 은 영문, 숫자, _ 만 쓸 수 있습니다: " + table);
        }
        this.url = url;
        this.user = user;
        this.password = password;
        this.table = table;
        this.mysql = mysql;
        this.brokenDir = brokenDir;
        this.log = log;
    }

    public static SqlStorage sqlite(Path file, String table, Path brokenDir, Logger log) {
        loadDriver("org.sqlite.JDBC");
        return new SqlStorage("jdbc:sqlite:" + file.toAbsolutePath(), null, null, table, false, brokenDir, log);
    }

    public static SqlStorage mysql(String host, int port, String database, String user, String password,
                                   String properties, String table, Path brokenDir, Logger log) {
        loadDriver("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + (properties == null || properties.isBlank() ? "" : "?" + properties);
        return new SqlStorage(url, user, password, table, true, brokenDir, log);
    }

    /** 서버에 들어 있는 JDBC 드라이버를 등록한다. 없으면 연결할 때 오류가 난다. */
    private static void loadDriver(String name) {
        try {
            Class.forName(name);
        } catch (ClassNotFoundException ignored) {
            // DriverManager 가 직접 찾도록 둔다
        }
    }

    /** 연결해서 표를 만들어 본다. 설정이 틀렸으면 켤 때 바로 알 수 있다. */
    public void open() throws SQLException {
        connection();
    }

    private Connection connection() throws SQLException {
        if (connection != null) {
            boolean ok;
            try {
                ok = !connection.isClosed() && connection.isValid(2);
            } catch (SQLException e) {
                ok = false;
            }
            if (ok) {
                return connection;
            }
            closeQuietly();
        }
        connection = user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, data " + (mysql ? "MEDIUMTEXT" : "TEXT")
                    + " NOT NULL, updated BIGINT NOT NULL)");
        }
        return connection;
    }

    @Override
    public String read(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement("SELECT data FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Override
    public void write(UUID uuid, String yaml) throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + table + " (uuid, data, updated) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE data = VALUES(data), updated = VALUES(updated)"
                : "INSERT INTO " + table + " (uuid, data, updated) VALUES (?, ?, ?)"
                        + " ON CONFLICT(uuid) DO UPDATE SET data = excluded.data, updated = excluded.updated";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, yaml);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public boolean quarantine(UUID uuid, String raw) {
        Path broken = brokenDir.resolve(uuid + ".broken-" + System.currentTimeMillis() + ".yml");
        try {
            Files.createDirectories(brokenDir);
            Files.writeString(broken, raw, StandardCharsets.UTF_8);
            log.warning(uuid + " 의 데이터 형식이 깨져 있어 " + broken + " 에 보관하고 새로 시작합니다.");
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "깨진 데이터를 보관하지 못했습니다: " + uuid, e);
            return false;
        }
    }

    /** 서버 여러 대가 같이 쓸 수 있는 저장소(MySQL)인지. */
    public boolean shared() {
        return mysql;
    }

    @Override
    public String describe() {
        return (mysql ? "MySQL" : "SQLite") + " (" + table + ")";
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 이미 끊긴 연결
            }
            connection = null;
        }
    }
}
