package me.exi.healthbars;

/** Ejecutar sin servidor ni dependencias: java -ea me.exi.healthbars.HealthBarFormatterSelfTest */
public final class HealthBarFormatterSelfTest {
    private static void equals(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError("Esperado " + expected + ", recibido " + actual);
    }

    private static String visibleText(String value) {
        return value.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }

    public static void main(String[] args) {
        equals(HealthBarFormatter.hexAt(1.0), "#00FF00");
        equals(HealthBarFormatter.hexAt(0.75), "#80FF00");
        equals(HealthBarFormatter.hexAt(0.5), "#FFFF00");
        equals(HealthBarFormatter.hexAt(0.25), "#FF8000");
        equals(HealthBarFormatter.hexAt(0.0), "#FF0000");
        equals(HealthBarFormatter.hexAt(-1.0), "#FF0000");
        equals(HealthBarFormatter.hexAt(10.0), "#00FF00");
        String full = HealthBarFormatter.format(20, 20, 12, true, null);
        equals(visibleText(full), "[" + "█".repeat(12) + "] 20/20 ♥");
        String half = HealthBarFormatter.format(10, 20, 12, true, "Oveja");
        equals(visibleText(half), "Oveja [" + "█".repeat(6) + "░".repeat(6) + "] 10/20 ♥");
        String zero = HealthBarFormatter.format(0, 20, 12, true, null);
        equals(visibleText(zero), "[" + "░".repeat(12) + "] 0/20 ♥");
        String low = HealthBarFormatter.format(1, 20, 12, true, null);
        equals(visibleText(low), "[█" + "░".repeat(11) + "] 1/20 ♥");
        System.out.println("PASS: 11 pruebas del gradiente RGB, limites, cantidades y barras.");
    }
}
