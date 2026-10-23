package com.adongcraft.gonggao;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class NoticeCatalog {
    private final JavaPlugin plugin;

    NoticeCatalog(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    List<Notice> load() {
        List<Notice> notices = new ArrayList<>();
        for (Plugin source : plugin.getServer().getPluginManager().getPlugins()) {
            YamlConfiguration yaml = read(source);
            if (yaml == null) {
                continue;
            }
            int order = yaml.getInt("order", 100);
            for (Map<?, ?> entry : yaml.getMapList("announcements")) {
                Notice notice = notice(source, order, entry);
                if (notice != null) {
                    notices.add(notice);
                }
            }
        }
        notices.sort(Comparator.comparingInt(Notice::order).thenComparing(Notice::id));
        return List.copyOf(notices);
    }

    private YamlConfiguration read(Plugin source) {
        File override = new File(source.getDataFolder(), "gonggao.yml");
        if (override.isFile()) {
            return YamlConfiguration.loadConfiguration(override);
        }
        ClassLoader loader = source.getClass().getClassLoader();
        try (InputStream in = loader.getResourceAsStream("gonggao.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.getLogger().warning("读不到 " + source.getName() + " 的公告：" + exception.getMessage());
            return null;
        }
    }

    private Notice notice(Plugin source, int order, Map<?, ?> entry) {
        String localId = text(entry.get("id"));
        String title = text(entry.get("title"));
        List<String> pages = pages(entry.get("pages"));
        if (localId == null || !localId.matches("[A-Za-z0-9_-]+") || title == null || pages.isEmpty()) {
            plugin.getLogger().warning(source.getName() + " 有一条公告格式不对，已跳过");
            return null;
        }
        return new Notice(source.getName() + ":" + localId, title, pages, order);
    }

    private static List<String> pages(Object value) {
        List<String> pages = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object page : list) {
                String text = text(page);
                if (text != null && !text.isBlank()) {
                    pages.add(text.strip());
                }
            }
        } else {
            String text = text(value);
            if (text != null && !text.isBlank()) {
                pages.add(text.strip());
            }
        }
        return pages;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}

record Notice(String id, String title, List<String> pages, int order) {
}
