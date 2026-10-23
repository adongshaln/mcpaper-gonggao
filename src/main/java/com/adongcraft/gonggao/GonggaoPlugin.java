package com.adongcraft.gonggao;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
                player.sendMessage(Component.text("没有新的公告。", NamedTextColor.GRAY));
            }
            return false;
        }
        Notice notice = unread.getFirst();
        ledger.rememberOpen(player.getUniqueId(), notice.id());
        player.openBook(book(notice));
        player.sendMessage(Component.text("有一条公告：" + notice.title() + "。看完后点最后一页的「我已阅读」。", NamedTextColor.GOLD));
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
        if (!openNext(player, false)) {
            player.sendMessage(Component.text("公告都看完了。", NamedTextColor.GRAY));
        }
    }

    private ItemStack book(Notice notice) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text(trim(notice.title(), 32)));
        meta.author(Component.text("公告"));
        for (String page : notice.pages()) {
            meta.addPages(Component.text(page));
        }
        meta.addPages(Component.text("看完了就点下面。\n还有下一条的话，会接着打开。\n\n", NamedTextColor.GRAY)
                .append(Component.text("【我已阅读】", NamedTextColor.DARK_GREEN)
                        .clickEvent(ClickEvent.callback(audience -> {
                            if (audience instanceof Player reader) {
                                Bukkit.getScheduler().runTask(this, () -> acknowledge(reader));
                            }
                        }, options -> options.uses(1).lifetime(Duration.ofMinutes(30))))));
        book.setItemMeta(meta);
        return book;
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
