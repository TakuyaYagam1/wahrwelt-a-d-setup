package ru.serega6531.packmate.service.optimization.tls.records;

import org.pcap4j.util.ByteArrays;
import ru.serega6531.packmate.service.optimization.tls.numbers.AlertDescription;
import ru.serega6531.packmate.service.optimization.tls.numbers.AlertLevel;

import static org.pcap4j.util.ByteArrays.BYTE_SIZE_IN_BYTES;

public class AlertRecord implements TlsRecord {

    private static final int LEVEL_OFFSET = 0;
    private static final int DESCRIPTION_OFFSET = LEVEL_OFFSET + BYTE_SIZE_IN_BYTES;

    private int length;
    private AlertLevel level;
    private AlertDescription description;

    public static AlertRecord newInstance(byte[] rawData, int offset, int length) {
        ByteArrays.validateBounds(rawData, offset, length);
        return new AlertRecord(rawData, offset, length);
    }

    public AlertRecord(byte[] rawData, int offset, int length) {
        this.length = length;
        this.level = AlertLevel.getInstance(ByteArrays.getByte(rawData, LEVEL_OFFSET + offset));

        if (level != AlertLevel.ENCRYPTED_ALERT) {
            this.description = AlertDescription.getInstance(ByteArrays.getByte(rawData, DESCRIPTION_OFFSET + offset));
        }
    }

    @Override
    public String toString() {
        if (level != AlertLevel.ENCRYPTED_ALERT) {
            return "  Alert [level: " + level.name() + ", description: " + description.name() + "]";
        } else {
            return "  Encrypted Alert [" + length + " bytes]";
        }
    }
}
