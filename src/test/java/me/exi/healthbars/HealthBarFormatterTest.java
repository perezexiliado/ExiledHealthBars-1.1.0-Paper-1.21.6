package me.exi.healthbars;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class HealthBarFormatterTest {
    @ParameterizedTest
    @CsvSource({
            "1.0, #00FF00", "0.75, #80FF00", "0.5, #FFFF00", "0.25, #FF8000",
            "0.0, #FF0000", "-1.0, #FF0000", "10.0, #00FF00"
    })
    void interpolatesRgbGradientAndClampsToRange(double fraction, String expected) {
        assertEquals(expected, HealthBarFormatter.hexAt(fraction));
    }

    @ParameterizedTest
    @CsvSource({
            "20, , [████████████] 20/20 ♥",
            "10, Oveja, Oveja [██████░░░░░░] 10/20 ♥",
            "0, , [░░░░░░░░░░░░] 0/20 ♥",
            "1, , [█░░░░░░░░░░░] 1/20 ♥"
    })
    void formatsHealthAmountsAndFilledBlocks(double health, String label, String expected) {
        String formatted = HealthBarFormatter.format(health, 20, 12, true, label);
        assertEquals(expected, formatted.replaceAll("(?i)§[0-9A-FK-ORX]", ""));
    }

    @ParameterizedTest
    @CsvSource({
            "0.05, 0.1", "1.05, 1.1", "2.05, 2.1", "3.05, 3.1",
            "4.05, 4.1", "10.05, 10.1", "19.95, 20", "19.96, 20"
    })
    void roundsFractionalHealthConsistentlyAndRemovesUnnecessaryDecimalZero(double health, String expectedNumber) {
        String formatted = HealthBarFormatter.format(health, 20, 12, true, null)
                .replaceAll("(?i)§[0-9A-FK-ORX]", "");
        assertEquals(expectedNumber + "/20 ♥", formatted.substring(formatted.indexOf("] ") + 2));
    }
}
