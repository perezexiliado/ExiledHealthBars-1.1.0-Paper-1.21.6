package me.exi.healthbars;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Repara los nombres por UUID sin convertir el mensaje traducible en texto plano. */
final class DeathMessageSanitizer {
    private DeathMessageSanitizer() { }

    record EntityNames(Component original, Component applied) { }

    static Component restoreEntityNames(Component message, Function<UUID, EntityNames> originalNames) {
        return restoreEntityNames(message, originalNames, null);
    }

    private static Component restoreEntityNames(Component message, Function<UUID, EntityNames> originalNames,
            HoverEvent.ShowEntity inheritedEntity) {
        if (message == null) {
            return null;
        }

        Component result = message;
        HoverEvent<?> hover = message.hoverEvent();
        // Un hover propio reemplaza al heredado, incluso si es SHOW_TEXT/SHOW_ITEM.
        HoverEvent.ShowEntity entity = hover == null ? inheritedEntity
                : hover.value() instanceof HoverEvent.ShowEntity ownEntity ? ownEntity : null;
        if (entity != null) {
            EntityNames names = originalNames.apply(entity.id());
            if (names != null) {
                HoverEvent.ShowEntity cleanedEntity = matchesAppliedName(entity.name(), names.applied())
                        ? entity.name(names.original()) : entity;
                if (matchesAppliedName(message, names.applied())) {
                    // Reemplazamos solo la barra aplicada, nunca el contenedor de
                    // un mensaje personalizado que use el mismo hover del mob.
                    return names.original().mergeStyle(message, Style.Merge.EVENTS, Style.Merge.INSERTION)
                            .hoverEvent(HoverEvent.showEntity(cleanedEntity));
                }
                if (hover != null) {
                    result = result.hoverEvent(HoverEvent.showEntity(cleanedEntity));
                }
            }
        }

        if (result instanceof TranslatableComponent translatable) {
            List<TranslationArgument> arguments = new ArrayList<>(translatable.arguments().size());
            for (TranslationArgument argument : translatable.arguments()) {
                arguments.add(argument.value() instanceof Component component
                        ? TranslationArgument.component(restoreEntityNames(component, originalNames, entity))
                        : argument);
            }
            result = translatable.arguments(arguments);
        }
        if (!message.children().isEmpty()) {
            List<Component> children = new ArrayList<>(message.children().size());
            for (Component child : message.children()) {
                children.add(restoreEntityNames(child, originalNames, entity));
            }
            result = result.children(children);
        }
        return result;
    }

    private static boolean matchesAppliedName(Component candidate, Component applied) {
        if (candidate != null && applied != null && candidate.style(applied.style()).equals(applied)) {
            // Los nombres ricos pueden incluir traducciones o keybinds. Sus hijos
            // deben coincidir exactamente; solo ignoramos el estilo raíz vanilla.
            return true;
        }
        // En barras con etiquetas literales, vanilla puede distribuir los colores
        // en hijos distintos sin cambiar el texto que ve el jugador.
        return isLiteral(candidate) && isLiteral(applied)
                && PlainTextComponentSerializer.plainText().serialize(candidate)
                        .equals(PlainTextComponentSerializer.plainText().serialize(applied));
    }

    private static boolean isLiteral(Component component) {
        if (!(component instanceof TextComponent)) {
            return false;
        }
        for (Component child : component.children()) {
            if (!isLiteral(child)) {
                return false;
            }
        }
        return true;
    }
}
