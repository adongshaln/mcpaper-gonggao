package com.adongcraft.gonggao;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Curated player guide, with discovery of announcements from additional installed plugins. */
final class NoticeCatalog {
    private final JavaPlugin plugin;
    NoticeCatalog(JavaPlugin plugin) { this.plugin = plugin; }

    List<Notice> load() throws Exception {
        YamlConfiguration guide = new YamlConfiguration();
        guide.load(new File(plugin.getDataFolder(), "guide.yml"));
        List<Notice> notices = new ArrayList<>();
        Set<String> covered = new HashSet<>(), ids = new HashSet<>();
        for (Map<?, ?> entry : guide.getMapList("topics")) {
            String name = text(entry.get("plugin"));
            Plugin source = name == null ? null : plugin.getServer().getPluginManager().getPlugin(name);
            if (source == null || !source.isEnabled()) continue;
            Notice notice = notice(name, 100, entry);
            if (!ids.add(notice.id())) throw new IllegalArgumentException("重复的介绍 ID：" + notice.id());
            notices.add(notice); covered.add(name);
        }
        // Extra plugins can still ship gonggao.yml; curated introductions replace their old entries.
        for (Plugin source : plugin.getServer().getPluginManager().getPlugins()) {
            if (!source.isEnabled() || source == plugin || covered.contains(source.getName())) continue;
            YamlConfiguration yaml = readExtra(source);
            if (yaml == null) continue;
            for (Map<?, ?> entry : yaml.getMapList("announcements")) {
                try {
                    Notice notice = notice(source.getName(), yaml.getInt("order", 200), entry);
                    if (ids.add(notice.id())) notices.add(notice);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning(source.getName() + " 的公告已跳过：" + ex.getMessage());
                }
            }
        }
        notices.sort(Comparator.comparingInt(Notice::order).thenComparing(Notice::id));
        return List.copyOf(notices);
    }

    private YamlConfiguration readExtra(Plugin source) {
        try {
            File override = new File(source.getDataFolder(), "gonggao.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            if (override.isFile()) { yaml.load(override); return yaml; }
            try (InputStream in = source.getClass().getClassLoader().getResourceAsStream("gonggao.yml")) {
                if (in == null) return null;
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8)); return yaml;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("读不到 " + source.getName() + " 的公告：" + ex.getMessage()); return null;
        }
    }

    private static Notice notice(String source, int defaultOrder, Map<?, ?> entry) {
        String key = text(entry.get("id")), title = text(entry.get("title"));
        List<String> pages = new ArrayList<>();
        Object content = entry.get("pages");
        if (content instanceof List<?> list) {
            for (Object value : list) { String page = text(value); if (page != null) pages.add(page); }
        } else if (text(content) != null) pages.add(text(content));
        if (key == null || !key.matches("[A-Za-z0-9_-]+") || title == null || pages.isEmpty())
            throw new IllegalArgumentException("介绍需要合法的 id、title 和 pages（来源：" + source + "）");
        int order = entry.get("order") instanceof Number number ? number.intValue() : defaultOrder;
        String summary = text(entry.get("summary"));
        return new Notice(source + ":" + key, key, source, title, summary == null ? "点击阅读功能与用法" : summary, List.copyOf(pages), order);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().strip(); return text.isEmpty() ? null : text;
    }
}
