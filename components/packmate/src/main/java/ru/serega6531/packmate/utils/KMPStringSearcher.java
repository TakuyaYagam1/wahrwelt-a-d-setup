package ru.serega6531.packmate.utils;

import lombok.SneakyThrows;

import java.io.StringReader;

/**
 * Основан на алгоритме из <a href="https://github.com/twitter/elephant-bird/blob/master/core/src/main/java/com/twitter/elephantbird/util/StreamSearcher.java">StreamSearcher</a>.
 */
public class KMPStringSearcher {

    private char[] pattern;
    private int[] borders;
    private int lastEnd = 0;

    public KMPStringSearcher(char[] pattern) {
        setPattern(pattern);
    }

    public void setPattern(char[] pattern) {
        this.pattern = pattern;
        this.borders = new int[this.pattern.length + 1];
        preProcess();
    }

    @SneakyThrows
    public int search(StringReader reader) {
        int bytesRead = 0;

        int b;
        int j = 0;

        while ((b = reader.read()) != -1) {
            bytesRead++;

            while (j >= 0 && (char)b != pattern[j]) {
                j = borders[j];
            }
            ++j;

            if (j == pattern.length) {
                lastEnd += bytesRead;
                return lastEnd;
            }
        }

        return -1;
    }

    private void preProcess() {
        int i = 0;
        int j = -1;
        borders[i] = j;
        while (i < pattern.length) {
            while (j >= 0 && pattern[i] != pattern[j]) {
                j = borders[j];
            }
            borders[++i] = ++j;
        }
    }

    public void reset() {
        this.lastEnd = 0;
    }

}
