package me.exi.healthbars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

/**
 * Barras vanilla mediante nombres de entidades, visibles en Java y normalmente por Geyser.
 * COMBAT muestra la barra temporalmente cuando un jugador golpea a un mob.
 */
public final class ExiledHealthBarsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, TrackedMob> tracked = new HashMap<>();
    private final Set<UUID> externallyControlled = new HashSet<>();
    private NamespacedKey managedKey;
    private NamespacedKey oldNameKey;
    private NamespacedKey oldVisibleKey;
    private NamespacedKey lastAppliedKey;
    private BukkitTask updateTask;

    private boolean nearbyMode;
    private int radius;
    private int scanInterval;
    private int updatePeriod;
    private int maximumTracked;
    private int combatDurationTicks;
    private int barLength;
    private boolean showNumbers;
    private boolean showMobName;
    private boolean includeNamed;
    private boolean includeBosses;
    private boolean includeNpcs;
    private long serverTicks;
    private long lastScan;

    @Override
    public void onEnable() {
        managedKey = new NamespacedKey(this, "managed");
        oldNameKey = new NamespacedKey(this, "old_name");
        oldVisibleKey = new NamespacedKey(this, "old_visible");
        lastAppliedKey = new NamespacedKey(this, "last_applied");
        saveDefaultConfig();
        loadOptions();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("exhealth"), "Falta exhealth en plugin.yml").setExecutor(this);
        startTask();
        getLogger().info("ExiledHealthBars 1.1.0 activo: " + (nearbyMode
                ? "todos los mobs cercanos, incluyendo animales pacificos" : "modo combate") + ".");
    }

    @Override
    public void onDisable() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        for (TrackedMob entry : new ArrayList<>(tracked.values())) {
            restoreIfOwned(entry);
        }
        tracked.clear();
        externallyControlled.clear();
    }

    private void loadOptions() {
        String mode = getConfig().getString("display.mode", "COMBAT");
        nearbyMode = "NEARBY".equalsIgnoreCase(mode);
        radius = clamp(getConfig().getInt("display.radius-blocks", 32), 8, 96);
        scanInterval = clamp(getConfig().getInt("display.scan-interval-ticks", 20), 10, 100);
        updatePeriod = clamp(getConfig().getInt("display.update-interval-ticks", 5), 2, 40);
        maximumTracked = clamp(getConfig().getInt("display.max-tracked-mobs", 500), 10, 2000);
        combatDurationTicks = clamp(getConfig().getInt("combat.duration-seconds", 10), 2, 60) * 20;
        barLength = clamp(getConfig().getInt("bar.length", 12), 5, 20);
        showNumbers = getConfig().getBoolean("bar.show-numbers", true);
        showMobName = getConfig().getBoolean("bar.show-mob-name", false);
        // Claves NUEVAS para que configuraciones de v1.0 no oculten animales sin querer.
        includeNamed = getConfig().getBoolean("entities.include-custom-named-mobs", true);
        includeBosses = getConfig().getBoolean("entities.include-bosses", true);
        includeNpcs = getConfig().getBoolean("entities.include-npcs", false);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void startTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        serverTicks = 0;
        lastScan = -scanInterval;
        updateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            serverTicks += updatePeriod;
            if (nearbyMode && serverTicks - lastScan >= scanInterval) {
                scanNearby();
                lastScan = serverTicks;
            }
            refreshTracked();
        }, 1L, updatePeriod);
    }

    private boolean eligible(LivingEntity mob) {
        if (mob instanceof Player || mob instanceof ArmorStand || !mob.isValid() || mob.isDead()
                || mob.getHealth() <= 0 || externallyControlled.contains(mob.getUniqueId())) {
            return false;
        }
        if (!includeBosses) {
            switch (mob.getType()) {
                case ENDER_DRAGON, WITHER -> { return false; }
                default -> { }
            }
        }
        if (!includeNpcs && mob.hasMetadata("NPC")) {
            return false;
        }
        return includeNamed || mob.getCustomName() == null || tracked.containsKey(mob.getUniqueId());
    }

    /** Escanea solo los mobs cargados alrededor de jugadores, NO todos los mobs de todos los mundos. */
    private void scanNearby() {
        final Set<UUID> seen = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isValid()) {
                continue;
            }
            final double radiusSq = (double) radius * radius;
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (!(entity instanceof LivingEntity mob)) {
                    continue;
                }
                if (mob.getLocation().distanceSquared(player.getLocation()) > radiusSq) {
                    continue;
                }
                UUID uuid = mob.getUniqueId();
                seen.add(uuid);
                if (!tracked.containsKey(uuid)) {
                    // Repara barras persistidas si el proceso se cerró abruptamente.
                    recoverIfMarked(mob);
                    if (tracked.size() < maximumTracked && eligible(mob)) {
                        acquire(mob);
                    }
                }
            }
        }
        // Permite que otro plugin gestione el nombre mientras el mob siga a la vista.
        externallyControlled.retainAll(seen);
        // Restaura el nombre cuando el último jugador se aleja del mob.
        for (TrackedMob entry : new ArrayList<>(tracked.values())) {
            if (!seen.contains(entry.mob.getUniqueId())) {
                tracked.remove(entry.mob.getUniqueId());
                restoreIfOwned(entry);
            }
        }
    }

    private void acquire(LivingEntity mob) {
        UUID id = mob.getUniqueId();
        if (tracked.containsKey(id) || !eligible(mob)) {
            return;
        }
        TrackedMob entry = new TrackedMob(mob, mob.getCustomName(), mob.isCustomNameVisible());
        tracked.put(id, entry);
        PersistentDataContainer data = mob.getPersistentDataContainer();
        data.set(managedKey, PersistentDataType.BYTE, (byte) 1);
        data.set(oldVisibleKey, PersistentDataType.BYTE, entry.originalVisible ? (byte) 1 : (byte) 0);
        if (entry.originalName != null) {
            data.set(oldNameKey, PersistentDataType.STRING, entry.originalName);
        } else {
            data.remove(oldNameKey);
        }
        apply(entry);
    }

    private void refreshTracked() {
        for (TrackedMob entry : new ArrayList<>(tracked.values())) {
            LivingEntity mob = entry.mob;
            UUID id = mob.getUniqueId();
            if (!mob.isValid() || mob.isDead() || mob.getHealth() <= 0) {
                tracked.remove(id);
                if (mob.isValid()) {
                    restoreIfOwned(entry);
                }
                continue;
            }
            if (!Objects.equals(mob.getCustomName(), entry.lastApplied)) {
                // Otro plugin cambió el nombre; no entrar en una guerra de nombres.
                tracked.remove(id);
                externallyControlled.add(id);
                clearBackup(mob);
                continue;
            }
            if (!nearbyMode && serverTicks >= entry.expireAtTick) {
                tracked.remove(id);
                restoreIfOwned(entry);
                continue;
            }
            apply(entry);
        }
    }

    private void apply(TrackedMob entry) {
        LivingEntity mob = entry.mob;
        AttributeInstance attribute = mob.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute != null ? attribute.getValue() : Math.max(20.0, mob.getHealth());
        // Respetar nombres originalmente ocultos; la barra sí será visible.
        String label = entry.originalVisible ? entry.originalName : null;
        if (showMobName && (label == null || label.isBlank())) {
            label = entry.originalName != null ? entry.originalName : mob.getType().name().replace('_', ' ');
        }
        String result = HealthBarFormatter.format(mob.getHealth(), max, barLength, showNumbers, label);
        if (!result.equals(entry.lastFormatted)) {
            // Paper puede normalizar los codigos RGB al serializar el nombre.
            // Comparar siempre con el valor real que devuelve getCustomName().
            mob.setCustomName(result);
            entry.lastApplied = mob.getCustomName();
            entry.lastFormatted = result;
            // Guardamos el nombre EXACTO aplicado para recuperarlo tras un cierre abrupto.
            if (entry.lastApplied != null) {
                mob.getPersistentDataContainer().set(lastAppliedKey,
                        PersistentDataType.STRING, entry.lastApplied);
            }
        }
        if (!mob.isCustomNameVisible()) {
            mob.setCustomNameVisible(true);
        }
    }

    private void restoreIfOwned(TrackedMob entry) {
        LivingEntity mob = entry.mob;
        if (!mob.isValid()) {
            return;
        }
        if (Objects.equals(mob.getCustomName(), entry.lastApplied)) {
            mob.setCustomName(entry.originalName);
            mob.setCustomNameVisible(entry.originalVisible);
        }
        clearBackup(mob);
    }

    private void recoverIfMarked(LivingEntity mob) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        if (!data.has(managedKey, PersistentDataType.BYTE)) {
            return;
        }
        String applied = data.get(lastAppliedKey, PersistentDataType.STRING);
        if (applied != null && Objects.equals(applied, mob.getCustomName())) {
            mob.setCustomName(data.get(oldNameKey, PersistentDataType.STRING));
            mob.setCustomNameVisible(Byte.valueOf((byte) 1).equals(data.get(oldVisibleKey, PersistentDataType.BYTE)));
        }
        clearBackup(mob);
    }

    private void clearBackup(LivingEntity mob) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        data.remove(managedKey);
        data.remove(oldNameKey);
        data.remove(oldVisibleKey);
        data.remove(lastAppliedKey);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity direct = event.getDamager();
        LivingEntity attacker = direct instanceof LivingEntity living ? living : null;
        if (direct instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof LivingEntity living) {
                attacker = living;
            }
        }

        // El mensaje vanilla de muerte usa el customName actual del atacante. Como la
        // barra vive temporalmente en ese campo, un mob rastreado aparecería en el chat
        // con la barra en lugar de su nombre. En un golpe letal exponemos su nombre real
        // hasta que Minecraft haya construido el mensaje y restauramos la barra al tick
        // siguiente.
        if (attacker != null && !(attacker instanceof Player)
                && event.getEntity() instanceof Player player
                && event.getFinalDamage() >= player.getHealth()) {
            TrackedMob entry = tracked.get(attacker.getUniqueId());
            if (entry != null) {
                exposeOriginalNameForDeathMessage(entry);
            }
        }

        if (nearbyMode) {
            return;
        }

        // La barra solo se activa cuando el daño parte de un jugador, directamente
        // o mediante un proyectil. Un mob que golpee al jugador no la activa.
        LivingEntity mob = null;
        if (attacker instanceof Player && event.getEntity() instanceof LivingEntity victim
                && !(victim instanceof Player)) {
            mob = victim;
        }
        if (mob != null) {
            LivingEntity target = mob;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!tracked.containsKey(target.getUniqueId())) {
                    recoverIfMarked(target);
                }
                if (!eligible(target)) {
                    return;
                }
                TrackedMob entry = tracked.get(target.getUniqueId());
                if (entry == null && tracked.size() < maximumTracked) {
                    acquire(target);
                    entry = tracked.get(target.getUniqueId());
                }
                if (entry != null) {
                    entry.expireAtTick = serverTicks + combatDurationTicks;
                    apply(entry);
                }
            });
        }
    }

    private void exposeOriginalNameForDeathMessage(TrackedMob entry) {
        LivingEntity mob = entry.mob;
        if (!Objects.equals(mob.getCustomName(), entry.lastApplied)) {
            return;
        }

        mob.setCustomName(entry.originalName);
        mob.setCustomNameVisible(entry.originalVisible);
        entry.lastApplied = mob.getCustomName();
        // Obliga a apply() a escribir nuevamente la barra aunque la vida no haya cambiado.
        entry.lastFormatted = null;

        Bukkit.getScheduler().runTask(this, () -> {
            UUID id = mob.getUniqueId();
            if (tracked.get(id) != entry || !mob.isValid() || mob.isDead()) {
                return;
            }
            if (!Objects.equals(mob.getCustomName(), entry.lastApplied)) {
                // Otro plugin aprovechó el intervalo para controlar el nombre.
                tracked.remove(id);
                externallyControlled.add(id);
                clearBackup(mob);
                return;
            }
            if (!nearbyMode && serverTicks >= entry.expireAtTick) {
                tracked.remove(id);
                restoreIfOwned(entry);
                return;
            }
            apply(entry);
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        externallyControlled.remove(id);
        TrackedMob entry = tracked.remove(id);
        if (entry != null) {
            restoreIfOwned(entry);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (Entity entity : chunk.getEntities()) {
            UUID id = entity.getUniqueId();
            externallyControlled.remove(id);
            TrackedMob entry = tracked.remove(id);
            if (entry != null) {
                restoreIfOwned(entry);
            } else if (entity instanceof LivingEntity mob) {
                recoverIfMarked(mob);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            for (TrackedMob entry : new ArrayList<>(tracked.values())) {
                restoreIfOwned(entry);
            }
            tracked.clear();
            externallyControlled.clear();
            reloadConfig();
            loadOptions();
            startTask();
            sender.sendMessage(ChatColor.GREEN + "[ExiledHealthBars] Configuración recargada.");
            return true;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("status"))) {
            sender.sendMessage(ChatColor.GOLD + "[ExiledHealthBars] v1.1.0 | modo: "
                    + (nearbyMode ? "TODOS LOS MOBS CERCANOS" : "SOLO COMBATE")
                    + " | barras: " + tracked.size() + "/" + maximumTracked
                    + (nearbyMode ? " | radio: " + radius + " bloques" : " | duracion: " + combatDurationTicks / 20 + " s"));
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Uso: /exhealth <status|reload>");
        return true;
    }

    private static final class TrackedMob {
        final LivingEntity mob;
        final String originalName;
        final boolean originalVisible;
        String lastApplied;
        String lastFormatted;
        long expireAtTick;

        TrackedMob(LivingEntity mob, String originalName, boolean originalVisible) {
            this.mob = mob;
            this.originalName = originalName;
            this.originalVisible = originalVisible;
        }
    }
}
