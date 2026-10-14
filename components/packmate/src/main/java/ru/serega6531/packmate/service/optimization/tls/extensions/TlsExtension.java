package ru.serega6531.packmate.service.optimization.tls.extensions;

import org.pcap4j.util.ByteArrays;
import ru.serega6531.packmate.service.optimization.tls.extensions.keyshare.KeyShareExtension;
import ru.serega6531.packmate.service.optimization.tls.numbers.ExtensionType;

public abstract class TlsExtension {

    /*
    0x0        - Тип
    0x2        - Длина
    0x4        - Содержимое
    0x4+length - Конец
     */

    protected ExtensionType type;
    protected short extensionLength;

    public static TlsExtension newInstance(ExtensionType type, byte[] rawData, int offset,
                                           short extensionLength, boolean client) {
        if (extensionLength > 0) {
            ByteArrays.validateBounds(rawData, offset, extensionLength);
        }

        if (type == ExtensionType.KEY_SHARE) {
            return KeyShareExtension.newInstance(type, rawData, offset, extensionLength, client);
        } else {
            return new UnimplementedTlsExtension(type, rawData, offset, extensionLength);
        }
    }

    public TlsExtension(ExtensionType type, short extensionLength) {
        this.type = type;
        this.extensionLength = extensionLength;
    }

    public ExtensionType getType() {
        return type;
    }

    public short getLength() {
        return extensionLength;
    }

    @Override
    public String toString() {
        return type.name();
    }
}
