package ru.serega6531.packmate.service;

import ru.serega6531.packmate.exception.RequestValidationException;

import java.util.regex.Pattern;

public final class RequestValidation {

    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_PATTERN_LENGTH = 4096;
    private static final int MAX_LOOKBACK_MINUTES = 10_080;
    private static final Pattern COLOR = Pattern.compile(
            "(?i)^(#[0-9a-f]{3}(?:[0-9a-f]{3})?|red|green|blue|orange|purple|teal|yellow|white|black|gray|grey)$"
    );

    private RequestValidation() {}

    public static void requirePort(int port) {
        if (port < 1 || port > 65_535) {
            throw new RequestValidationException("Порт должен быть в диапазоне от 1 до 65535.");
        }
    }

    public static void requireIdentifier(long identifier, String label) {
        if (identifier < 1) {
            throw new RequestValidationException(label + " должен быть положительным числом.");
        }
    }

    public static void requireName(String value, String label) {
        if (value == null || value.isBlank() || value.length() > MAX_NAME_LENGTH) {
            throw new RequestValidationException(label + " должен содержать от 1 до 100 символов.");
        }
    }

    public static void requirePatternValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_PATTERN_LENGTH) {
            throw new RequestValidationException("Значение паттерна должно содержать от 1 до 4096 символов.");
        }
    }

    public static void requirePatternColor(String value) {
        if (value == null || !COLOR.matcher(value).matches()) {
            throw new RequestValidationException("Цвет паттерна задан в неподдерживаемом формате.");
        }
    }

    public static void requirePage(Long startingFrom, int pageSize) {
        if (startingFrom != null && startingFrom < 1) {
            throw new RequestValidationException("Идентификатор начала страницы должен быть положительным.");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new RequestValidationException("Размер страницы должен быть в диапазоне от 1 до 500.");
        }
    }

    public static void requireLookback(int minutes) {
        if (minutes < 1 || minutes > MAX_LOOKBACK_MINUTES) {
            throw new RequestValidationException("Период поиска должен быть от 1 до 10080 минут.");
        }
    }
}
