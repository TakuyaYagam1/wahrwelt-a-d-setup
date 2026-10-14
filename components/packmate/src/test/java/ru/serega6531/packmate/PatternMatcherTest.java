package ru.serega6531.packmate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ru.serega6531.packmate.model.FoundPattern;
import ru.serega6531.packmate.model.Pattern;
import ru.serega6531.packmate.model.enums.PatternSearchType;
import ru.serega6531.packmate.service.PatternMatcher;

import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class PatternMatcherTest {

    @Test
    public void testRegex() {
        String content = "ahkfkyafceffek";

        Set<FoundPattern> correctMatches = Set.of(
                FoundPattern.builder()
                        .startPosition(6)
                        .endPosition(8)
                        .build(),
                FoundPattern.builder()
                        .startPosition(9)
                        .endPosition(11)
                        .build());

        final Pattern pattern = new Pattern();
        pattern.setId(1);
        pattern.setValue("[a-f]{3}");
        pattern.setSearchType(PatternSearchType.REGEX);

        final PatternMatcher matcher = new PatternMatcher(content.getBytes(), List.of(pattern));
        final Set<FoundPattern> matches = matcher.findMatches();

        assertMatchesAreCorrect(correctMatches, matches);
    }

    @Test
    public void testSubstring() {
        String content = "abaabbaaabaabbbbbbbaaabaaa";
        Set<FoundPattern> correctMatches = Set.of(
                FoundPattern.builder()
                        .startPosition(12)
                        .endPosition(14)
                        .build(),
                FoundPattern.builder()
                        .startPosition(15)
                        .endPosition(17)
                        .build());

        final Pattern pattern = new Pattern();
        pattern.setId(1);
        pattern.setValue("bbb");
        pattern.setSearchType(PatternSearchType.SUBSTRING);

        final PatternMatcher matcher = new PatternMatcher(content.getBytes(), List.of(pattern));
        final Set<FoundPattern> matches = matcher.findMatches();

        assertMatchesAreCorrect(correctMatches, matches);
    }

    @Test
    public void testSubbytes() {
        byte[] content = new byte[]{0x11, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA, 0x22};
        Set<FoundPattern> correctMatches = Set.of(
                FoundPattern.builder()
                        .startPosition(1)
                        .endPosition(2)
                        .build(),
                FoundPattern.builder()
                        .startPosition(3)
                        .endPosition(4)
                        .build());

        final Pattern pattern = new Pattern();
        pattern.setId(1);
        pattern.setValue("AAaa");
        pattern.setSearchType(PatternSearchType.SUBBYTES);

        final PatternMatcher matcher = new PatternMatcher(content, List.of(pattern));
        final Set<FoundPattern> matches = matcher.findMatches();

        assertMatchesAreCorrect(correctMatches, matches);
    }

    @Test
    public void ignoresZeroLengthRegexMatches() {
        final Pattern pattern = new Pattern();
        pattern.setId(3);
        pattern.setValue("^");
        pattern.setSearchType(PatternSearchType.REGEX);

        Set<FoundPattern> matches = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> new PatternMatcher("traffic".getBytes(), List.of(pattern)).findMatches());

        Assertions.assertTrue(matches.isEmpty());
    }

    @Test
    public void usesRe2CompatibleRegexSyntax() {
        final Pattern pattern = new Pattern();
        pattern.setId(4);
        pattern.setValue("(?=traffic)");
        pattern.setSearchType(PatternSearchType.REGEX);

        Assertions.assertThrows(RuntimeException.class,
                () -> new PatternMatcher("traffic".getBytes(), List.of(pattern)).findMatches());
    }

    @Test
    public void substringSearchersAreIsolatedAcrossThreads() throws Exception {
        final Pattern pattern = new Pattern();
        pattern.setId(5);
        pattern.setValue("needle");
        pattern.setSearchType(PatternSearchType.SUBSTRING);

        try (var executor = Executors.newFixedThreadPool(4)) {
            var checks = IntStream.range(0, 32)
                    .mapToObj(ignored -> executor.submit(() -> new PatternMatcher(
                            "prefix-needle-suffix".getBytes(), List.of(pattern)).findMatches().size()))
                    .toList();
            for (var check : checks) {
                Assertions.assertEquals(1, check.get(2, TimeUnit.SECONDS));
            }
        }
    }

    private void assertMatchesAreCorrect(Set<FoundPattern> correctMatches, Set<FoundPattern> foundMatches) {
        Assertions.assertEquals(correctMatches.size(), foundMatches.size());

        Assertions.assertTrue(correctMatches.stream().allMatch(correct ->
                foundMatches.stream().anyMatch(found -> matchesEqual(correct, found))
        ));
    }

    private boolean matchesEqual(FoundPattern one, FoundPattern two) {
        return one.getStartPosition() == two.getStartPosition() &&
                one.getEndPosition() == two.getEndPosition();
    }

}
