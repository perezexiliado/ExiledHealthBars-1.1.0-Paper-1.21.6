package me.exi.healthbars;

import java.util.Locale;

/** Formato de nombres vanilla. Independiente de Bukkit para facilitar las pruebas. */
final class HealthBarFormatter {
    private static final char SECTION = '\u00a7';
    private static final String FILLED_BLOCK = "█";
    private static final String EMPTY_BLOCK = "░";

    private HealthBarFormatter() { }

    static String format(double health, double maxHealth, int barLength, boolean showNumbers, String label) {
        maxHealth = Double.isFinite(maxHealth) && maxHealth > 0.0 ? maxHealth : 20.0;
        health = Double.isFinite(health) ? Math.max(0.0, Math.min(health, maxHealth)) : 0.0;
        barLength = Math.max(5, Math.min(barLength, 20));

        final double ratio = health / maxHealth;
        final int filled = Math.max(0, Math.min(barLength, (int) Math.ceil(ratio * barLength)));
        final String color = colorAt(ratio);
        final StringBuilder line = new StringBuilder(150);

        if (label != null && !label.isBlank()) {
            line.append(label).append(' ').append(SECTION).append('r');
        }

        // Marco oscuro y bloques continuos para que parezca una barra sólida.
        line.append(SECTION).append('8').append('[').append(color);
        if (filled > 0) {
            line.append(FILLED_BLOCK.repeat(filled));
        }
        if (filled < barLength) {
            // Al llegar a cero coloreamos toda la barra vacía de rojo en vez de gris.
            if (filled > 0) {
                line.append(SECTION).append('8');
            }
            line.append(EMPTY_BLOCK.repeat(barLength - filled));
        }
        line.append(SECTION).append('8').append(']');
        if (showNumbers) {
            line.append(' ').append(color)
                    .append(number(health)).append(SECTION).append('7').append('/')
                    .append(number(maxHealth))
                    .append(SECTION).append('c').append(" ♥");
        }
        return line.toString();
    }

    /** 0.0 -> #FF0000, 0.5 -> #FFFF00, 1.0 -> #00FF00. */
    static String hexAt(double fraction) {
        final double t = Double.isFinite(fraction) ? Math.max(0.0, Math.min(1.0, fraction)) : 0.0;
        final int red = t <= 0.5 ? 255 : (int) Math.round(255 * (2.0 - 2.0 * t));
        final int green = t >= 0.5 ? 255 : (int) Math.round(510 * t);
        return String.format(Locale.ROOT, "#%02X%02X00", red, green);
    }

    /** Formato RGB clásico §x§R§R§G§G§B§B: legible por Paper y clientes Java modernos. */
    static String colorAt(double fraction) {
        final String hex = hexAt(fraction).substring(1);
        final StringBuilder result = new StringBuilder(14).append(SECTION).append('x');
        for (int i = 0; i < hex.length(); i++) {
            result.append(SECTION).append(hex.charAt(i));
        }
        return result.toString();
    }

    private static String number(double value) {
        String rounded = String.format(Locale.ROOT, "%.1f", value);
        return rounded.endsWith(".0") ? rounded.substring(0, rounded.length() - 2) : rounded;
    }
}
