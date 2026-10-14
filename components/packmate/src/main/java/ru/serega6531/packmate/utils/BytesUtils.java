package ru.serega6531.packmate.utils;

import lombok.experimental.UtilityClass;
import org.pcap4j.util.ByteArrays;

import java.nio.ByteOrder;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.pcap4j.util.ByteArrays.BYTE_SIZE_IN_BITS;

@UtilityClass
public class BytesUtils {

    public int indexOf(byte[] array, byte[] target) {
        return indexOf(array, target, 0, array.length);
    }

    public int indexOf(byte[] array, byte[] target, int start, int end) {
        if (target.length == 0) {
            return 0;
        }

        outer:
        for (int i = start; i < end - target.length + 1; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public boolean startsWith(byte[] array, byte[] prefix) {
        if (prefix.length > array.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param array  где ищем
     * @param target что ищем
     */
    public boolean endsWith(byte[] array, byte[] target) {
        if (array.length < target.length) {
            return false;
        }

        for (int i = 0; i < target.length; i++) {
            if (array[array.length - target.length + i] != target[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param array массив байтов
     * @param offset начальная позиция
     * @return целочисленное значение
     */
    public int getThreeBytesInt(byte[] array, int offset) {
        return getThreeBytesInt(array, offset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * @param array массив байтов
     * @param offset начальная позиция
     * @param bo порядок байтов
     * @return целочисленное значение
     */
    public int getThreeBytesInt(byte[] array, int offset, ByteOrder bo) {
        ByteArrays.validateBounds(array, offset, 3);

        if (bo == null) {
            throw new NullPointerException("Порядок байтов не задан.");
        }

        if (bo.equals(LITTLE_ENDIAN)) {
            return ((0xFF & array[offset + 2]) << (BYTE_SIZE_IN_BITS * 2))
                    | ((0xFF & array[offset + 1]) << (BYTE_SIZE_IN_BITS * 1))
                    | ((0xFF & array[offset]));
        } else {
            return ((0xFF & array[offset]) << (BYTE_SIZE_IN_BITS * 2))
                    | ((0xFF & array[offset + 1]) << (BYTE_SIZE_IN_BITS * 1))
                    | ((0xFF & array[offset + 2]));
        }
    }

}
