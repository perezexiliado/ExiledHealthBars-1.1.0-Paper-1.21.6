package me.exi.healthbars;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
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
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('\u00a7').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private final Map<UUID, TrackedMob> tracked = new HashMap<>();
    private final Set<UUID> externallyControlled = new HashSet<>();
    private NamespacedKey managedKey;
    private NamespacedKey oldNameKey;
    private NamespacedKey oldVisibleKey;
    private NamespacedKey lastAppliedKey;
    private NamespacedKey oldNameJsonKey;
    private NamespacedKey lastAppliedJsonKey;
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
    private long lastRefresh;
    private int lastServerTick;
    private long taskGeneration;

    @Override
    public void onEnable() {
        managedKey = new NamespacedKey(this, "managed");
        oldNameKey = new NamespacedKey(this, "old_name");
        oldVisibleKey = new NamespacedKey(this, "old_visible");
        lastAppliedKey = new NamespacedKey(this, "last_applied");
        oldNameJsonKey = new NamespacedKey(this, "old_name_json");
        lastAppliedJsonKey = new NamespacedKey(this, "last_applied_json");
        saveDefaultConfig();
        loadOptions();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("exhealth"), "Falta exhealth en plugin.yml").setExecutor(this);
        // También en COMBAT: no dejar barras de un cierre abrupto hasta el próximo ataque.
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity mob : world.getLivingEntities()) {
                recoverIfMarked(mob);
            }
        }
        startTask();
        getLogger().info("ExiledHealthBars 1.1.0 activo: " + (nearbyMode
                ? "todos los mobs cercanos, incluyendo animales pacificos" : "modo combate") + ".");
    }

    @Override
    public void onDisable() {
        taskGeneration++;
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
        taskGeneration++;
        if (updateTask != null) {
            updateTask.cancel();
        }
        serverTicks = 0;
        lastServerTick = Bukkit.getCurrentTick();
        lastScan = -scanInterval;
        lastRefresh = -updatePeriod;
        updateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            updateClock();
            if (serverTicks - lastScan >= scanInterval) {
                if (nearbyMode) {
                    scanNearby();
                } else {
                    pruneExternallyControlled();
                }
                lastScan = serverTicks;
            }
            if (serverTicks - lastRefresh >= updatePeriod) {
                refreshTracked();
                lastRefresh = serverTicks;
            }
        }, 1L, greatestCommonDivisor(scanInterval, updatePeriod));
    }

    private static int greatestCommonDivisor(int first, int second) {
        while (second != 0) {
            int remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }

    private void updateClock() {
        int currentTick = Bukkit.getCurrentTick();
        // La resta int conserva el tiempo transcurrido incluso al desbordarse el contador.
        serverTicks += Integer.toUnsignedLong(currentTick - lastServerTick);
        lastServerTick = currentTick;
    }

    private void pruneExternallyControlled() {
        externallyControlled.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                return true;
            }
            Location location = entity.getLocation();
            double radiusSq = (double) radius * radius;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isValid() && player.getWorld().equals(entity.getWorld())
                        && player.getLocation().distanceSquared(location) <= radiusSq) {
                    return false;
                }
            }
            return true;
        });
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
        return includeNamed || mob.customName() == null || tracked.containsKey(mob.getUniqueId());
    }

    /** Escanea solo los mobs cargados alrededor de jugadores, NO todos los mobs de todos los mundos. */
    private void scanNearby() {
        final Map<UUID, LivingEntity> seen = new LinkedHashMap<>();
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
                seen.putIfAbsent(uuid, mob);
            }
        }
        // Permite que otro plugin gestione el nombre mientras el mob siga a la vista.
        externallyControlled.retainAll(seen.keySet());
        // Restaura el nombre cuando el último jugador se aleja del mob.
        for (TrackedMob entry : new ArrayList<>(tracked.values())) {
            if (!seen.containsKey(entry.mob.getUniqueId()) || !entry.mob.isValid()
                    || entry.mob.isDead() || entry.mob.getHealth() <= 0) {
                tracked.remove(entry.mob.getUniqueId());
                restoreIfOwned(entry);
            }
        }
        // Libera primero el cupo de los mobs que salieron del radio, para que
        // los nuevos tengan barra en este escaneo aun con el límite lleno.
        for (LivingEntity mob : seen.values()) {
            if (!tracked.containsKey(mob.getUniqueId())) {
                recoverIfMarked(mob);
                if (tracked.size() < maximumTracked && eligible(mob)) {
                    acquire(mob);
                }
            }
        }
    }

    private void acquire(LivingEntity mob) {
        UUID id = mob.getUniqueId();
        if (tracked.containsKey(id) || !eligible(mob)) {
            return;
        }
        TrackedMob entry = new TrackedMob(mob, mob.customName(), mob.isCustomNameVisible());
        tracked.put(id, entry);
        PersistentDataContainer data = mob.getPersistentDataContainer();
        data.set(managedKey, PersistentDataType.BYTE, (byte) 1);
        data.set(oldVisibleKey, PersistentDataType.BYTE, entry.originalVisible ? (byte) 1 : (byte) 0);
        if (entry.originalName != null) {
            data.set(oldNameKey, PersistentDataType.STRING, entry.originalName);
            data.set(oldNameJsonKey, PersistentDataType.STRING,
                    GsonComponentSerializer.gson().serialize(entry.originalNameComponent));
        } else {
            data.remove(oldNameKey);
            data.remove(oldNameJsonKey);
        }
        apply(entry);
    }

    private void refreshTracked() {
        for (TrackedMob entry : new ArrayList<>(tracked.values())) {
            LivingEntity mob = entry.mob;
            UUID id = mob.getUniqueId();
            if (!mob.isValid() || mob.isDead() || mob.getHealth() <= 0) {
                tracked.remove(id);
                restoreIfOwned(entry);
                continue;
            }
            if (!ownsName(entry)) {
                relinquish(entry);
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
        Component label = entry.originalVisible ? entry.originalNameComponent : null;
        if (showMobName && (label == null || entry.originalName == null || entry.originalName.isBlank())) {
            label = entry.originalNameComponent != null ? entry.originalNameComponent
                    : Component.text(mob.getType().name().replace('_', ' '));
        }
        String result = HealthBarFormatter.format(mob.getHealth(), max, barLength, showNumbers, null);
        if (!result.equals(entry.lastFormatted)) {
            // Comparar el componente real de Paper para detectar también cambios
            // externos de fuente, traducción o eventos que el texto legacy pierde.
            Component formatted = LEGACY.deserialize(result);
            if (label != null) {
                // No aplanar la fuente, traducción o eventos del nombre original.
                formatted = Component.empty().append(label).append(Component.text(" ")).append(formatted);
            }
            mob.customName(formatted);
            entry.lastAppliedComponent = mob.customName();
            entry.lastApplied = entry.lastAppliedComponent != null
                    ? LEGACY.serialize(entry.lastAppliedComponent) : null;
            entry.lastFormatted = result;
            // Guardamos el nombre EXACTO aplicado para recuperarlo tras un cierre abrupto.
            if (entry.lastApplied != null) {
                mob.getPersistentDataContainer().set(lastAppliedKey,
                        PersistentDataType.STRING, entry.lastApplied);
                mob.getPersistentDataContainer().set(lastAppliedJsonKey,
                        PersistentDataType.STRING,
                        GsonComponentSerializer.gson().serialize(entry.lastAppliedComponent));
            }
        }
        if (!mob.isCustomNameVisible()) {
            mob.setCustomNameVisible(true);
        }
    }

    private void restoreIfOwned(TrackedMob entry) {
        LivingEntity mob = entry.mob;
        if (ownsName(entry)) {
            mob.customName(entry.originalNameComponent);
            mob.setCustomNameVisible(entry.originalVisible);
        }
        clearBackup(mob);
    }

    private boolean ownsName(TrackedMob entry) {
        return Objects.equals(entry.mob.customName(), entry.lastAppliedComponent);
    }

    private void relinquish(TrackedMob entry) {
        UUID id = entry.mob.getUniqueId();
        tracked.remove(id);
        externallyControlled.add(id);
        clearBackup(entry.mob);
    }

    private Component savedName(PersistentDataContainer data, NamespacedKey jsonKey, NamespacedKey legacyKey) {
        String json = data.get(jsonKey, PersistentDataType.STRING);
        if (json != null) {
            try {
                return GsonComponentSerializer.gson().deserialize(json);
            } catch (JsonParseException | IllegalArgumentException exception) {
                getLogger().warning("Copia de nombre inválida en " + jsonKey
                        + "; se usará la copia legacy.");
            }
        }
        String legacy = data.get(legacyKey, PersistentDataType.STRING);
        return legacy != null ? LEGACY.deserialize(legacy) : null;
    }

    private boolean ownsPersistedName(LivingEntity mob) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        if (!data.has(managedKey, PersistentDataType.BYTE)) {
            return false;
        }
        if (data.has(lastAppliedJsonKey, PersistentDataType.STRING)) {
            Component applied = savedName(data, lastAppliedJsonKey, lastAppliedKey);
            return applied != null && Objects.equals(applied, mob.customName());
        }
        // Las versiones anteriores guardaban getCustomName(): conservar esa
        // comparación exacta para recuperar también sus barras normalizadas.
        String applied = data.get(lastAppliedKey, PersistentDataType.STRING);
        return applied != null && Objects.equals(applied, mob.getCustomName());
    }

    private void recoverIfMarked(LivingEntity mob) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        if (!data.has(managedKey, PersistentDataType.BYTE)) {
            return;
        }
        if (ownsPersistedName(mob)) {
            mob.customName(savedName(data, oldNameJsonKey, oldNameKey));
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
        data.remove(oldNameJsonKey);
        data.remove(lastAppliedJsonKey);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || event.getFinalDamage() <= 0.0 || nearbyMode) {
            return;
        }
        Entity direct = event.getDamager();
        LivingEntity attacker = direct instanceof LivingEntity living ? living : null;
        if (direct instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof LivingEntity living) {
                attacker = living;
            }
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
            long generation = taskGeneration;
            Bukkit.getScheduler().runTask(this, () -> {
                if (generation != taskGeneration || nearbyMode || event.isCancelled()) {
                    return;
                }
                updateClock();
                TrackedMob entry = tracked.get(target.getUniqueId());
                if (entry != null && !ownsName(entry)) {
                    relinquish(entry);
                    return;
                }
                if (!tracked.containsKey(target.getUniqueId())) {
                    recoverIfMarked(target);
                }
                if (!eligible(target)) {
                    return;
                }
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // El mensaje ya está construido: también cubre proyectiles y caídas/fuego
        // atribuidos a un mob, sin predecir golpes letales ni tocar su barra.
        event.deathMessage(DeathMessageSanitizer.restoreEntityNames(
                event.deathMessage(), this::deathMessageNames));
        Component screenMessage = event.deathScreenMessageOverride();
        if (screenMessage != null) {
            event.deathScreenMessageOverride(DeathMessageSanitizer.restoreEntityNames(
                    screenMessage, this::deathMessageNames));
        }
    }

    private DeathMessageSanitizer.EntityNames deathMessageNames(UUID id) {
        TrackedMob entry = tracked.get(id);
        if (entry != null && ownsName(entry)) {
            return new DeathMessageSanitizer.EntityNames(entry.originalDisplayName, entry.lastAppliedComponent);
        }

        // Puede haber una barra persistida tras un cierre abrupto, antes del
        // primer escaneo o ataque que vuelva a registrar al mob.
        if (Bukkit.getEntity(id) instanceof LivingEntity mob && !(mob instanceof Player)) {
            PersistentDataContainer data = mob.getPersistentDataContainer();
            if (ownsPersistedName(mob)) {
                Component original = savedName(data, oldNameJsonKey, oldNameKey);
                return new DeathMessageSanitizer.EntityNames(original != null ? original
                        : Component.translatable(mob.getType().translationKey()), mob.customName());
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.isCancelled()) {
            return;
        }
        UUID id = event.getEntity().getUniqueId();
        externallyControlled.remove(id);
        TrackedMob entry = tracked.remove(id);
        if (entry != null) {
            restoreIfOwned(entry);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
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

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity mob && !tracked.containsKey(entity.getUniqueId())) {
                recoverIfMarked(mob);
            }
        }
    }

    @EventHandler
    public void onEntityRemoved(EntityRemoveFromWorldEvent event) {
        UUID id = event.getEntity().getUniqueId();
        externallyControlled.remove(id);
        TrackedMob entry = tracked.remove(id);
        if (entry != null) {
            restoreIfOwned(entry);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity source)) {
            return;
        }
        TrackedMob entry = tracked.get(source.getUniqueId());
        Component original;
        Component applied;
        boolean visible;
        if (entry != null) {
            original = entry.originalNameComponent;
            applied = entry.lastAppliedComponent;
            visible = entry.originalVisible;
        } else {
            PersistentDataContainer data = source.getPersistentDataContainer();
            if (!data.has(managedKey, PersistentDataType.BYTE)) {
                return;
            }
            original = savedName(data, oldNameJsonKey, oldNameKey);
            applied = savedName(data, lastAppliedJsonKey, lastAppliedKey);
            visible = Byte.valueOf((byte) 1).equals(data.get(oldVisibleKey, PersistentDataType.BYTE));
        }
        if (applied == null) {
            return;
        }
        // Vanilla copia el nametag a un UUID nuevo, pero no la copia PDC. La
        // entidad transformada aún puede no estar añadida al mundo en este evento.
        // Comparar con la barra registrada: otro listener pudo renombrar solo
        // el origen después de que vanilla copiara su nombre al destino.
        for (Entity entity : event.getTransformedEntities()) {
            if (entity instanceof LivingEntity mob && Objects.equals(mob.customName(), applied)) {
                mob.customName(original);
                mob.setCustomNameVisible(visible);
                clearBackup(mob);
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
        final Component originalNameComponent;
        final Component originalDisplayName;
        String lastApplied;
        Component lastAppliedComponent;
        String lastFormatted;
        long expireAtTick;

        TrackedMob(LivingEntity mob, Component originalNameComponent, boolean originalVisible) {
            this.mob = mob;
            this.originalNameComponent = originalNameComponent;
            this.originalName = originalNameComponent != null ? LEGACY.serialize(originalNameComponent) : null;
            this.originalVisible = originalVisible;
            this.originalDisplayName = originalNameComponent != null ? originalNameComponent
                    : Component.translatable(mob.getType().translationKey());
        }
    }
}
