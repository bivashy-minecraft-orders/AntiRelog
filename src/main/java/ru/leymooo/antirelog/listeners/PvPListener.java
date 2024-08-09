package ru.leymooo.antirelog.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.leymooo.antirelog.config.Messages;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.manager.PvPManager;
import ru.leymooo.antirelog.util.Utils;
import ru.leymooo.antirelog.util.VersionUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class PvPListener implements Listener {

    private final static String META_KEY = "ar-f-shooter";
    private final Plugin plugin;
    private final PvPManager pvpManager;
    private final Messages messages;
    private final Settings settings;
    private final Map<Player, AtomicInteger> allowedTeleports = new HashMap<>();

    public PvPListener(Plugin plugin, PvPManager pvpManager, Settings settings) {
        this.plugin = plugin;
        this.pvpManager = pvpManager;
        this.settings = settings;
        this.messages = settings.getMessages();
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            allowedTeleports.values().forEach(ai -> ai.set(ai.get() + 1));
            allowedTeleports.values().removeIf(ai -> ai.get() >= 5);
        }, 1, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity().getType() != EntityType.PLAYER) {
            return;
        }
        Player target = (Player) event.getEntity();
        Player damager = getDamager(event.getDamager());
        pvpManager.playerDamagedByPlayer(damager, target);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractWithEntity(PlayerInteractEntityEvent event) {
        if (settings.isCancelInteractWithEntities() && pvpManager.isPvPModeEnabled() && pvpManager.isInPvP(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        Player target = (Player) event.getEntity();
        Player damager = getDamager(event.getCombuster());
        pvpManager.playerDamagedByPlayer(damager, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (VersionUtils.isVersion(14) && event.getProjectile() instanceof Firework && event.getEntity().getType() == EntityType.PLAYER) {
            event.getProjectile().setMetadata(META_KEY, new FixedMetadataValue(plugin, event.getEntity().getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent e) {
        if (e.getPotion().getShooter() instanceof Player) {
            Player shooter = (Player) e.getPotion().getShooter();
            for (LivingEntity en : e.getAffectedEntities()) {
                if (en.getType() == EntityType.PLAYER && en != shooter) {
                    for (PotionEffect ef : e.getPotion().getEffects()) {
                        if (ef.getType().equals(PotionEffectType.POISON)) {
                            pvpManager.playerDamagedByPlayer(shooter, (Player) en);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {

        if (settings.isDisableTeleportsInPvp() && pvpManager.isInPvP(event.getPlayer())) {
            if (allowedTeleports.containsKey(event.getPlayer())) { //allow all teleport in 4-5 ticks after chorus or ender pearl
                return;
            }

            if ((VersionUtils.isVersion(9) && event.getCause() == TeleportCause.CHORUS_FRUIT) || event.getCause() == TeleportCause.ENDER_PEARL) {
                allowedTeleports.put(event.getPlayer(), new AtomicInteger(0));
                return;
            }
            if (event.getTo() == null)
                return;
            if (event.getFrom().getWorld() != event.getTo().getWorld()) {
                event.setCancelled(true);
                return;
            }
            if (event.getFrom().distanceSquared(event.getTo()) > 100) { //10 = 10 blocks
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (settings.isDisableCommandsInPvp() && pvpManager.isInPvP(e.getPlayer())) {
            String command = e.getMessage().split(" ")[0].replaceFirst("/", "");
            if (pvpManager.isCommandWhiteListed(command)) {
                return;
            }
            e.setCancelled(true);
            String message = messages.getCommandsDisabled();
            if (!message.isEmpty()) {
                message = Utils.replaceTime(message, pvpManager.getTimeRemainingInPvP(e.getPlayer()));
                Utils.sendMessage(e.getPlayer(), message);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        Player player = e.getPlayer();

        if (pvpManager.isInSilentPvP(player)) {
            pvpManager.stopPvPSilent(player);
            return;
        }

        if (!pvpManager.isInPvP(player)) {
            return;
        }

        pvpManager.stopPvPSilent(player);

        if (settings.getKickMessages().isEmpty()) {
            kickedInPvp(player);
            return;
        }
        String reason = ChatColor.stripColor(e.getReason().toLowerCase());
        for (String killReason : settings.getKickMessages()) {
            if (reason.contains(killReason.toLowerCase())) {
                kickedInPvp(player);
                return;
            }
        }
    }

    private void kickedInPvp(Player player) {
        if (settings.isKillOnKick()) {
            player.setHealth(0);
            sendLeavedInPvpMessage(player);
        }
        if (settings.isRunCommandsOnKick()) {
            runCommands(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        allowedTeleports.remove(e.getPlayer());
        if (settings.isHideLeaveMessage()) {
            e.setQuitMessage(null);
        }
        if (pvpManager.isInPvP(e.getPlayer())) {
            pvpManager.stopPvPSilent(e.getPlayer());
            if (settings.isKillOnLeave()) {
                sendLeavedInPvpMessage(e.getPlayer());
                e.getPlayer().setHealth(0);
            } else {
                pvpManager.stopPvPSilent(e.getPlayer());
            }
            runCommands(e.getPlayer());
        }
        if (pvpManager.isInSilentPvP(e.getPlayer())) {
            pvpManager.stopPvPSilent(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        if (settings.isHideDeathMessage()) {
            e.setDeathMessage(null);
        }

        if (pvpManager.isInSilentPvP(e.getEntity()) || pvpManager.isInPvP(e.getEntity())) {
            pvpManager.stopPvPSilent(e.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (settings.isHideJoinMessage()) {
            e.setJoinMessage(null);
        }
    }

    private void sendLeavedInPvpMessage(Player p) {
        String message = messages.getPvpLeaved().replace("%player%", p.getName());
        if (!message.isEmpty()) {
            Utils.broadcastMessage(message);
        }
    }

    private void runCommands(Player leaved) {
        if (!settings.getCommandsOnLeave().isEmpty()) {
            settings.getCommandsOnLeave().forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", leaved.getName())));
        }
    }

    private Player getDamager(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        } else if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            if (proj.getShooter() instanceof Player) {
                return (Player) proj.getShooter();
            }
        } else if (damager instanceof TNTPrimed) {
            TNTPrimed tntPrimed = (TNTPrimed) damager;
            return getDamager(tntPrimed.getSource());
        } else if (VersionUtils.isVersion(9) && damager instanceof AreaEffectCloud) {
            AreaEffectCloud aec = (AreaEffectCloud) damager;
            if (aec.getSource() instanceof Player) {
                return (Player) aec.getSource();
            }
        }
        return null;
    }

}
