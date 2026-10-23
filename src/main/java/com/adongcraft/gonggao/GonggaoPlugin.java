package com.adongcraft.gonggao;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.List;

public final class GonggaoPlugin extends JavaPlugin implements CommandExecutor, Listener {
    private final ReadLedger ledger = new ReadLedger(this);
    private final NoticeCatalog catalog = new NoticeCatalog(this);

    @Override
    public void onEnable() {
        ledger.load();
        var command = getCommand("gonggao");
        if (command != null) {
            command.setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleOpen(player);
        }
        getLogger().info("公告已启用，读到 " + catalog.load().size() + " 条");
    }

    @Override
    public void onDisable() {
        ledger.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("这个命令只能由玩家使用。", NamedTextColor.RED));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("next")) {
            acknowledge(player);
            return true;
        }
        openNext(player, true);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scheduleOpen(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ledger.forgetOpen(event.getPlayer().getUniqueId());
    }

    private void scheduleOpen(Player player) {
        new BukkitRunnable() {
            private int tries;

            @Override
            public void run() {
                if (!player.isOnline() || ++tries > 90) {
                    cancel();
                    return;
                }
                if (!authenticated(player)) {
                    return;
                }
                cancel();
                openNext(player, false);
            }
        }.runTaskTimer(this, 40L, 20L);
    }

    private boolean openNext(Player player, boolean fromCommand) {
        List<Notice> unread = catalog.load().stream()
                .filter(notice -> !ledger.hasRead(player.getUniqueId(), notice.id()))
                .toList();
        if (unread.isEmpty()) {
            ledger.forgetOpen(player.getUniqueId());
            if (fromCommand) {
                player.sendMessage(line("没有新的公告。", NamedTextColor.GRAY));
            }
            return false;
        }
        Notice notice = unread.getFirst();
        ledger.rememberOpen(player.getUniqueId(), notice.id());
        player.openBook(book(notice));
        player.sendMessage(line("新功能：" + notice.title(), NamedTextColor.GOLD));
        return true;
    }

    private void acknowledge(Player player) {
        String openId = ledger.openId(player.getUniqueId());
        if (openId == null || ledger.hasRead(player.getUniqueId(), openId)) {
            openNext(player, true);
            return;
        }
        ledger.markRead(player.getUniqueId(), openId);
        ledger.save();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!openNext(player, false)) {
                player.sendMessage(line("新功能都看完了。", NamedTextColor.GRAY));
            }
        }, 20L);
    }

    private ItemStack book(Notice notice) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(line("新功能！", NamedTextColor.GOLD));
        meta.author(line(trim(notice.title(), 16), NamedTextColor.DARK_AQUA));
        meta.addPages(line("新功能！", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(line(notice.title(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(line("向右翻页。", NamedTextColor.GRAY)));
        for (String page : notice.pages()) {
            meta.addPages(line(page, NamedTextColor.BLACK));
        }
        meta.addPages(line("新功能！", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(line(notice.title(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(line("下一条", NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.callback(this::onRead, options -> options.uses(1).lifetime(Duration.ofMinutes(30))))));
        book.setItemMeta(meta);
        return book;
    }

    private void onRead(Audience audience) {
        Player reader = audience instanceof Player player ? player : audience.get(Identity.UUID).map(Bukkit::getPlayer).orElse(null);
        if (reader == null) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> acknowledge(reader));
    }

    private static Component line(String text, NamedTextColor color, TextDecoration... decorations) {
        Style style = Style.style(color).decoration(TextDecoration.ITALIC, false);
        for (TextDecoration decoration : decorations) {
            style = style.decoration(decoration, true);
        }
        return Component.text(text, style);
    }

    private static String trim(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit);
    }

    private boolean authenticated(Player player) {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) {
            return true;
        }
        try {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object authenticated = api.getMethod("isAuthenticated", Player.class).invoke(instance, player);
            return Boolean.TRUE.equals(authenticated);
        } catch (ReflectiveOperationException exception) {
            return true;
        }
    }
}
