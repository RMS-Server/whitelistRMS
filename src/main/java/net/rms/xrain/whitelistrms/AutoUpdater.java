package net.rms.xrain.whitelistrms;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import org.slf4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class AutoUpdater {
    private static final String GITHUB_REPO = "RMS-Server/whitelistRMS";
    
    // 默认GitHub URLs
    private static final String DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/" + GITHUB_REPO + "/master/plugin.version";
    private static final String DEFAULT_RELEASES_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    
    // 国内镜像URLs
    private static final String MIRROR_VERSION_URL = "https://ghproxy.com/https://raw.githubusercontent.com/" + GITHUB_REPO + "/master/plugin.version";
    private static final String MIRROR_RELEASES_URL = "https://ghproxy.com/https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    
    private final HttpClient httpClient;
    private final Logger logger;
    private final Path pluginPath;
    private final Path dataDirectory;
    private String currentVersion;
    private boolean useMirror;
    private String customMirrorUrl;
    
    public AutoUpdater(HttpClient httpClient, Logger logger, Path pluginPath, Path dataDirectory) {
        this.httpClient = httpClient;
        this.logger = logger;
        this.pluginPath = pluginPath;
        this.dataDirectory = dataDirectory;
        this.currentVersion = getCurrentVersion();
        this.useMirror = false;
        this.customMirrorUrl = null;
    }
    
    public AutoUpdater(HttpClient httpClient, Logger logger, Path pluginPath, Path dataDirectory, boolean useMirror, String customMirrorUrl) {
        this.httpClient = httpClient;
        this.logger = logger;
        this.pluginPath = pluginPath;
        this.dataDirectory = dataDirectory;
        this.currentVersion = getCurrentVersion();
        this.useMirror = useMirror;
        this.customMirrorUrl = customMirrorUrl;
    }
    
    private String getCurrentVersion() {
        try {
            Path versionFile = pluginPath.getParent().resolve("plugin.version");
            if (Files.exists(versionFile)) {
                String content = Files.readString(versionFile).trim();
                if (content.startsWith("V ")) {
                    return content.substring(2).trim();
                }
                return content;
            }
        } catch (Exception e) {
            logger.warn("无法读取当前版本文件: " + e.getMessage());
        }
        
        // 从注解中获取版本作为后备
        return "1.1.1-SNAPSHOT";
    }
    
    private String getVersionUrl() {
        if (customMirrorUrl != null && !customMirrorUrl.isEmpty()) {
            return customMirrorUrl + "/raw.githubusercontent.com/" + GITHUB_REPO + "/master/plugin.version";
        }
        return useMirror ? MIRROR_VERSION_URL : DEFAULT_VERSION_URL;
    }
    
    private String getReleasesUrl() {
        if (customMirrorUrl != null && !customMirrorUrl.isEmpty()) {
            return customMirrorUrl + "/api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
        }
        return useMirror ? MIRROR_RELEASES_URL : DEFAULT_RELEASES_URL;
    }
    
    private String getMirrorDownloadUrl(String originalUrl) {
        if (customMirrorUrl != null && !customMirrorUrl.isEmpty()) {
            return originalUrl.replace("https://github.com/", customMirrorUrl + "/github.com/");
        }
        if (useMirror) {
            return "https://ghproxy.com/" + originalUrl;
        }
        return originalUrl;
    }
    
    public CompletableFuture<VersionCheckResult> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("正在检查更新...");
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getVersionUrl()))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String remoteVersionContent = response.body().trim();
                    String remoteVersion;
                    
                    if (remoteVersionContent.startsWith("V ")) {
                        remoteVersion = remoteVersionContent.substring(2).trim();
                    } else {
                        remoteVersion = remoteVersionContent;
                    }
                    
                    logger.info("当前版本: " + currentVersion + ", 远程版本: " + remoteVersion);
                    
                    if (isNewerVersion(remoteVersion, currentVersion)) {
                        logger.info("发现新版本: " + remoteVersion);
                        return new VersionCheckResult(true, remoteVersion, null);
                    } else {
                        logger.info("已是最新版本");
                        return new VersionCheckResult(false, remoteVersion, null);
                    }
                } else {
                    logger.warn("版本检查失败，HTTP状态码: " + response.statusCode());
                    return new VersionCheckResult(false, null, "HTTP错误: " + response.statusCode());
                }
            } catch (Exception e) {
                logger.error("检查更新时发生错误", e);
                return new VersionCheckResult(false, null, e.getMessage());
            }
        });
    }
    
    public CompletableFuture<Boolean> downloadAndUpdate(String version) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("正在下载版本 " + version + "...");
                
                // 获取最新发布信息
                HttpRequest releaseRequest = HttpRequest.newBuilder()
                        .uri(URI.create(getReleasesUrl()))
                        .timeout(Duration.ofSeconds(30))
                        .build();
                
                HttpResponse<String> releaseResponse = httpClient.send(releaseRequest, HttpResponse.BodyHandlers.ofString());
                
                if (releaseResponse.statusCode() != 200) {
                    logger.error("获取发布信息失败，HTTP状态码: " + releaseResponse.statusCode());
                    return false;
                }
                
                JsonObject release = JsonParser.parseString(releaseResponse.body()).getAsJsonObject();
                JsonArray assets = release.getAsJsonArray("assets");
                
                String downloadUrl = null;
                for (int i = 0; i < assets.size(); i++) {
                    JsonObject asset = assets.get(i).getAsJsonObject();
                    String name = asset.get("name").getAsString();
                    if (name.endsWith(".jar") && !name.contains("sources")) {
                        downloadUrl = getMirrorDownloadUrl(asset.get("browser_download_url").getAsString());
                        break;
                    }
                }
                
                if (downloadUrl == null) {
                    logger.error("未找到可下载的JAR文件");
                    return false;
                }
                
                logger.info("下载地址: " + downloadUrl);
                
                // 下载新版本
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofMinutes(5))
                        .build();
                
                HttpResponse<InputStream> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
                
                if (downloadResponse.statusCode() != 200) {
                    logger.error("下载失败，HTTP状态码: " + downloadResponse.statusCode());
                    return false;
                }
                
                // 保存到临时文件
                Path tempFile = dataDirectory.resolve("whitelistRMS-" + version + ".jar.tmp");
                try (InputStream in = downloadResponse.body()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                
                // 创建更新脚本
                createUpdateScript(tempFile, version);
                
                logger.info("新版本已下载完成，将在服务器重启后自动更新");
                return true;
                
            } catch (Exception e) {
                logger.error("下载更新时发生错误", e);
                return false;
            }
        });
    }
    
    private void createUpdateScript(Path newJarPath, String version) throws IOException {
        Path updateScript = dataDirectory.resolve("update.txt");
        String updateInfo = "NEW_VERSION=" + version + "\n" +
                           "NEW_JAR_PATH=" + newJarPath.toString() + "\n" +
                           "CURRENT_JAR_PATH=" + pluginPath.toString() + "\n" +
                           "UPDATE_TIME=" + System.currentTimeMillis();
        
        Files.writeString(updateScript, updateInfo);
        logger.info("更新脚本已创建: " + updateScript);
    }
    
    public void checkAndApplyPendingUpdate() {
        try {
            Path updateScript = dataDirectory.resolve("update.txt");
            if (!Files.exists(updateScript)) {
                return;
            }
            
            String content = Files.readString(updateScript);
            String[] lines = content.split("\n");
            String newVersion = null;
            String newJarPath = null;
            String currentJarPath = null;
            
            for (String line : lines) {
                if (line.startsWith("NEW_VERSION=")) {
                    newVersion = line.substring("NEW_VERSION=".length());
                } else if (line.startsWith("NEW_JAR_PATH=")) {
                    newJarPath = line.substring("NEW_JAR_PATH=".length());
                } else if (line.startsWith("CURRENT_JAR_PATH=")) {
                    currentJarPath = line.substring("CURRENT_JAR_PATH=".length());
                }
            }
            
            if (newVersion != null && newJarPath != null && currentJarPath != null) {
                Path newJar = Path.of(newJarPath);
                Path currentJar = Path.of(currentJarPath);
                
                if (Files.exists(newJar)) {
                    // 备份当前版本
                    Path backup = currentJar.getParent().resolve("whitelistRMS-backup.jar");
                    Files.copy(currentJar, backup, StandardCopyOption.REPLACE_EXISTING);
                    
                    // 应用更新
                    Files.copy(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                    
                    // 清理临时文件
                    Files.deleteIfExists(newJar);
                    Files.deleteIfExists(updateScript);
                    
                    // 更新版本文件
                    updateVersionFile(newVersion);
                    
                    logger.info("插件已成功更新到版本 " + newVersion);
                } else {
                    logger.warn("更新文件不存在: " + newJarPath);
                    Files.deleteIfExists(updateScript);
                }
            }
        } catch (Exception e) {
            logger.error("应用更新时发生错误", e);
        }
    }
    
    private void updateVersionFile(String version) throws IOException {
        Path versionFile = pluginPath.getParent().resolve("plugin.version");
        Files.writeString(versionFile, "V " + version);
    }
    
    private boolean isNewerVersion(String remote, String current) {
        try {
            // 移除SNAPSHOT后缀进行比较
            String remoteClean = remote.replace("-SNAPSHOT", "");
            String currentClean = current.replace("-SNAPSHOT", "");
            
            String[] remoteParts = remoteClean.split("\\.");
            String[] currentParts = currentClean.split("\\.");
            
            int maxLength = Math.max(remoteParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int remoteNum = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                int currentNum = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                
                if (remoteNum > currentNum) {
                    return true;
                } else if (remoteNum < currentNum) {
                    return false;
                }
            }
            
            return false;
        } catch (Exception e) {
            logger.warn("版本比较失败: " + e.getMessage());
            return false;
        }
    }
    
    public static class VersionCheckResult {
        private final boolean hasUpdate;
        private final String latestVersion;
        private final String error;
        
        public VersionCheckResult(boolean hasUpdate, String latestVersion, String error) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.error = error;
        }
        
        public boolean hasUpdate() {
            return hasUpdate;
        }
        
        public String getLatestVersion() {
            return latestVersion;
        }
        
        public String getError() {
            return error;
        }
        
        public boolean hasError() {
            return error != null;
        }
    }
}