package net.rms.xrain.whitelistrms;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

@Plugin(
    id = "whitelist-rms",
    name = "WhitelistRMS",
    version = "1.1-SNAPSHOT",
    description = "A whitelist plugin that reads from MySQL database",
    authors = {"XRain666"}
)
public class WhitelistRMS {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private HikariDataSource dataSource;
    private String notWhitelistedMessage;
    private HttpClient httpClient;
    private Gson gson;
    private AutoUpdater autoUpdater;
    private boolean updaterEnabled;
    private int checkInterval;
    private boolean autoDownload;
    private boolean notifyConsole;
    private boolean useMirror;
    private String customMirrorUrl;

    @Inject
    public WhitelistRMS(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // 创建配置目录
        if (!Files.exists(dataDirectory)) {
            try {
                Files.createDirectory(dataDirectory);
            } catch (IOException e) {
                logger.error("Failed to create data directory", e);
                return;
            }
        }

        // 保存默认配置
        Path configPath = dataDirectory.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath);
                }
            } catch (IOException e) {
                logger.error("Failed to save default config", e);
                return;
            }
        }

        // 加载配置
        try {
            ConfigManager configManager = new ConfigManager(logger, configPath);
            Map<String, Object> config = configManager.loadAndValidateConfig();
            
            // 获取MySQL配置
            Map<String, Object> mysql = (Map<String, Object>) config.get("mysql");
            String host = (String) mysql.get("host");
            int port = (int) mysql.get("port");
            String database = (String) mysql.get("database");
            String username = (String) mysql.get("username");
            String password = (String) mysql.get("password");

            // 获取消息配置
            Map<String, Object> messages = (Map<String, Object>) config.get("messages");
            notWhitelistedMessage = (String) messages.get("not-whitelisted");

            // 获取更新器配置
            Map<String, Object> updaterConfig = (Map<String, Object>) config.get("updater");
            if (updaterConfig != null) {
                updaterEnabled = (Boolean) updaterConfig.getOrDefault("enabled", false);
                checkInterval = (Integer) updaterConfig.getOrDefault("check-interval", 24);
                autoDownload = (Boolean) updaterConfig.getOrDefault("auto-download", false);
                notifyConsole = (Boolean) updaterConfig.getOrDefault("notify-console", true);
                useMirror = (Boolean) updaterConfig.getOrDefault("use-mirror", true);
                customMirrorUrl = (String) updaterConfig.getOrDefault("custom-mirror-url", "");
            } else {
                updaterEnabled = false;
                checkInterval = 24;
                autoDownload = false;
                notifyConsole = true;
                useMirror = true;
                customMirrorUrl = "";
            }

            // 配置数据库连接池
            HikariConfig hikariConfig = new HikariConfig();
            String jdbcUrl = String.format("jdbc:mariadb://%s:%d/%s", host, port, database);
            logger.info("Connecting to database: " + jdbcUrl);
            
            hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(1);
            
            // 添加连接测试配置
            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setValidationTimeout(3000);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setAutoCommit(true);
            hikariConfig.addDataSourceProperty("useSSL", "false");
            hikariConfig.addDataSourceProperty("allowPublicKeyRetrieval", "true");

            try {
                Class.forName("org.mariadb.jdbc.Driver");
                dataSource = new HikariDataSource(hikariConfig);
                logger.info("Successfully connected to database!");
            } catch (Exception e) {
                logger.error("Failed to create connection pool", e);
                throw e;
            }
            
            // 创建白名单表（如果不存在）
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS whitelist (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "username VARCHAR(36) NOT NULL UNIQUE," +
                    "uuid VARCHAR(36) NULL" +
                    ")"
                );
                
                // 检查是否需要添加uuid列（向后兼容）
                try {
                    conn.createStatement().execute(
                        "ALTER TABLE whitelist ADD COLUMN uuid VARCHAR(36) NULL"
                    );
                    logger.info("Added uuid column to whitelist table");
                } catch (Exception e) {
                    // 列可能已经存在，忽略错误
                }
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS temporarylogin (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "username VARCHAR(36) NOT NULL UNIQUE," +
                    "request_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "status VARCHAR(20) DEFAULT 'pending'," +  // 状态：pending（等待中）, approved（已批准）, rejected（已拒绝）, timeout（超时）
                    "update_time TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP" +
                    ")"
                );
                logger.info("Database tables checked/created successfully");
            }

            // 启动定时清理任务
            server.getScheduler().buildTask(this, () -> {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM temporarylogin WHERE " +
                         "TIMESTAMPDIFF(SECOND, request_time, CURRENT_TIMESTAMP) > 90")) {
                    int deleted = stmt.executeUpdate();
                    if (deleted > 0) {
                        logger.info("Cleaned up " + deleted + " old temporary login requests");
                    }
                } catch (Exception e) {
                    logger.error("Failed to clean up old temporary login requests", e);
                }
            }).repeat(Duration.ofSeconds(30)).schedule();

            // 初始化自动更新器
            if (updaterEnabled) {
                Path pluginJar = getPluginJarPath();
                if (pluginJar != null) {
                    autoUpdater = new AutoUpdater(httpClient, logger, pluginJar, dataDirectory, useMirror, customMirrorUrl);
                    
                    // 检查是否有待应用的更新
                    autoUpdater.checkAndApplyPendingUpdate();
                    
                    // 启动定时检查任务
                    server.getScheduler().buildTask(this, this::checkForUpdates)
                            .repeat(Duration.ofHours(checkInterval))
                            .delay(Duration.ofMinutes(1)) // 启动后1分钟开始第一次检查
                            .schedule();
                    
                    if (notifyConsole) {
                        logger.info("自动更新器已启用，检查间隔: " + checkInterval + " 小时" + 
                                   (useMirror ? " (使用国内镜像)" : " (使用GitHub官方源)"));
                    }
                } else {
                    logger.warn("无法确定插件JAR路径，自动更新器已禁用");
                }
            }

            logger.info("WhitelistRMS plugin has been enabled!");
        } catch (Exception e) {
            logger.error("Failed to initialize plugin", e);
        }
    }

    private String getPlayerNameByUUID(String uuid) {
        try {
            String url = "https://api.mojang.com/user/profiles/" + uuid.replace("-", "") + "/names";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject[] names = gson.fromJson(response.body(), JsonObject[].class);
                if (names.length > 0) {
                    return names[names.length - 1].get("name").getAsString();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch player name for UUID " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    private String getUUIDByPlayerName(String playerName) {
        try {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject profile = gson.fromJson(response.body(), JsonObject.class);
                String uuid = profile.get("id").getAsString();
                return uuid.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5"
                );
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch UUID for player " + playerName + ": " + e.getMessage());
        }
        return null;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        UUID playerUuid = event.getPlayer().getUniqueId();
        
        try (Connection conn = dataSource.getConnection()) {
            boolean isWhitelisted = false;
            boolean needsUsernameUpdate = false;
            
            // 首先尝试按用户名匹配
            try (PreparedStatement stmt = conn.prepareStatement("SELECT uuid FROM whitelist WHERE username = ?")) {
                stmt.setString(1, username);
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    isWhitelisted = true;
                    logger.info("Player " + username + " was granted access (username match)");
                }
            }
            
            // 如果用户名匹配失败，尝试UUID匹配
            if (!isWhitelisted && playerUuid != null) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT username FROM whitelist WHERE uuid = ?")) {
                    stmt.setString(1, playerUuid.toString());
                    ResultSet rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        isWhitelisted = true;
                        needsUsernameUpdate = true;
                        String oldUsername = rs.getString("username");
                        logger.info("Player " + username + " was granted access (UUID match, old username: " + oldUsername + ")");
                    }
                }
            }
            
            // 如果通过UUID匹配成功，更新用户名
            if (isWhitelisted && needsUsernameUpdate) {
                try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE whitelist SET username = ? WHERE uuid = ?")) {
                    updateStmt.setString(1, username);
                    updateStmt.setString(2, playerUuid.toString());
                    updateStmt.executeUpdate();
                    logger.info("Updated username for UUID " + playerUuid + " from database to " + username);
                } catch (Exception e) {
                    logger.error("Failed to update username for UUID " + playerUuid, e);
                }
            }
            
            if (!isWhitelisted) {
                // 检查是否已经有未处理的请求
                try (PreparedStatement checkStmt = conn.prepareStatement("SELECT status FROM temporarylogin WHERE username = ?")) {
                    checkStmt.setString(1, username);
                    ResultSet checkRs = checkStmt.executeQuery();
                    
                    if (checkRs.next()) {
                        String status = checkRs.getString("status");
                        if ("pending".equals(status)) {
                            event.setResult(LoginEvent.ComponentResult.denied(Component.text("§e您的临时登录请求正在等待管理员审核中，请稍后再试！")));
                            return;
                        } else if ("rejected".equals(status)) {
                            event.setResult(LoginEvent.ComponentResult.denied(Component.text("§c您的临时登录请求已被管理员拒绝！")));
                            return;
                        } else if ("approved".equals(status)) {
                            // 临时登录请求已通过，允许登录
                            logger.info("Player " + username + " logged in with approved temporary access");
                            return;
                        } else if ("timeout".equals(status)) {
                            // 删除超时的请求，允许重新申请
                            try (PreparedStatement deleteStmt = conn.prepareStatement("DELETE FROM temporarylogin WHERE username = ?")) {
                                deleteStmt.setString(1, username);
                                deleteStmt.executeUpdate();
                            }
                        }
                    }
                }
                
                // 创建新的临时登录请求
                try (PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO temporarylogin (username, status) VALUES (?, 'pending')")) {
                    insertStmt.setString(1, username);
                    insertStmt.executeUpdate();
                }

                // 启动异步任务检查超时
                server.getScheduler().buildTask(this, () -> {
                    try (Connection timeoutConn = dataSource.getConnection();
                         PreparedStatement updateStmt = timeoutConn.prepareStatement(
                             "UPDATE temporarylogin SET status = 'timeout' WHERE username = ? AND status = 'pending'")) {
                        updateStmt.setString(1, username);
                        updateStmt.executeUpdate();
                        logger.info("Player " + username + " temporary login request timed out");
                    } catch (Exception e) {
                        logger.error("Failed to update timeout status for player " + username, e);
                    }
                }).delay(Duration.ofSeconds(60)).schedule();

                // 向玩家显示提示消息并拒绝连接
                event.setResult(LoginEvent.ComponentResult.denied(Component.text(
                    "§e您当前不在白名单中，但是您可以让管理员在白名单管理系统上允许您的临时登录请求！\n" +
                    "§e系统将在60秒内等待管理员的审核，请稍后重新连接服务器查看结果。"
                )));
                logger.info("Player " + username + " requested temporary login");
            }
        } catch (Exception e) {
            logger.error("Failed to check whitelist for player " + username, e);
            event.setResult(LoginEvent.ComponentResult.denied(Component.text("§c服务器错误，请联系管理员")));
        }
    }

    private Path getPluginJarPath() {
        try {
            // 尝试从类的代码源获取JAR路径
            String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            return Path.of(jarPath);
        } catch (Exception e) {
            logger.warn("无法自动获取JAR路径: " + e.getMessage());
            // 作为后备，尝试从环境变量或系统属性获取
            String pluginPath = System.getProperty("plugin.path");
            if (pluginPath != null) {
                return Path.of(pluginPath);
            }
            return null;
        }
    }

    private void checkForUpdates() {
        if (autoUpdater == null) {
            return;
        }
        
        autoUpdater.checkForUpdates().thenAccept(result -> {
            if (result.hasError()) {
                if (notifyConsole) {
                    logger.warn("检查更新时发生错误: " + result.getError());
                }
                return;
            }
            
            if (result.hasUpdate()) {
                if (notifyConsole) {
                    logger.info("发现新版本: " + result.getLatestVersion() + " (当前版本: " + getCurrentVersionString() + ")");
                }
                
                if (autoDownload) {
                    logger.info("开始自动下载更新...");
                    autoUpdater.downloadAndUpdate(result.getLatestVersion()).thenAccept(success -> {
                        if (success) {
                            logger.info("更新下载完成！服务器重启后将自动更新到版本 " + result.getLatestVersion());
                        } else {
                            logger.error("更新下载失败");
                        }
                    });
                } else {
                    logger.info("自动下载已禁用，请手动更新到版本 " + result.getLatestVersion());
                }
            } else {
                if (notifyConsole) {
                    logger.info("当前已是最新版本");
                }
            }
        });
    }

    private String getCurrentVersionString() {
        try {
            Path versionFile = dataDirectory.getParent().resolve("plugin.version");
            if (Files.exists(versionFile)) {
                String content = Files.readString(versionFile).trim();
                if (content.startsWith("V ")) {
                    return content.substring(2).trim();
                }
                return content;
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return "1.1.1-SNAPSHOT"; // 后备版本
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
