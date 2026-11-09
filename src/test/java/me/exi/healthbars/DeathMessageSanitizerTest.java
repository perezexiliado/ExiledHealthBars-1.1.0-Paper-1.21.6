package me.exi.healthbars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DeathMessageSanitizerTest {
    private static final UUID MOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Key ZOMBIE_TYPE = Key.key("minecraft:zombie");
    private static final Component BAR_NAME = Component.text("[██░] 12/20 ♥", NamedTextColor.GREEN);
    private static final Component VANILLA_NAME = Component.translatable("entity.minecraft.zombie");

    @Test
    void preservesDisabledDeathMessagesWithoutLookingUpNames() {
        assertNull(DeathMessageSanitizer.restoreEntityNames(null, id -> {
            fail("A disabled death message must not inspect entities");
            return null;
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"death.attack.mob", "death.attack.arrow", "death.fell.assist"})
    void restoresTranslatedMobNameInMeleeProjectileAndIndirectDeaths(String deathKey) {
        Component victim = playerName();
        Component attacker = barEntity(MOB_ID);
        TranslatableComponent message = Component.translatable(deathKey, victim, attacker);

        Component restored = DeathMessageSanitizer.restoreEntityNames(message,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get);

        assertEquals(Component.translatable(deathKey, victim, restoredEntity(VANILLA_NAME, attacker)), restored);
        Component restoredAttacker = componentArgument((TranslatableComponent) restored, 1);
        assertEquals("entity.minecraft.zombie", assertInstanceOf(TranslatableComponent.class, restoredAttacker).key());
        assertNull(restoredAttacker.color(), "Health bar coloring must not bleed into a vanilla mob name");
    }

    @Test
    void preservesRichCustomNameAndEntityInteractionsWithoutBarStyling() {
        Component customName = Component.text("Guardián", TextColor.color(0x35a9e5), TextDecoration.BOLD)
                .append(Component.text(" nocturno", TextColor.color(0xe585d1)));
        Component attacker = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("██", NamedTextColor.GREEN))
                .append(Component.text("░] 12/20 ♥", NamedTextColor.RED))
                .insertion(MOB_ID.toString()).clickEvent(ClickEvent.suggestCommand("/msg " + MOB_ID + " "))
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, BAR_NAME))
                .font(Key.key("minecraft:uniform"))
                .decorate(TextDecoration.ITALIC).decorate(TextDecoration.STRIKETHROUGH);

        Component restored = DeathMessageSanitizer.restoreEntityNames(attacker,
                Map.of(MOB_ID, names(customName, BAR_NAME))::get);

        assertEquals(restoredEntity(customName, attacker), restored);
        assertEquals(TextDecoration.State.NOT_SET, restored.decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.NOT_SET, restored.decoration(TextDecoration.STRIKETHROUGH));
        assertNull(restored.font());
        HoverEvent.ShowEntity hover = assertInstanceOf(HoverEvent.ShowEntity.class, restored.hoverEvent().value());
        assertEquals(customName, hover.name());
        assertEquals(MOB_ID, hover.id());
        assertEquals(ZOMBIE_TYPE, hover.type());
        assertEquals(attacker.insertion(), restored.insertion());
        assertEquals(attacker.clickEvent(), restored.clickEvent());
    }

    @Test
    void usesEntityUuidToDistinguishIdenticalBarsAndLeavesUntrackedEntitiesAlone() {
        Component trackedMob = barEntity(MOB_ID);
        Component untrackedMob = barEntity(OTHER_ID);
        Component playerWithSameVisibleName = BAR_NAME.hoverEvent(
                HoverEvent.showEntity(Key.key("minecraft:player"), PLAYER_ID, BAR_NAME));
        Component message = Component.text("Combat: ").append(trackedMob).append(untrackedMob)
                .append(playerWithSameVisibleName);

        Component restored = DeathMessageSanitizer.restoreEntityNames(message,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get);

        assertEquals(Component.text("Combat: ").append(restoredEntity(VANILLA_NAME, trackedMob))
                .append(untrackedMob).append(playerWithSameVisibleName), restored);
    }

    @Test
    void restoresEveryTrackedMobUsingItsOwnOriginalName() {
        Component first = barEntity(MOB_ID);
        Component second = barEntity(OTHER_ID);
        Component secondName = Component.text("Centinela", NamedTextColor.GOLD);
        Component message = Component.textOfChildren(first, Component.text(" / "), second);

        Component restored = DeathMessageSanitizer.restoreEntityNames(message,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME), OTHER_ID, names(secondName, BAR_NAME))::get);

        assertEquals(Component.textOfChildren(restoredEntity(VANILLA_NAME, first), Component.text(" / "),
                restoredEntity(secondName, second)), restored);
    }

    @Test
    void traversesNestedChildrenAndTranslationArgumentsWhilePreservingMessageStyle() {
        Component attacker = barEntity(MOB_ID);
        Component nested = Component.text("source: ").append(attacker);
        Component message = Component.translatable("death.attack.mob", playerName(), nested)
                .fallback("%s was slain by %s").color(NamedTextColor.YELLOW)
                .append(Component.text(" (").append(attacker).append(Component.text(")")));
        Component expected = Component.translatable("death.attack.mob", playerName(),
                        Component.text("source: ").append(restoredEntity(VANILLA_NAME, attacker)))
                .fallback("%s was slain by %s").color(NamedTextColor.YELLOW)
                .append(Component.text(" (").append(restoredEntity(VANILLA_NAME, attacker)).append(Component.text(")")));

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(message,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void preservesNumericAndBooleanTranslationArgumentsAsTypedValues() {
        Component attacker = barEntity(MOB_ID);
        TranslatableComponent message = Component.translatable("test.mixed.arguments").arguments(List.of(
                TranslationArgument.component(attacker), TranslationArgument.numeric(3.5), TranslationArgument.bool(true)));

        TranslatableComponent restored = assertInstanceOf(TranslatableComponent.class,
                DeathMessageSanitizer.restoreEntityNames(message, Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));

        assertEquals(restoredEntity(VANILLA_NAME, attacker), componentArgument(restored, 0));
        assertEquals(message.arguments().get(1), restored.arguments().get(1));
        assertEquals(message.arguments().get(2), restored.arguments().get(2));
        assertInstanceOf(Number.class, restored.arguments().get(1).value());
        assertInstanceOf(Boolean.class, restored.arguments().get(2).value());
    }

    @Test
    void preservesWeaponNameAndHoverAlongsideRestoredKiller() {
        Component attacker = barEntity(MOB_ID);
        Component weapon = Component.translatable("chat.square_brackets", Component.text("Espada del jefe"))
                .color(NamedTextColor.AQUA).hoverEvent(HoverEvent.showItem(Key.key("minecraft:diamond_sword"), 1));
        Component message = Component.translatable("death.attack.mob.item", playerName(), attacker, weapon);

        assertEquals(Component.translatable("death.attack.mob.item", playerName(),
                        restoredEntity(VANILLA_NAME, attacker), weapon),
                DeathMessageSanitizer.restoreEntityNames(message, Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void leavesUnrelatedVisibleTextAndHoverTextUntouched() {
        Component message = Component.translatable("death.attack.generic", playerName())
                .append(BAR_NAME.hoverEvent(HoverEvent.showText(barEntity(MOB_ID))));

        assertEquals(message, DeathMessageSanitizer.restoreEntityNames(message, id -> {
            if (MOB_ID.equals(id)) {
                fail("SHOW_TEXT content is not the killer's visible entity component");
            }
            return null;
        }));
    }

    @Test
    void handlesNativeLikeJsonWithHealthBarChildSegmentsAndEntityHover() {
        GsonComponentSerializer serializer = GsonComponentSerializer.gson();
        String nativeJson = """
                {"translate":"death.attack.mob.item","with":[
                  {"text":"Alex","insertion":"Alex","hoverEvent":{"action":"show_entity","contents":{
                    "type":"minecraft:player","id":"11111111-1111-1111-1111-111111111111","name":{"text":"Alex"}}}},
                  {"text":"","insertion":"22222222-2222-2222-2222-222222222222","color":"red","italic":true,
                   "hoverEvent":{"action":"show_entity","contents":{"type":"minecraft:zombie",
                    "id":"22222222-2222-2222-2222-222222222222","name":{"text":"[██░] 12/20 ♥","color":"#80ff00"}}},
                   "extra":[{"text":"[","color":"dark_gray"},{"text":"██","color":"#80ff00"},
                    {"text":"░] 12/20 ♥","color":"gray"}]},
                  {"translate":"chat.square_brackets","with":[{"text":"Espada del jefe","color":"#35a9e5"}],
                   "hoverEvent":{"action":"show_item","contents":{"id":"minecraft:diamond_sword","count":1}}}
                ],"color":"yellow"}
                """;
        TranslatableComponent nativeMessage = assertInstanceOf(TranslatableComponent.class, serializer.deserialize(nativeJson));
        Component attacker = componentArgument(nativeMessage, 1);
        Component expected = nativeMessage.arguments(List.of(componentArgument(nativeMessage, 0),
                restoredEntity(VANILLA_NAME, attacker), componentArgument(nativeMessage, 2)));

        Component restored = DeathMessageSanitizer.restoreEntityNames(nativeMessage,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get);
        String json = serializer.serialize(restored);

        assertEquals(expected, restored);
        assertEquals(restored, serializer.deserialize(json));
        assertFalse(json.contains("██"));
        assertFalse(json.contains("12/20"));
        assertFalse(json.contains("♥"));
    }

    @Test
    void preservesCustomMessageWrapperThatUsesTheKillersEntityHover() {
        Component attacker = barEntity(MOB_ID);
        Component wrapper = Component.text("Alex was defeated by ").append(attacker).append(Component.text("!"))
                .color(NamedTextColor.YELLOW).hoverEvent(attacker.hoverEvent());
        Component expected = Component.text("Alex was defeated by ").append(restoredEntity(VANILLA_NAME, attacker))
                .append(Component.text("!")).color(NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, VANILLA_NAME));

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(wrapper,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void preservesTranslatedMessageWrapperWithEntityHoverAndRestoresItsArguments() {
        Component attacker = barEntity(MOB_ID);
        Component message = Component.translatable("death.attack.mob", playerName(), attacker)
                .fallback("%s was slain by %s").hoverEvent(attacker.hoverEvent());
        Component expected = Component.translatable("death.attack.mob", playerName(), restoredEntity(VANILLA_NAME, attacker))
                .fallback("%s was slain by %s").hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, VANILLA_NAME));

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(message,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void preservesAnotherPluginsVisibleNameWhileCleaningTheBarTooltip() {
        Component alternateName = Component.text("Guardián elegido por otro plugin", NamedTextColor.GOLD)
                .decorate(TextDecoration.ITALIC).hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, BAR_NAME));
        Component expected = alternateName.hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, VANILLA_NAME));

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(alternateName,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void preservesAnotherPluginsTooltipWhileReplacingTheVisibleBar() {
        Component alternateTooltip = Component.text("Nombre del tooltip elegido por otro plugin", NamedTextColor.GOLD);
        Component attacker = barEntity(MOB_ID).hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, alternateTooltip));
        Component expected = VANILLA_NAME.insertion(attacker.insertion()).clickEvent(attacker.clickEvent())
                .hoverEvent(attacker.hoverEvent());

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(attacker,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void preservesAbsentTooltipNameWhileReplacingTheVisibleBar() {
        Component attacker = barEntity(MOB_ID).hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID));
        Component expected = VANILLA_NAME.insertion(attacker.insertion()).clickEvent(attacker.clickEvent())
                .hoverEvent(attacker.hoverEvent());

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(attacker,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void restoresBareBarChildUnderInheritedEntityHoverAndPreservesItsMessageWrapper() {
        HoverEvent<HoverEvent.ShowEntity> entityHover = HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, BAR_NAME);
        Component wrapper = Component.text("Alex was defeated by ").append(BAR_NAME).append(Component.text("!"))
                .color(NamedTextColor.YELLOW).hoverEvent(entityHover);
        Component expected = Component.text("Alex was defeated by ")
                .append(VANILLA_NAME.hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, VANILLA_NAME)))
                .append(Component.text("!")).color(NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, VANILLA_NAME));
        // Otro texto idéntico fuera del hover del mob no representa a esa entidad.
        Component message = Component.text("Unrelated bar: ").append(BAR_NAME).append(wrapper);
        Component expectedMessage = Component.text("Unrelated bar: ").append(BAR_NAME).append(expected);

        assertEquals(expectedMessage, DeathMessageSanitizer.restoreEntityNames(message,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void ownUnrelatedHoverOverridesInheritedEntityContextForChildrenAndTheirDescendants() {
        Component textHover = BAR_NAME.hoverEvent(HoverEvent.showText(Component.text("An unrelated tooltip")));
        Component itemHover = Component.text("Weapon: ").append(BAR_NAME)
                .hoverEvent(HoverEvent.showItem(Key.key("minecraft:diamond_sword"), 1));
        Component differentEntity = BAR_NAME.hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, OTHER_ID, BAR_NAME));
        Component wrapper = Component.textOfChildren(textHover, itemHover, differentEntity)
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, BAR_NAME));
        Component expected = Component.textOfChildren(textHover, itemHover, differentEntity)
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, VANILLA_NAME));

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(wrapper,
                Map.of(MOB_ID, names(VANILLA_NAME, BAR_NAME))::get));
    }

    @Test
    void restoresExactAppliedBarThatPreservesTranslatedAndKeybindLabelComponents() {
        Component originalName = Component.translatable("entity.minecraft.zombie", "Guardián traducido")
                .font(Key.key("minecraft:uniform")).color(TextColor.color(0x35a9e5))
                .append(Component.keybind("key.jump").color(NamedTextColor.GOLD));
        Component applied = Component.empty().append(originalName).append(Component.space()).append(BAR_NAME);
        Component attacker = applied.insertion(MOB_ID.toString()).clickEvent(ClickEvent.suggestCommand("/msg " + MOB_ID + " "))
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, applied));

        assertEquals(restoredEntity(originalName, attacker), DeathMessageSanitizer.restoreEntityNames(attacker,
                Map.of(MOB_ID, names(originalName, applied))::get));
    }

    @Test
    void doesNotMatchDifferentTranslationKeysWithIdenticalFallbackLabels() {
        Component originalName = Component.translatable("entity.minecraft.zombie", "Guardián");
        Component applied = Component.empty().append(originalName).append(Component.space()).append(BAR_NAME);
        Component alternateVisible = Component.empty()
                .append(Component.translatable("entity.minecraft.skeleton", "Guardián"))
                .append(Component.space()).append(BAR_NAME)
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, applied));
        Component expected = alternateVisible.hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, MOB_ID, originalName));

        assertEquals(expected, DeathMessageSanitizer.restoreEntityNames(alternateVisible,
                Map.of(MOB_ID, names(originalName, applied))::get));
    }

    private static DeathMessageSanitizer.EntityNames names(Component original, Component applied) {
        return new DeathMessageSanitizer.EntityNames(original, applied);
    }

    private static Component playerName() {
        Component name = Component.text("Alex");
        return name.insertion("Alex").hoverEvent(HoverEvent.showEntity(Key.key("minecraft:player"), PLAYER_ID, name));
    }

    private static Component barEntity(UUID id) {
        return BAR_NAME.insertion(id.toString()).clickEvent(ClickEvent.suggestCommand("/msg " + id + " "))
                .hoverEvent(HoverEvent.showEntity(ZOMBIE_TYPE, id, BAR_NAME));
    }

    private static Component restoredEntity(Component originalName, Component barEntity) {
        HoverEvent.ShowEntity hover = (HoverEvent.ShowEntity) barEntity.hoverEvent().value();
        return originalName.insertion(barEntity.insertion()).clickEvent(barEntity.clickEvent())
                .hoverEvent(HoverEvent.showEntity(hover.type(), hover.id(), originalName));
    }

    private static Component componentArgument(TranslatableComponent message, int index) {
        return assertInstanceOf(Component.class, message.arguments().get(index).value());
    }
}
