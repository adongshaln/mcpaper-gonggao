package com.adongcraft.gonggao;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

final class ReadLedger {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Set<String>> read = new HashMap<>();
    private final Map<UUID, String> open = new HashMap<>();
    private boolean dirty;

    ReadLedger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "read.yml");
    }

    void load() {
        read.clear();
        if (!file.isFile()) {
            return;
        }
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("read");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                read.put(UUID.fromString(key), new HashSet<>(section.getStringList(key)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("跳过无效的公告读者：" + key);
            }
        }
    }

    boolean hasRead(UUID playerId, String noticeId) {
        Set<String> notices = read.get(playerId);
        return notices != null && notices.contains(noticeId);
    }

    void markRead(UUID playerId, String noticeId) {
        read.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(noticeId);
        open.remove(playerId);
        dirty = true;
    }

    void rememberOpen(UUID playerId, String noticeId) {
        open.put(playerId, noticeId);
    }

    String openId(UUID playerId) {
        return open.get(playerId);
    }

    void forgetOpen(UUID playerId) {
        open.remove(playerId);
    }

    void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : read.entrySet()) {
            yaml.set("read." + entry.getKey(), List.copyOf(entry.getValue()));
        }
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法保存公告阅读记录", exception);
        }
    }
}
