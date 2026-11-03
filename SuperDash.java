package com.example.superdash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class SuperDash extends JavaPlugin implements Listener, TabExecutor {

    // Cooldown default kalau config-nya kosong / belum ada
    private static final String FALLBACK_COOLDOWN_MSG = "&7Sabar dulu, &e{time}s &7lagi &8[{bar}&8]";

    private NamespacedKey enabledKey;
    // key lama dari versi DoubleJump, dibaca sebagai cadangan biar setelan on/off pemain nggak hilang
    private NamespacedKey legacyEnabledKey;

    // pemain yang lagi "siap" dash (allowFlight sudah kita nyalakan)
    private final Set<UUID> armed = new HashSet<>();
    // waktu dash terakhir, dipakai buat kebal fall damage sebentar setelah mendarat
    private final Map<UUID, Long> jumped = new HashMap<>();
    // waktu pemain terakhir MENDARAT setelah dash, dipakai buat hitung cooldown
    private final Map<UUID, Long> lastJump = new HashMap<>();
    // pemain yang sudah dash dan masih di udara (cooldown baru mulai pas mendarat)
    private final Map<UUID, Long> pendingLanding = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        enabledKey = new NamespacedKey(this, "enabled");
        legacyEnabledKey = NamespacedKey.fromString("doublejump:enabled");
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("superdash");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        // balikin allowFlight pemain yang masih "armed" supaya nggak nyangkut bisa terbang
        for (UUID id : armed) {
            Player p = getServer().getPlayer(id);
            if (p != null && isSurvivalLike(p)) {
                p.setAllowFlight(false);
            }
        }
        armed.clear();
        pendingLanding.clear();
    }

    private boolean isSurvivalLike(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private boolean isEnabled(Player p) {
        Byte stored = p.getPersistentDataContainer().get(enabledKey, PersistentDataType.BYTE);
        if (stored == null && legacyEnabledKey != null) {
            stored = p.getPersistentDataContainer().get(legacyEnabledKey, PersistentDataType.BYTE);
        }
        if (stored == null) {
            return getConfig().getBoolean("enable-by-default", true);
        }
        return stored == 1;
    }

    private void setEnabled(Player p, boolean on) {
        p.getPersistentDataContainer().set(enabledKey, PersistentDataType.BYTE, (byte) (on ? 1 : 0));
        if (!on) {
            pendingLanding.remove(p.getUniqueId());
        }
        if (!on && armed.remove(p.getUniqueId()) && isSurvivalLike(p)) {
            p.setAllowFlight(false);
        }
    }

    private boolean worldAllowed(Player p) {
        List<String> worlds = getConfig().getStringList("jump.worlds");
        return worlds.isEmpty() || worlds.contains(p.getWorld().getName());
    }

    // ---------------------------------------------------------------
    //  Pesan
    // ---------------------------------------------------------------

    private static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    /**
     * Ambil teks pesan dari config. Kalau isinya list, dipilih satu secara acak
     * biar nggak monoton. Kalau cuma satu string ya dipakai apa adanya.
     */
    private String pick(String key, String fallback) {
        FileConfiguration cfg = getConfig();
        List<String> options = cfg.getStringList("messages." + key);
        if (!options.isEmpty()) {
            return options.get(random.nextInt(options.size()));
        }
        return cfg.getString("messages." + key, fallback);
    }

    private void msg(CommandSender to, String key, String... replacements) {
        String text = getConfig().getString("messages." + key, "");
        if (text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        to.sendMessage(color(text));
    }

    // ---------------------------------------------------------------
    //  Cooldown
    // ---------------------------------------------------------------

    private static String formatSeconds(long millis) {
        return String.format(Locale.US, "%.1f", millis / 1000.0);
    }

    /** Bar kecil yang makin penuh dan berubah warna (merah, kuning, hijau) seiring cooldown habis. */
    private String cooldownBar(long remaining, long total) {
        FileConfiguration cfg = getConfig();
        int length = Math.max(3, cfg.getInt("cooldown-feedback.bar-length", 10));
        double done = total <= 0 ? 1.0 : 1.0 - ((double) remaining / total);
        done = Math.max(0.0, Math.min(1.0, done));
        int filled = (int) Math.round(done * length);

        String tint = done < 0.34 ? "&c" : (done < 0.67 ? "&e" : "&a");
        String full = cfg.getString("cooldown-feedback.bar-filled", "■");
        String empty = cfg.getString("cooldown-feedback.bar-empty", "■");
        return tint + full.repeat(filled) + "&8" + empty.repeat(length - filled);
    }

    private void showCooldown(Player p, long remaining, long total) {
        FileConfiguration cfg = getConfig();
        String text = pick("cooldown", FALLBACK_COOLDOWN_MSG)
                .replace("{time}", formatSeconds(remaining))
                .replace("{bar}", cooldownBar(remaining, total));
        if (text.isEmpty()) {
            return;
        }
        Component line = color(text);

        if (cfg.getBoolean("cooldown-feedback.actionbar", true)) {
            p.sendActionBar(line);
        }
        if (cfg.getBoolean("cooldown-feedback.chat", false)) {
            p.sendMessage(line);
        }
        if (cfg.getBoolean("cooldown-feedback.sound.enabled", true)) {
            playTo(p, "cooldown-feedback.sound", "block.note_block.bass", 0.6, 0.7);
        }
    }

    /** Sisa cooldown (ms) untuk pemain ini; <= 0 berarti sudah siap. */
    private long remainingCooldown(UUID id, long now) {
        long cooldown = Math.max(0L, getConfig().getLong("jump.cooldown", 1000L));
        return lastJump.getOrDefault(id, 0L) + cooldown - now;
    }

    /** Kabari pemain kalau dash-nya sudah siap lagi (opsional, default mati). */
    private void scheduleReadyPing(Player p, long delayMs) {
        FileConfiguration cfg = getConfig();
        if (cfg.getLong("jump.cooldown", 1000L) <= 0
                || !cfg.getBoolean("cooldown-feedback.ready-ping.enabled", false)) {
            return;
        }
        UUID id = p.getUniqueId();
        long ticks = Math.max(1L, (Math.max(0L, delayMs) + 49L) / 50L);
        getServer().getScheduler().runTaskLater(this, () -> {
            Player online = getServer().getPlayer(id);
            if (online == null || !online.isOnline() || !isEnabled(online)) {
                return;
            }
            // kalau dia sudah dash lagi di sela-sela itu, jangan ganggu
            if (pendingLanding.containsKey(id) || remainingCooldown(id, System.currentTimeMillis()) > 0) {
                return;
            }
            String text = pick("ready", "&a✔ Udah siap, dash lagi!");
            if (!text.isEmpty()) {
                online.sendActionBar(color(text));
            }
            if (getConfig().getBoolean("cooldown-feedback.ready-ping.sound.enabled", true)) {
                playTo(online, "cooldown-feedback.ready-ping.sound", "entity.experience_orb.pickup", 0.5, 1.4);
            }
        }, ticks);
    }

    // ---------------------------------------------------------------
    //  Event
    // ---------------------------------------------------------------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isSurvivalLike(p) || !p.isOnGround()) {
            return;
        }

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        Long when = jumped.get(id);
        if (when != null && now - when > 300L) {
            jumped.remove(id);
        }

        // Pemain sudah mendarat setelah dash -> dash berikutnya boleh (kalau cooldown sudah habis).
        // Jeda 300ms dipakai supaya momen lepas landas nggak dikira mendarat.
        Long dashedAt = pendingLanding.get(id);
        if (dashedAt != null && now - dashedAt > 300L) {
            pendingLanding.remove(id);
            if (getConfig().getBoolean("jump.cooldown-from-landing", false)) {
                // mode lama: cooldown baru mulai hitung dari saat mendarat
                lastJump.put(id, now);
            }
            scheduleReadyPing(p, remainingCooldown(id, now));
        }

        if (!p.getAllowFlight() && isEnabled(p) && p.hasPermission("superdash.use") && worldAllowed(p)) {
            p.setAllowFlight(true);
            armed.add(id);
        }
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!e.isFlying() || !armed.contains(id) || !isSurvivalLike(p)) {
            return;
        }

        // ini dash kita, jangan sampai beneran terbang
        e.setCancelled(true);
        p.setFlying(false);

        if (!isEnabled(p) || !p.hasPermission("superdash.use") || !worldAllowed(p)) {
            p.setAllowFlight(false);
            armed.remove(id);
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, getConfig().getLong("jump.cooldown", 1000L));

        // Sudah dash dan belum mendarat: dash kedua di udara tetap ditolak.
        if (pendingLanding.containsKey(id)) {
            if (cooldown > 0) {
                if (getConfig().getBoolean("jump.cooldown-from-landing", false)) {
                    // mode lama: cooldown belum mulai, tampilkan penuh
                    showCooldown(p, cooldown, cooldown);
                } else {
                    long left = remainingCooldown(id, now);
                    if (left > 0) {
                        showCooldown(p, left, cooldown);
                    }
                }
            }
            return;
        }

        long remaining = remainingCooldown(id, now);
        if (remaining > 0) {
            showCooldown(p, remaining, cooldown);
            return;
        }

        // Dash! Pemain tetap "armed" supaya tekan spasi di udara memunculkan info cooldown,
        // tapi pendingLanding mencegah dash kedua sebelum mendarat + cooldown habis.
        pendingLanding.put(id, now);
        lastJump.put(id, now); // cooldown mulai dari saat dash (kecuali cooldown-from-landing: true)
        jumped.put(id, now);

        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() > 0) {
            dir.normalize().multiply(getConfig().getDouble("jump.forward", 0.6));
        }
        dir.setY(getConfig().getDouble("jump.height", 0.9));
        p.setVelocity(dir);

        playEffects(p);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (!getConfig().getBoolean("jump.prevent-fall-damage", true)) {
            return;
        }
        if (jumped.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            jumped.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        armed.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        armed.remove(id);
        jumped.remove(id);
        lastJump.remove(id);
        pendingLanding.remove(id);
    }

    // ---------------------------------------------------------------
    //  Efek
    // ---------------------------------------------------------------

    private Sound findSound(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase());
        Sound sound = key == null ? null : Registry.SOUNDS.get(key);
        if (sound == null) {
            getLogger().warning("Sound tidak valid di config: " + name);
        }
        return sound;
    }

    /** Putar sound khusus untuk satu pemain (dipakai buat feedback cooldown). */
    private void playTo(Player p, String path, String defType, double defVolume, double defPitch) {
        FileConfiguration cfg = getConfig();
        Sound sound = findSound(cfg.getString(path + ".type", defType));
        if (sound != null) {
            p.playSound(p.getLocation(), sound,
                    (float) cfg.getDouble(path + ".volume", defVolume),
                    (float) cfg.getDouble(path + ".pitch", defPitch));
        }
    }

    private void playEffects(Player p) {
        Location loc = p.getLocation();
        FileConfiguration cfg = getConfig();

        if (cfg.getBoolean("effects.particle.enabled", true)) {
            String name = cfg.getString("effects.particle.type", "CLOUD");
            try {
                Particle particle = Particle.valueOf(name.toUpperCase());
                double offset = cfg.getDouble("effects.particle.offset", 0.3);
                p.getWorld().spawnParticle(particle, loc,
                        cfg.getInt("effects.particle.count", 20),
                        offset, 0.1, offset,
                        cfg.getDouble("effects.particle.speed", 0.05));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Particle tidak valid di config: " + name);
            }
        }

        if (cfg.getBoolean("effects.sound.enabled", true)) {
            Sound sound = findSound(cfg.getString("effects.sound.type", "entity.bat.takeoff"));
            if (sound != null) {
                p.getWorld().playSound(loc, sound,
                        (float) cfg.getDouble("effects.sound.volume", 1.0),
                        (float) cfg.getDouble("effects.sound.pitch", 1.2));
            }
        }
    }

    // ---------------------------------------------------------------
    //  Command
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("superdash.admin")) {
                msg(sender, "no-permission");
                return true;
            }
            reloadConfig();
            msg(sender, "reloaded");
            return true;
        }

        if (!(sender instanceof Player p)) {
            msg(sender, "players-only");
            return true;
        }
        if (!p.hasPermission("superdash.use")) {
            msg(p, "no-permission");
            return true;
        }

        boolean on;
        if (args.length == 0) {
            on = !isEnabled(p);
        } else if (args[0].equalsIgnoreCase("on")) {
            on = true;
        } else if (args[0].equalsIgnoreCase("off")) {
            on = false;
        } else {
            msg(p, "usage");
            return true;
        }

        setEnabled(p, on);
        msg(p, on ? "enabled" : "disabled");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase();
        return List.of("on", "off", "reload").stream()
                .filter(s -> s.startsWith(typed))
                .filter(s -> !s.equals("reload") || sender.hasPermission("superdash.admin"))
                .toList();
    }
}
