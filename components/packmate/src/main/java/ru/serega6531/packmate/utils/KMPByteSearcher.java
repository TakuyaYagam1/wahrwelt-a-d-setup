package ru.serega6531.packmate.utils;

import lombok.SneakyThrows;

import java.io.InputStream;

/**
 * Основан на алгоритме из <a href="https://github.com/twitter/elephant-bird/blob/master/core/src/main/java/com/twitter/elephantbird/util/StreamSearcher.java">StreamSearcher</a>.
 */
public class KMPByteSearcher {

    private byte[] pattern;
    private int[] borders;
    private int lastEnd = 0;

    public KMPByteSearcher(byte[] pattern) {
        setPattern(pattern);
    }

    public void setPattern(byte[] pattern) {
        this.pattern = pattern;
        this.borders = new int[this.pattern.length + 1];
        preProcess();
    }

    @SneakyThrows
    public int search(InputStream stream) {
        int bytesRead = 0;

        int b;
        int j = 0;

        while ((b = stream.read()) != -1) {
            bytesRead++;

            while (j >= 0 && (byte)b != pattern[j]) {
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
