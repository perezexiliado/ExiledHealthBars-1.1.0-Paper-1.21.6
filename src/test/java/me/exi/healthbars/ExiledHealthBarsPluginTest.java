package me.exi.healthbars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Exercises public events/lifecycle with actual plugin code and in-memory entity state. */
final class ExiledHealthBarsPluginTest {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static Server server;
    private static Server previousServer;
    private static MockedStatic<RegistryAccess> registries;
    private static final int[] currentTick = {0};
    private ExiledHealthBarsPlugin plugin;
    private YamlConfiguration config;
    private final List<Runnable> pending = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final List<Long> timerPeriods = new ArrayList<>();

    @BeforeAll
    static void server() throws Exception {
        RegistryAccess access = mock(RegistryAccess.class);
        registries = mockStatic(RegistryAccess.class);
        registries.when(RegistryAccess::registryAccess).thenReturn(access);
        when(access.getRegistry(any(Class.class))).thenAnswer(call -> testRegistry());
        when(access.getRegistry(any(RegistryKey.class))).thenAnswer(call -> testRegistry());
        server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getLogger("ExiledHealthBarsPluginTest"));
        when(server.getName()).thenReturn("Paper test server");
        when(server.getVersion()).thenReturn("1.21.6");
        when(server.getBukkitVersion()).thenReturn("1.21.6-R0.1-SNAPSHOT");
        when(server.getCurrentTick()).thenAnswer(call -> currentTick[0]);
        // Bukkit.setServer logs Paper build information, absent from the standalone API jar.
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = (Server) serverField.get(null);
        serverField.set(null, server);
    }

    @AfterAll
    static void closeRegistries() throws Exception {
        registries.close();
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, previousServer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry testRegistry() {
        Registry registry = mock(Registry.class);
        when(registry.get(any(NamespacedKey.class))).thenAnswer(call -> mock(Attribute.class));
        return registry;
    }

    @BeforeEach
    void plugin() throws Exception {
        currentTick[0] = 0;
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            pending.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    timers.add(call.getArgument(1));
                    timerPeriods.add(call.getArgument(3));
                    return mock(BukkitTask.class);
                });

        // JavaPlugin requires a server plugin loader in its constructor. Mockito bypasses it;
        // real handlers still execute, with only configuration access replaced by a fixture.
        plugin = mock(ExiledHealthBarsPlugin.class, CALLS_REAL_METHODS);
        config = new YamlConfiguration();
        doReturn(config).when(plugin).getConfig();
        doReturn(Logger.getLogger("ExiledHealthBarsPluginTest")).when(plugin).getLogger();
        doNothing().when(plugin).reloadConfig();
        doReturn(true).when(plugin).isEnabled();
        field("tracked", new HashMap<>());
        field("externallyControlled", new HashSet<>());
        for (Field field : ExiledHealthBarsPlugin.class.getDeclaredFields()) {
            if (field.getType() == NamespacedKey.class) {
                field.setAccessible(true);
                field.set(plugin, new NamespacedKey("exiledhealthbars", field.getName().toLowerCase()));
            }
        }
        Method options = ExiledHealthBarsPlugin.class.getDeclaredMethod("loadOptions");
        options.setAccessible(true);
        options.invoke(plugin);
    }

    @Test
    void disableRestoresCompleteAdventureNameAndOriginalVisibility() {
        Component original = richName();
        MobState mob = mob(original, false);
        hit(mob, 2);
        runPending();
        assertNotEquals(original, mob.name);
        assertTrue(mob.visible);

        plugin.onDisable();

        assertEquals(original, mob.name, "Restoration must keep hover, click and component styling");
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void nextHitDoesNotOverwriteNameTakenOverByAnotherPlugin() {
        MobState mob = mob(Component.text("Original"), false);
        hit(mob, 2);
        runPending();
        Component replacement = Component.text("Quest target", NamedTextColor.GOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Managed by quests")));
        mob.name = replacement;
        mob.health = 16;

        // A player can hit again before the periodic refresh notices the external rename.
        hit(mob, 2);
        runPending();

        assertEquals(replacement, mob.name);
        assertTrue(mob.persistent.isEmpty(), "Giving up ownership must also discard stale backups");
    }

    @Test
    void cancelledDeathKeepsBarAndItsOriginalNameBackup() throws Exception {
        MobState mob = mob(richName(), false);
        hit(mob, 2);
        runPending();
        Component bar = mob.name;
        Map<NamespacedKey, Object> backup = new HashMap<>(mob.persistent);
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(mob.entity);
        when(death.isCancelled()).thenReturn(true);

        dispatchDeath(death);

        assertEquals(bar, mob.name);
        assertEquals(backup, mob.persistent);
        plugin.onDisable();
        assertEquals(richName(), mob.name, "A later shutdown must still have the original name");
    }

    @Test
    void damageQueuedBeforeReloadCannotCreateABarAfterReload() {
        Component original = Component.text("Original");
        MobState mob = mob(original, false);
        hit(mob, 2);

        plugin.onCommand(mock(CommandSender.class), null, "exhealth", new String[]{"reload"});
        runPending();

        assertEquals(original, mob.name);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void zeroDamageDoesNotStartCombatDisplay() {
        Component original = Component.text("Original");
        MobState mob = mob(original, false);

        hit(mob, 0);
        runPending();

        assertEquals(original, mob.name);
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void positiveDamageStartsCombatDisplay() {
        MobState mob = mob(Component.text("Original"), false);

        hit(mob, 2);
        runPending();

        assertNotEquals(Component.text("Original"), mob.name);
        assertTrue(mob.visible);
        assertFalse(mob.persistent.isEmpty());
    }

    @Test
    void componentOnlyExternalRenameIsPreservedOnDisable() {
        MobState mob = mob(Component.text("Original"), false);
        hit(mob, 2);
        runPending();
        Component externalName = mob.name.font(Key.key("minecraft:uniform"));
        assertEquals(LEGACY.serialize(mob.name), LEGACY.serialize(externalName));
        mob.name = externalName;

        plugin.onDisable();

        assertEquals(externalName, mob.name, "Ownership comparison must not discard a changed font");
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void reloadOfEntityRestoresPersistedRichNameEvenInCombatMode() throws Exception {
        Component original = richName();
        MobState mob = mob(original, false);
        hit(mob, 2);
        runPending();
        assertNotEquals(original, mob.name);

        // A new plugin instance represents a restart after an abrupt process exit.
        plugin();
        EntitiesLoadEvent load = mock(EntitiesLoadEvent.class);
        when(load.getEntities()).thenReturn(List.of(mob.entity));
        plugin.onEntitiesLoad(load);

        assertEquals(original, mob.name);
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void unloadedEntitiesRestoreNamesEvenWhenAlreadyInvalid() {
        Component original = richName();
        MobState mob = mob(original, false);
        hit(mob, 2);
        runPending();
        when(mob.entity.isValid()).thenReturn(false);
        EntitiesUnloadEvent unload = mock(EntitiesUnloadEvent.class);
        when(unload.getEntities()).thenReturn(List.of(mob.entity));

        plugin.onEntitiesUnload(unload);

        assertEquals(original, mob.name);
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void combatDurationUsesElapsedServerTicksBetweenTimerCallbacks() {
        config.set("display.update-interval-ticks", 40);
        plugin.onCommand(mock(CommandSender.class), null, "exhealth", new String[]{"reload"});
        currentTick[0] = 1;
        timers.getLast().run();
        MobState mob = mob(richName(), false);
        currentTick[0] = 40;
        hit(mob, 2);
        currentTick[0] = 41;
        runPending();
        Component bar = mob.name;
        for (int tick = 41; tick <= 201; tick += 40) {
            currentTick[0] = tick;
            timers.getLast().run();
        }

        assertEquals(bar, mob.name, "Only 160 ticks passed since the bar was activated");
        currentTick[0] = 241;
        timers.getLast().run();
        assertEquals(richName(), mob.name);
        assertFalse(mob.visible);
    }

    @Test
    void queuedDamageAfterDisableDoesNotStartABar() {
        Component original = Component.text("Original");
        MobState mob = mob(original, false);
        hit(mob, 2);

        plugin.onDisable();
        runPending();

        assertEquals(original, mob.name);
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void vanillaTransformationDoesNotCopyTheBarAsPermanentTargetName() throws Exception {
        Component original = richName();
        MobState source = mob(original, false);
        hit(source, 2);
        runPending();
        MobState transformed = mob(source.name, true);
        when(transformed.entity.isValid()).thenReturn(false); // Not yet added to the world.
        EntityTransformEvent event = transform(source, transformed, false);

        dispatchTransform(event);

        assertEquals(original, transformed.name);
        assertFalse(transformed.visible);
        assertTrue(transformed.persistent.isEmpty());
    }

    @Test
    void cancelledTransformationDoesNotModifyItsProposedTarget() throws Exception {
        MobState source = mob(richName(), false);
        hit(source, 2);
        runPending();
        Component bar = source.name;
        MobState transformed = mob(bar, true);
        EntityTransformEvent event = transform(source, transformed, true);

        dispatchTransform(event);

        assertEquals(bar, transformed.name);
        assertTrue(transformed.visible);
        assertEquals(bar, source.name);
        assertFalse(source.persistent.isEmpty());
    }

    @Test
    void transformationPreservesAnotherPluginsReplacementName() throws Exception {
        MobState source = mob(richName(), false);
        hit(source, 2);
        runPending();
        Component replacement = Component.text("Quest target", NamedTextColor.GOLD);
        source.name = replacement;
        MobState transformed = mob(replacement, true);

        dispatchTransform(transform(source, transformed, false));

        assertEquals(replacement, transformed.name);
        assertTrue(transformed.visible);
    }

    @Test
    void transformationRestoresCopiedBarWhenAnotherListenerRenamesSourceFirst() throws Exception {
        Component original = richName();
        MobState source = mob(original, false);
        hit(source, 2);
        runPending();
        MobState transformed = mob(source.name, true);
        Component replacement = Component.text("Quest source", NamedTextColor.GOLD);
        source.name = replacement;

        dispatchTransform(transform(source, transformed, false));

        assertEquals(original, transformed.name, "The target copied the bar before the source was renamed");
        assertFalse(transformed.visible);
        assertEquals(replacement, source.name);
    }

    @Test
    void transformationCanRestoreCopiedBarFromBackupAfterSourceWasRenamed() throws Exception {
        Component original = richName();
        MobState source = mob(original, false);
        hit(source, 2);
        runPending();
        MobState transformed = mob(source.name, true);
        Component replacement = Component.text("Quest source", NamedTextColor.GOLD);
        source.name = replacement;
        plugin(); // Only the source's persistent backup remains after a restart.

        dispatchTransform(transform(source, transformed, false));

        assertEquals(original, transformed.name);
        assertFalse(transformed.visible);
        assertEquals(replacement, source.name);
    }

    @Test
    void entityLoadRecoversLegacyBackupFromPreviousPluginVersion() throws Exception {
        String original = "\u00a7aLegacy name";
        String bar = "\u00a7c[----] 5/20";
        MobState mob = mob(LEGACY.deserialize(bar), true);
        mob.persistent.put(key("managedKey"), (byte) 1);
        mob.persistent.put(key("oldNameKey"), original);
        mob.persistent.put(key("lastAppliedKey"), bar);
        mob.persistent.put(key("oldVisibleKey"), (byte) 0);
        EntitiesLoadEvent event = mock(EntitiesLoadEvent.class);
        when(event.getEntities()).thenReturn(List.of(mob.entity));

        plugin.onEntitiesLoad(event);

        assertEquals(LEGACY.deserialize(original), mob.name);
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    @Test
    void nearbyScanReusesFullCapacityImmediatelyWhenPlayersMove() {
        config.set("display.mode", "NEARBY");
        config.set("display.max-tracked-mobs", 10);
        PlayerState player = onlinePlayer();
        List<MobState> oldGroup = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MobState mob = mob(Component.text("Old " + i), false);
            place(mob, player.world, i + 1);
            oldGroup.add(mob);
            player.nearby.add(mob.entity);
        }
        startConfiguredTask();
        tickTimer(1);
        assertTrue(oldGroup.stream().allMatch(mob -> mob.visible));
        player.nearby.clear();
        MobState incoming = mob(Component.text("Incoming"), false);
        place(incoming, player.world, 1);
        player.nearby.add(incoming.entity);

        tickTimer(21);

        assertNotEquals(Component.text("Incoming"), incoming.name,
                "The same scan must release the old group and display the new group");
        assertTrue(incoming.visible);
        for (int i = 0; i < oldGroup.size(); i++) {
            assertEquals(Component.text("Old " + i), oldGroup.get(i).name);
            assertFalse(oldGroup.get(i).visible);
        }
    }

    @Test
    void fastNearbyScanRunsEvenWithSlowHealthUpdates() {
        config.set("display.mode", "NEARBY");
        config.set("display.scan-interval-ticks", 10);
        config.set("display.update-interval-ticks", 40);
        PlayerState player = onlinePlayer();
        startConfiguredTask();
        assertEquals(10L, timerPeriods.getLast());
        tickTimer(1);
        MobState arriving = mob(Component.text("Arriving"), false);
        place(arriving, player.world, 1);
        player.nearby.add(arriving.entity);

        tickTimer(11);

        assertNotEquals(Component.text("Arriving"), arriving.name);
        assertTrue(arriving.visible);
    }

    @Test
    void independentScanAndUpdateIntervalsMeetTheirDeadlines() {
        config.set("display.mode", "NEARBY");
        config.set("display.scan-interval-ticks", 20);
        config.set("display.update-interval-ticks", 19);
        PlayerState player = onlinePlayer();
        startConfiguredTask();
        tickTimer(1);
        MobState arriving = mob(Component.text("Arriving"), false);
        place(arriving, player.world, 1);
        player.nearby.add(arriving.entity);

        int actualScheduledPeriod = Math.toIntExact(timerPeriods.getLast());
        for (int tick = 1 + actualScheduledPeriod; tick <= 21; tick += actualScheduledPeriod) {
            tickTimer(tick);
        }

        assertNotEquals(Component.text("Arriving"), arriving.name,
                "The second scan is due at tick 21 even when updates have a different interval");
        assertTrue(arriving.visible);
    }

    @Test
    void combatExternalOwnershipResetsAfterPlayerLeavesTheArea() {
        PlayerState player = onlinePlayer();
        startConfiguredTask();
        MobState mob = mob(Component.text("Original"), false);
        place(mob, player.world, 1);
        hit(mob, 2);
        runPending();
        Component externalName = Component.text("External");
        mob.name = externalName;
        hit(mob, 2);
        runPending();
        tickTimer(1);
        hit(mob, 2);
        runPending();
        assertEquals(externalName, mob.name, "Suppression must remain while a player is nearby");
        player.location = new Location(player.world, 1000, 64, 0);
        tickTimer(21);
        player.location = new Location(player.world, 0, 64, 0);

        hit(mob, 2);
        runPending();

        assertNotEquals(externalName, mob.name, "Returning to the mob starts a new ownership cycle");
        plugin.onDisable();
        assertEquals(externalName, mob.name);
    }

    @Test
    void activeBarKeepsRichTranslatedLabelAndRestoresLongNameCompletely() {
        Component original = Component.translatable("entity.minecraft.zombie")
                .font(Key.key("custom:guards"))
                .append(Component.text(" " + "N".repeat(350), NamedTextColor.GOLD));
        MobState mob = mob(original, true);

        hit(mob, 2);
        runPending();

        assertTrue(contains(mob.name, original),
                "A visible custom name must keep its translated content and font above the bar");
        plugin.onDisable();
        assertEquals(original, mob.name);
        assertTrue(mob.visible);
    }

    @Test
    void combatExpirySurvivesServerTickCounterOverflow() {
        currentTick[0] = Integer.MAX_VALUE - 5;
        startConfiguredTask();
        MobState mob = mob(richName(), false);
        currentTick[0] = Integer.MAX_VALUE - 4;
        hit(mob, 2);
        currentTick[0] = Integer.MAX_VALUE - 3;
        runPending();
        Component bar = mob.name;

        tickTimer(Integer.MIN_VALUE + 191); // 195 ticks since acquisition.
        assertEquals(bar, mob.name);
        tickTimer(Integer.MIN_VALUE + 196); // 200 ticks since acquisition.

        assertEquals(richName(), mob.name);
        assertFalse(mob.visible);
        assertTrue(mob.persistent.isEmpty());
    }

    private static boolean contains(Component root, Component expected) {
        return root.equals(expected) || root.children().stream().anyMatch(child -> contains(child, expected));
    }

    private void startConfiguredTask() {
        plugin.onCommand(mock(CommandSender.class), null, "exhealth", new String[]{"reload"});
    }

    private void tickTimer(int tick) {
        currentTick[0] = tick;
        timers.getLast().run();
    }

    private PlayerState onlinePlayer() {
        PlayerState state = new PlayerState();
        when(state.player.isValid()).thenReturn(true);
        when(state.player.getWorld()).thenReturn(state.world);
        when(state.player.getLocation()).thenAnswer(call -> state.location);
        when(state.player.getNearbyEntities(anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(call -> new ArrayList<>(state.nearby));
        doReturn(List.of(state.player)).when(server).getOnlinePlayers();
        return state;
    }

    private void place(MobState mob, World world, double x) {
        when(mob.entity.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(mob.entity.getWorld()).thenReturn(world);
    }

    private EntityTransformEvent transform(MobState source, MobState transformed, boolean cancelled) {
        EntityTransformEvent event = mock(EntityTransformEvent.class);
        when(event.getEntity()).thenReturn(source.entity);
        when(event.getTransformedEntities()).thenReturn(List.of(transformed.entity));
        when(event.isCancelled()).thenReturn(cancelled);
        return event;
    }

    private void dispatchTransform(EntityTransformEvent event) throws Exception {
        Method method = ExiledHealthBarsPlugin.class.getMethod("onTransform", EntityTransformEvent.class);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        if (!event.isCancelled() || !handler.ignoreCancelled()) {
            method.invoke(plugin, event);
        }
    }

    private static Component richName() {
        return Component.text("Guard", NamedTextColor.BLUE)
                .append(Component.text(" captain", NamedTextColor.GOLD))
                .hoverEvent(HoverEvent.showText(Component.text("Special named mob")))
                .clickEvent(ClickEvent.openUrl("https://example.org/guard"));
    }

    private void hit(MobState mob, double damage) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(mock(Player.class));
        when(event.getEntity()).thenReturn(mob.entity);
        when(event.getFinalDamage()).thenReturn(damage);
        plugin.onDamage(event);
    }

    private void runPending() {
        List<Runnable> jobs = new ArrayList<>(pending);
        pending.clear();
        jobs.forEach(Runnable::run);
    }

    private void dispatchDeath(EntityDeathEvent event) throws Exception {
        EventHandler handler = ExiledHealthBarsPlugin.class.getMethod("onDeath", EntityDeathEvent.class)
                .getAnnotation(EventHandler.class);
        if (!event.isCancelled() || !handler.ignoreCancelled()) {
            plugin.onDeath(event);
        }
    }

    private void field(String name, Object value) throws Exception {
        Field field = ExiledHealthBarsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private NamespacedKey key(String name) throws Exception {
        Field field = ExiledHealthBarsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return (NamespacedKey) field.get(plugin);
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private MobState mob(Component name, boolean visible) {
        MobState state = new MobState(name, visible);
        LivingEntity entity = state.entity;
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(server.getEntity(id)).thenReturn(entity);
        when(entity.getType()).thenReturn(EntityType.ZOMBIE);
        when(entity.isValid()).thenReturn(true);
        when(entity.getHealth()).thenAnswer(call -> state.health);
        when(entity.customName()).thenAnswer(call -> state.name);
        when(entity.getCustomName()).thenAnswer(call -> state.name == null ? null : LEGACY.serialize(state.name));
        org.mockito.Mockito.doAnswer(call -> {
            String legacy = call.getArgument(0);
            state.name = legacy == null ? null : LEGACY.deserialize(legacy);
            return null;
        }).when(entity).setCustomName(nullable(String.class));
        org.mockito.Mockito.doAnswer(call -> {
            state.name = call.getArgument(0);
            return null;
        }).when(entity).customName(nullable(Component.class));
        when(entity.isCustomNameVisible()).thenAnswer(call -> state.visible);
        org.mockito.Mockito.doAnswer(call -> {
            state.visible = call.getArgument(0);
            return null;
        }).when(entity).setCustomNameVisible(anyBoolean());

        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(container);
        when(container.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(call -> state.persistent.get(call.getArgument(0)));
        when(container.has(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(call -> state.persistent.containsKey(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> {
            state.persistent.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(container).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        org.mockito.Mockito.doAnswer(call -> {
            state.persistent.remove(call.getArgument(0));
            return null;
        }).when(container).remove(any(NamespacedKey.class));
        return state;
    }

    private static final class MobState {
        final LivingEntity entity = mock(LivingEntity.class);
        final Map<NamespacedKey, Object> persistent = new HashMap<>();
        Component name;
        boolean visible;
        double health = 20;

        MobState(Component name, boolean visible) {
            this.name = name;
            this.visible = visible;
        }
    }

    private static final class PlayerState {
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final List<Entity> nearby = new ArrayList<>();
        Location location = new Location(world, 0, 64, 0);
    }
}
