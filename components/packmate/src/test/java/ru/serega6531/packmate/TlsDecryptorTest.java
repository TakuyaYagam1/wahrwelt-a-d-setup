package ru.serega6531.packmate;

import org.junit.jupiter.api.Test;
import ru.serega6531.packmate.model.Packet;
import ru.serega6531.packmate.service.optimization.RsaKeysHolder;
import ru.serega6531.packmate.service.optimization.TlsDecryptor;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TlsDecryptorTest {

    @Test
    public void testDecryptTls() throws IOException {
        List<Packet> packets = new PackmateDumpFileLoader("tls.pkmt").getPackets();

        RsaKeysHolder keysHolder = new RsaKeysHolder();
        File keyFile = new File(getClass().getClassLoader().getResource("tls.key").getFile());
        keysHolder.addKey(keyFile);

        TlsDecryptor decryptor = new TlsDecryptor(packets, keysHolder);
        decryptor.decryptTls();

        assertTrue(decryptor.isParsed(), "TLS not parsed");
        List<Packet> parsed = decryptor.getParsedPackets();
        assertNotNull(parsed, "Parsed packets list is null");

        parsed.forEach(p -> System.out.println(p.getContentString()));

        assertEquals(4, parsed.size(), "Wrong packets list size");

        assertTrue(new String(parsed.get(0).getContent()).startsWith("GET /"), "Wrong content at the start");
        assertTrue(new String(parsed.get(3).getContent()).endsWith("Not Found\n"), "Wrong content at the end");
    }

}
