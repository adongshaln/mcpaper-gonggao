package com.adongcraft.gonggao;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class GonggaoPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private final ReadLedger ledger = new ReadLedger(this);
    private final NoticeCatalog catalog = new NoticeCatalog(this);
    private final Map<UUID, BukkitTask> reminders = new HashMap<>();
    private List<Notice> notices = List.of();
    private boolean authWarning;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!new java.io.File(getDataFolder(), "guide.yml").exists()) saveResource("guide.yml", false);
        ledger.load(); reloadCatalog();
        var command = Objects.requireNonNull(getCommand("gonggao"));
        command.setExecutor(this); command.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) scheduleReminder(player);
    }
    @Override public void onDisable() {
        reminders.values().forEach(BukkitTask::cancel); reminders.clear(); ledger.save();
    }
    private boolean reloadCatalog() {
        try {
            List<Notice> next = catalog.load();
            for (Notice notice : next) if (BookLayout.paginate(notice.pages()).size() > 100)
                throw new IllegalArgumentException("单项介绍超过 100 页：" + notice.id());
            notices = next;
            getLogger().info("功能手册已加载：" + notices.size() + " 项，来自 " + notices.stream().map(Notice::source).distinct().count() + " 个插件");
            return true;
        } catch (Exception ex) { getLogger().log(Level.SEVERE, "功能手册加载失败，保留已加载内容", ex); return false; }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("gonggao.reload")) { sender.sendMessage(line("你没有重载手册的权限。", NamedTextColor.RED)); return true; }
            reloadConfig();
            sender.sendMessage(line(reloadCatalog() ? "功能手册已更新。" : "加载失败，原内容已保留，请检查配置。", NamedTextColor.GRAY)); return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("玩家使用 /gonggao 打开手册；控制台可用 /gonggao reload。"); return true; }
        if (!authenticated(player)) { player.sendMessage(line("请先完成登录。", NamedTextColor.GRAY)); return true; }
        String query = String.join(" ", args).strip();
        if (query.isEmpty() || query.equalsIgnoreCase("all")) openIndex(player, false, "");
        else if (query.equalsIgnoreCase("unread") || query.equalsIgnoreCase("next")) openIndex(player, true, "");
        else {
            Optional<Notice> exact = notices.stream().filter(n -> n.key().equalsIgnoreCase(query) || n.id().equalsIgnoreCase(query) || n.title().equals(query)).findFirst();
            if (exact.isPresent()) openTopic(player, exact.get(), false); else openIndex(player, false, query);
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>(List.of("all", "unread"));
        if (sender.hasPermission("gonggao.reload")) choices.add("reload");
        notices.forEach(n -> { choices.add(n.key()); choices.add(n.title()); });
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().toList();
    }
    private boolean unread(Player player, Notice notice) { return !ledger.hasRead(player.getUniqueId(), notice.readKey()); }
    private void openIndex(Player player, boolean onlyUnread, String query) {
        String term = query.toLowerCase(Locale.ROOT);
        List<Notice> shown = notices.stream().filter(n -> !onlyUnread || unread(player, n))
                .filter(n -> term.isEmpty() || (n.title()+" "+n.summary()+" "+n.key()+" "+String.join(" ", n.pages())).toLowerCase(Locale.ROOT).contains(term)).toList();
        List<Component> pages = new ArrayList<>();
        if (shown.isEmpty()) pages.add(heading("Adongcraft", "功能手册")
                .append(line(onlyUnread ? "已看完全部介绍。" : query.isEmpty() ? "暂无可用介绍。" : "没有找到相关内容。", NamedTextColor.BLACK))
                .append(Component.text("\n\n")).append(button(player, "[查看全部]", p -> openIndex(p, false, ""))));
        for (int start = 0; start < shown.size(); start += 7) {
            Component page = heading("Adongcraft", onlyUnread ? "尚未阅读" : term.isEmpty() ? "功能手册" : "搜索结果");
            for (Notice notice : shown.subList(start, Math.min(start+7, shown.size()))) {
                page = page.append(line(unread(player, notice) ? "● " : "· ", unread(player, notice) ? NamedTextColor.DARK_GREEN : NamedTextColor.GRAY))
                        .append(button(player, BookLayout.shorten(notice.title(), 89), p -> openTopic(p, notice, onlyUnread))
                                .hoverEvent(HoverEvent.showText(Component.text(notice.summary())))).append(Component.newline());
            }
            page = page.append(Component.newline()).append(button(player, onlyUnread ? "[全部介绍]" : "[只看更新]", p -> openIndex(p, !onlyUnread, "")))
                    .append(Component.newline()).append(line("● 尚未阅读或有更新", NamedTextColor.DARK_GRAY));
            pages.add(page);
        }
        openBook(player, "功能手册", pages);
    }
    private void openTopic(Player player, Notice requested, boolean onlyUnread) {
        Notice notice = notices.stream().filter(n -> n.id().equals(requested.id())).findFirst().orElse(null);
        if (notice == null) { openIndex(player, onlyUnread, ""); return; }
        List<String> body = BookLayout.paginate(notice.pages()); List<Component> pages = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            Component page = line(BookLayout.shorten(notice.title(), 78), NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD)
                    .append(line(" " + (i+1) + "/" + body.size(), NamedTextColor.DARK_GRAY))
                    .append(Component.text("\n\n")).append(line(body.get(i), NamedTextColor.BLACK));
            int count = body.get(i).split("\n", -1).length;
            page = page.append(Component.text("\n".repeat(BookLayout.BODY_LINES-count+1))).append(button(player, "[目录]", p -> openIndex(p, onlyUnread, "")));
            if (i == body.size()-1) page = page.append(Component.text(" ")).append(button(player, "[看完了]", p -> {
                ledger.markRead(p.getUniqueId(), notice.readKey()); ledger.save(); openIndex(p, onlyUnread, "");
            }));
            pages.add(page);
        }
        openBook(player, notice.title(), pages);
    }
    private void openBook(Player player, String title, List<Component> pages) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK); BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(line(BookLayout.shorten(title, 110), NamedTextColor.DARK_AQUA)); meta.author(Component.text("Adongcraft"));
        meta.pages(pages); book.setItemMeta(meta); player.openBook(book);
    }
    private Component button(Player owner, String text, Consumer<Player> action) {
        return line(text, NamedTextColor.DARK_GREEN).clickEvent(ClickEvent.callback(audience -> {
            UUID id = audience instanceof Player player ? player.getUniqueId() : audience.get(Identity.UUID).orElse(null);
            if (!owner.getUniqueId().equals(id)) return;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player player = Bukkit.getPlayer(id); if (player != null && authenticated(player)) action.accept(player);
            }, 3L);
        }, options -> options.uses(-1).lifetime(Duration.ofMinutes(30))));
    }
    private static Component heading(String title, String subtitle) {
        return line(title, NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD).append(Component.newline())
                .append(line(subtitle, NamedTextColor.DARK_GRAY)).append(Component.text("\n\n"));
    }
    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { scheduleReminder(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        BukkitTask task = reminders.remove(event.getPlayer().getUniqueId()); if (task != null) task.cancel();
    }
    private void scheduleReminder(Player player) {
        if (!getConfig().getBoolean("join-reminder", true)) return;
        UUID id = player.getUniqueId(); BukkitTask old = reminders.remove(id); if (old != null) old.cancel();
        BukkitTask task = new BukkitRunnable() {
            int tries;
            @Override public void run() {
                if (!player.isOnline() || ++tries > 90) { cancel(); reminders.remove(id); return; }
                if (!authenticated(player)) return;
                cancel(); reminders.remove(id);
                long count = notices.stream().filter(n -> unread(player, n)).count();
                if (count > 0) player.sendMessage(line("Adongcraft · 有 " + count + " 项功能介绍尚未阅读或已更新  ", NamedTextColor.GRAY)
                        .append(button(player, "[打开手册]", p -> openIndex(p, true, ""))));
            }
        }.runTaskTimer(this, Math.max(20, getConfig().getLong("join-delay-ticks", 60)), 20);
        reminders.put(id, task);
    }
    private boolean authenticated(Player player) {
        var auth = Bukkit.getPluginManager().getPlugin("AuthMe"); if (auth == null || !auth.isEnabled()) return true;
        try {
            Class<?> api = auth.getClass().getClassLoader().loadClass("fr.xephi.authme.api.v3.AuthMeApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isAuthenticated", Player.class).invoke(instance, player));
        } catch (ReflectiveOperationException ex) {
            if (!authWarning) { getLogger().warning("无法确认 AuthMe 登录状态，暂不发送手册提醒：" + ex.getMessage()); authWarning = true; }
            return false;
        }
    }
}
