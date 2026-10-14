package ru.serega6531.packmate.service.optimization.tls.extensions.keyshare;

import org.pcap4j.util.ByteArrays;
import ru.serega6531.packmate.service.optimization.tls.extensions.TlsExtension;
import ru.serega6531.packmate.service.optimization.tls.numbers.ExtensionType;

import java.util.ArrayList;
import java.util.List;

public abstract class KeyShareExtension extends TlsExtension {

    private final List<KeyShareEntry> entries = new ArrayList<>();

    public static KeyShareExtension newInstance(ExtensionType type, byte[] rawData, int offset,
                                                short extensionLength, boolean client) {
        ByteArrays.validateBounds(rawData, offset, extensionLength);

        if(client) {
            return new ClientKeyShareExtension(type, rawData, offset, extensionLength);
        } else {
            return new ServerKeyShareExtension(type, rawData, offset, extensionLength);
        }
    }

    protected KeyShareExtension(ExtensionType type, short extensionLength) {
        super(type, extensionLength);
    }

    protected void readEntries(byte[] rawData, int cursor, int end) {
        while (cursor < end) {
            KeyShareEntry entry = readEntry(rawData, cursor);
            cursor += entry.size();
        }
    }

    protected KeyShareEntry readEntry(byte[] rawData, int cursor) {
        KeyShareEntry entry = new KeyShareEntry(rawData, cursor);
        entries.add(entry);
        return entry;
    }

    @Override
    public String toString() {
        return type.name() + " " + entries.toString();
    }
}
