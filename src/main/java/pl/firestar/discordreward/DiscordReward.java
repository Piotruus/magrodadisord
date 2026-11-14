package pl.firestar.discordreward;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class DiscordReward extends JavaPlugin implements Listener, CommandExecutor {

    private static final int BOOK_SLOT = 22;
    private static final Pattern NICK = Pattern.compile("^\\.?[A-Za-z0-9_]{3,16}$");

    private File dataFile;
    private YamlConfiguration data;
    private HttpServer http;
    private ExecutorService pool;

    /** Holder pozwala rozpoznac nasze GUI bez porownywania tytulow. */
    private static class RewardHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("nagroda").setExecutor(this);

        startHttp();
    }

    @Override
    public void onDisable() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ helpers

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, ""));
    }

    private boolean isClaimed(UUID id) {
        return data.getBoolean("claimed." + id);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie zapisac data.yml: " + e.getMessage());
        }
    }

    private String formatTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || h > 0) sb.append(m).append("min ");
        sb.append(s).append("s");
        return sb.toString();
    }

    private void showTitle(Player p, String text) {
        p.sendTitle(" ", text, 10, 70, 20);
    }

    // ------------------------------------------------------------------ GUI

    private void openGui(Player p) {
        Inventory inv = Bukkit.createInventory(new RewardHolder(), 54, color(getConfig().getString("gui.title")));

        if (getConfig().getBoolean("gui.filler", true)) {
            Material pane = Material.matchMaterial("BLACK_STAINED_GLASS_PANE");
            if (pane != null) {
                ItemStack filler = new ItemStack(pane);
                ItemMeta fm = filler.getItemMeta();
                fm.setDisplayName(" ");
                filler.setItemMeta(fm);
                for (int i = 0; i < 54; i++) inv.setItem(i, filler);
            }
        }

        boolean claimed = isClaimed(p.getUniqueId());
        String status = color(getConfig().getString(claimed ? "gui.status-yes" : "gui.status-no"));

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("gui.title")));
        List<String> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("gui.lore")) {
            lore.add(color(line).replace("{status}", status));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        book.setItemMeta(meta);
        inv.setItem(BOOK_SLOT, book);

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RewardHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getRawSlot() != BOOK_SLOT) return;

        Player p = (Player) e.getWhoClicked();
        p.closeInventory();

        if (isClaimed(p.getUniqueId())) {
            p.sendMessage(msg("already-claimed-chat"));
            return;
        }

        String link = getConfig().getString("link");
        String text = msg("link").replace("{link}", link);
        BaseComponent[] comps = TextComponent.fromLegacyText(text);
        for (BaseComponent c : comps) {
            c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
        }
        p.spigot().sendMessage(comps);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RewardHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ komenda

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("discordreward.admin")) {
                sender.sendMessage(msg("no-perm"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uzycie: /nagroda reset <nick>");
                return true;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
            data.set("claimed." + op.getUniqueId(), null);
            saveData();
            sender.sendMessage(msg("reset-ok").replace("{player}", args[1]));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Tylko dla graczy.");
            return true;
        }
        openGui((Player) sender);
        return true;
    }

    // ------------------------------------------------------------------ odbieranie nagrody

    /** Wywolywane NA GLOWNYM WATKU. Zwraca JSON dla bota. */
    private String claim(String nick) {
        Player p = Bukkit.getPlayerExact(nick);
        if (p == null) return "{\"status\":\"OFFLINE\"}";

        UUID id = p.getUniqueId();
        if (isClaimed(id)) return "{\"status\":\"ALREADY\"}";

        long playedTicks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long neededTicks = getConfig().getLong("required-minutes", 60) * 60L * 20L;

        if (playedTicks < neededTicks) {
            long remaining = (neededTicks - playedTicks + 19) / 20; // w gore do pelnych sekund
            showTitle(p, msg("need-time").replace("{time}", formatTime(remaining)));
            return "{\"status\":\"TIME\",\"remaining\":" + remaining + "}";
        }

        for (String c : getConfig().getStringList("reward-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        }
        data.set("claimed." + id, true);
        saveData();
        showTitle(p, msg("success"));
        return "{\"status\":\"OK\",\"nick\":\"" + p.getName() + "\"}";
    }

    // ------------------------------------------------------------------ mini-serwer HTTP

    private void startHttp() {
        int port = getConfig().getInt("api.port", 8765);
        String secret = getConfig().getString("api.secret", "");
        if (secret.isEmpty() || secret.startsWith("ZMIEN_MNIE")) {
            getLogger().warning("!!! Ustaw api.secret w config.yml (ten sam co PLUGIN_SECRET w bocie) !!!");
        }

        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            pool = Executors.newFixedThreadPool(2);
            http.setExecutor(pool);
            http.createContext("/claim", ex -> {
                try {
                    handleClaim(ex, secret);
                } catch (Exception e) {
                    getLogger().warning("Blad API: " + e);
                    send(ex, 500, "{\"status\":\"ERROR\"}");
                }
            });
            http.start();
            getLogger().info("API bota nasluchuje na porcie " + port);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie uruchomic API na porcie " + port + ": " + e.getMessage());
        }
    }

    private void handleClaim(HttpExchange ex, String secret) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"status\":\"ERROR\"}");
            return;
        }
        String given = ex.getRequestHeaders().getFirst("X-Secret");
        if (given == null || !MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            send(ex, 401, "{\"status\":\"UNAUTHORIZED\"}");
            return;
        }

        String nick = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!NICK.matcher(nick).matches()) {
            send(ex, 400, "{\"status\":\"BAD_NICK\"}");
            return;
        }

        String result = Bukkit.getScheduler().callSyncMethod(this, () -> claim(nick)).get(5, TimeUnit.SECONDS);
        send(ex, 200, result);
    }

    private void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
