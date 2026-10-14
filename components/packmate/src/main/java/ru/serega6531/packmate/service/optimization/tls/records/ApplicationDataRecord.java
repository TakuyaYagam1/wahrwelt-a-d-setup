package ru.serega6531.packmate.service.optimization.tls.records;

import org.pcap4j.util.ByteArrays;

public class ApplicationDataRecord implements TlsRecord {

    /**
     * 0x0          - Зашифрованные данные приложения
     * 0x0 + length - Конец записи
     */

    private byte[] data;

    public static ApplicationDataRecord newInstance(byte[] rawData, int offset, int length) {
        ByteArrays.validateBounds(rawData, offset, length);
        return new ApplicationDataRecord(rawData, offset, length);
    }

    public ApplicationDataRecord(byte[] rawData, int offset, int length) {
        data = new byte[length];
        System.arraycopy(rawData, offset, data, 0, length);
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public String toString() {
        return "  Зашифрованные данные: [" + data.length + " байт]";
    }

}
