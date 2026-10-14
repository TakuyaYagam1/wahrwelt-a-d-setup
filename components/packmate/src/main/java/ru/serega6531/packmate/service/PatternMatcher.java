package ru.serega6531.packmate.service;

import lombok.SneakyThrows;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.security.crypto.codec.Hex;
import ru.serega6531.packmate.model.FoundPattern;
import ru.serega6531.packmate.model.Pattern;
import ru.serega6531.packmate.utils.KMPByteSearcher;
import ru.serega6531.packmate.utils.KMPStringSearcher;
import com.google.re2j.Matcher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class PatternMatcher {

    private static final Cache<String, com.google.re2j.Pattern> compiledRegexes = CacheBuilder.newBuilder()
            .maximumSize(256)
            .build();

    private final byte[] contentBytes;
    private final String content;
    private final List<Pattern> patterns;

    private final Set<FoundPattern> result = new HashSet<>();

    public PatternMatcher(byte[] contentBytes, List<Pattern> patterns) {
        this.contentBytes = contentBytes;
        this.content = new String(contentBytes);
        this.patterns = patterns;
    }

    public Set<FoundPattern> findMatches() {
        patterns.forEach(this::match);
        return result;
    }

    private void match(Pattern pattern) {
        switch (pattern.getSearchType()) {
            case REGEX -> matchRegex(pattern);
            case SUBSTRING -> matchSubstring(pattern);
            case SUBBYTES -> matchSubbytes(pattern);
        }
    }

    private void matchRegex(Pattern pattern) {
        final var regex = compileRegex(pattern.getValue());
        final Matcher matcher = regex.matcher(content);

        while (matcher.find()) {
            if (matcher.start() == matcher.end()) {
                continue;
            }
            addIfPossible(FoundPattern.builder()
                    .patternId(pattern.getId())
                    .startPosition(matcher.start())
                    .endPosition(matcher.end() - 1)
                    .build());
        }
    }

    private void matchSubstring(Pattern pattern) {
        final String value = pattern.getValue();
        if (value.isEmpty()) {
            return;
        }
        KMPStringSearcher searcher = new KMPStringSearcher(value.toCharArray());
        StringReader reader = new StringReader(content);

        while (true) {
            int end = searcher.search(reader) - 1;

            if (end < 0) {
                searcher.reset();
                return;
            }

            int start = end - value.length() + 1;
            addIfPossible(FoundPattern.builder()
                    .patternId(pattern.getId())
                    .startPosition(start)
                    .endPosition(end)
                    .build());
        }
    }

    @SneakyThrows
    private void matchSubbytes(Pattern pattern) {
        final byte[] value = Hex.decode(pattern.getValue());
        if (value.length == 0) {
            return;
        }
        KMPByteSearcher searcher = new KMPByteSearcher(value);
        InputStream is = new ByteArrayInputStream(contentBytes);

        while (true) {
            int end = searcher.search(is) - 1;

            if (end < 0) {
                searcher.reset();
                return;
            }

            int start = end - value.length + 1;
            addIfPossible(FoundPattern.builder()
                    .patternId(pattern.getId())
                    .startPosition(start)
                    .endPosition(end)
                    .build());
        }
    }

    private void addIfPossible(FoundPattern found) {
        if (result.stream().noneMatch(match ->
                between(match.getStartPosition(), match.getEndPosition(), found.getStartPosition()) ||
                        between(match.getStartPosition(), match.getEndPosition(), found.getEndPosition()))) {
            result.add(found);
        }
    }

    private boolean between(int a, int b, int x) {
        return a <= x && x <= b;
    }

    static void compilePattern(Pattern pattern) {
        switch (pattern.getSearchType()) {
            case REGEX -> compileRegex(pattern.getValue());
            case SUBSTRING -> {
                if (pattern.getValue().isEmpty()) {
                    throw new IllegalArgumentException("Текстовый шаблон не может быть пустым.");
                }
            }
            case SUBBYTES -> {
                if (Hex.decode(pattern.getValue()).length == 0) {
                    throw new IllegalArgumentException("Байтовый шаблон не может быть пустым.");
                }
            }
        }
    }

    private static com.google.re2j.Pattern compileRegex(String expression) {
        try {
            return compiledRegexes.get(expression, () -> com.google.re2j.Pattern.compile(expression));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalArgumentException("Некорректное регулярное выражение.", cause);
        }
    }

}
