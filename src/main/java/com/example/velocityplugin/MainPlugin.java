package com.example.velocityplugin;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.time.Duration;

@Plugin(
    id = "whitelist-rms",
    name = "WhitelistRMS",
    version = "1.1-SNAPSHOT",
    description = "A whitelist plugin that reads from MySQL database",
    authors = {"XRain666"}
)
public class MainPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private HikariDataSource dataSource;
    private String notWhitelistedMessage;

    @Inject
    public MainPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
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
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(new FileInputStream(configPath.toFile()));
            
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
                    "username VARCHAR(36) NOT NULL UNIQUE" +
                    ")"
                );
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

            logger.info("WhitelistRMS plugin has been enabled!");
        } catch (Exception e) {
            logger.error("Failed to initialize plugin", e);
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM whitelist WHERE username = ?")) {
            
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            
            if (!rs.next()) {
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
                    "§e您当前不在白名单中，但是您可以让管理员在RMS白名单管理系统上允许您的临时登录请求！\n" +
                    "§e系统将在60秒内等待管理员的审核，请稍后重新连接服务器查看结果。"
                )));
                logger.info("Player " + username + " requested temporary login");
            } else {
                logger.info("Player " + username + " was granted access (in whitelist)");
            }
        } catch (Exception e) {
            logger.error("Failed to check whitelist for player " + username, e);
            event.setResult(LoginEvent.ComponentResult.denied(Component.text("§c服务器错误，请联系管理员")));
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
