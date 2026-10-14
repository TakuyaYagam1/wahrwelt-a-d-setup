package ru.serega6531.packmate.service;

import org.junit.jupiter.api.Test;
import ru.serega6531.packmate.exception.RequestValidationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestValidationTest {

    @Test
    void acceptsOnlyValidPortsAndPageBounds() {
        assertDoesNotThrow(() -> RequestValidation.requirePort(1));
        assertDoesNotThrow(() -> RequestValidation.requirePort(65_535));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requirePort(0));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requirePort(65_536));

        assertDoesNotThrow(() -> RequestValidation.requirePage(null, 1));
        assertDoesNotThrow(() -> RequestValidation.requirePage(1L, 500));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requirePage(null, 501));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requirePage(0L, 10));
    }

    @Test
    void boundsPatternNamesValuesAndLookback() {
        assertDoesNotThrow(() -> RequestValidation.requireName("HTTP", "Название"));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requireName(" ", "Название"));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requireName("x".repeat(101), "Название"));
        assertDoesNotThrow(() -> RequestValidation.requirePatternValue("abc"));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requirePatternValue("x".repeat(4097)));
        assertDoesNotThrow(() -> RequestValidation.requireLookback(10_080));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requireLookback(10_081));
        assertThrows(RequestValidationException.class, () -> RequestValidation.requirePatternColor("url(javascript:alert(1))"));
    }
}
