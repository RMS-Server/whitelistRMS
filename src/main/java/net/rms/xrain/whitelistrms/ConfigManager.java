package net.rms.xrain.whitelistrms;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigManager {
    private final Logger logger;
    private final Path configPath;
    private Map<String, Object> config;
    
    public ConfigManager(Logger logger, Path configPath) {
        this.logger = logger;
        this.configPath = configPath;
    }
    
    public Map<String, Object> loadAndValidateConfig() {
        try {
            // 加载现有配置
            Yaml yaml = new Yaml();
            if (Files.exists(configPath)) {
                config = yaml.load(new FileInputStream(configPath.toFile()));
                if (config == null) {
                    config = new LinkedHashMap<>();
                }
            } else {
                config = new LinkedHashMap<>();
            }
            
            // 检查并补齐缺失的配置项
            boolean needsUpdate = false;
            
            // MySQL配置
            if (!config.containsKey("mysql")) {
                config.put("mysql", getDefaultMysqlConfig());
                needsUpdate = true;
                logger.info("已添加默认MySQL配置");
            } else {
                Map<String, Object> mysql = (Map<String, Object>) config.get("mysql");
                if (validateAndFixMysqlConfig(mysql)) {
                    needsUpdate = true;
                }
            }
            
            // 消息配置
            if (!config.containsKey("messages")) {
                config.put("messages", getDefaultMessagesConfig());
                needsUpdate = true;
                logger.info("已添加默认消息配置");
            } else {
                Map<String, Object> messages = (Map<String, Object>) config.get("messages");
                if (validateAndFixMessagesConfig(messages)) {
                    needsUpdate = true;
                }
            }
            
            // 更新器配置
            if (!config.containsKey("updater")) {
                config.put("updater", getDefaultUpdaterConfig());
                needsUpdate = true;
                logger.info("已添加默认更新器配置");
            } else {
                Map<String, Object> updater = (Map<String, Object>) config.get("updater");
                if (validateAndFixUpdaterConfig(updater)) {
                    needsUpdate = true;
                }
            }
            
            // 如果有更新，保存配置文件
            if (needsUpdate) {
                saveConfig();
                logger.info("配置文件已更新并保存");
            }
            
            return config;
            
        } catch (Exception e) {
            logger.error("加载配置文件失败", e);
            return getDefaultConfig();
        }
    }
    
    private Map<String, Object> getDefaultMysqlConfig() {
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("host", "127.0.0.1");
        mysql.put("port", 3306);
        mysql.put("database", "minecraft");
        mysql.put("username", "root");
        mysql.put("password", "root");
        mysql.put("table", "whitelist");
        return mysql;
    }
    
    private Map<String, Object> getDefaultMessagesConfig() {
        Map<String, Object> messages = new LinkedHashMap<>();
        messages.put("not-whitelisted", "§c你不在白名单中！请联系管理员");
        return messages;
    }
    
    private Map<String, Object> getDefaultUpdaterConfig() {
        Map<String, Object> updater = new LinkedHashMap<>();
        updater.put("enabled", true);
        updater.put("check-interval", 24);
        updater.put("auto-download", true);
        updater.put("notify-console", true);
        updater.put("use-mirror", true);
        updater.put("custom-mirror-url", "");
        return updater;
    }
    
    private boolean validateAndFixMysqlConfig(Map<String, Object> mysql) {
        boolean updated = false;
        Map<String, Object> defaults = getDefaultMysqlConfig();
        
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!mysql.containsKey(entry.getKey())) {
                mysql.put(entry.getKey(), entry.getValue());
                updated = true;
                logger.info("已添加缺失的MySQL配置项: " + entry.getKey());
            }
        }
        
        return updated;
    }
    
    private boolean validateAndFixMessagesConfig(Map<String, Object> messages) {
        boolean updated = false;
        Map<String, Object> defaults = getDefaultMessagesConfig();
        
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!messages.containsKey(entry.getKey())) {
                messages.put(entry.getKey(), entry.getValue());
                updated = true;
                logger.info("已添加缺失的消息配置项: " + entry.getKey());
            }
        }
        
        return updated;
    }
    
    private boolean validateAndFixUpdaterConfig(Map<String, Object> updater) {
        boolean updated = false;
        Map<String, Object> defaults = getDefaultUpdaterConfig();
        
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!updater.containsKey(entry.getKey())) {
                updater.put(entry.getKey(), entry.getValue());
                updated = true;
                logger.info("已添加缺失的更新器配置项: " + entry.getKey());
            }
        }
        
        return updated;
    }
    
    private Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mysql", getDefaultMysqlConfig());
        config.put("messages", getDefaultMessagesConfig());
        config.put("updater", getDefaultUpdaterConfig());
        return config;
    }
    
    private void saveConfig() throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        
        Yaml yaml = new Yaml(options);
        
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            // 添加注释头
            writer.write("# WhitelistRMS 配置文件\n");
            writer.write("# 此文件会在插件启动时自动检查并补齐缺失的配置项\n\n");
            
            // 写入配置
            yaml.dump(config, writer);
        }
    }
}