package ru.serega6531.packmate.service.optimization.tls.extensions.keyshare;

import ru.serega6531.packmate.service.optimization.tls.numbers.ExtensionType;

public class ServerKeyShareExtension extends KeyShareExtension {

    private static final int KEY_SHARE_ENTRY_OFFSET = 0;

    public ServerKeyShareExtension(ExtensionType type, byte[] rawData, int offset, short extensionLength) {
        super(type, extensionLength);
        readEntry(rawData, KEY_SHARE_ENTRY_OFFSET + offset);
    }

}
