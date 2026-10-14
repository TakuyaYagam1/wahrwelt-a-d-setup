package ru.serega6531.packmate;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;
import ru.serega6531.packmate.model.Packet;
import ru.serega6531.packmate.service.optimization.HttpProcessor;
import ru.serega6531.packmate.service.optimization.HttpUrldecodeProcessor;
import ru.serega6531.packmate.service.optimization.PacketsMerger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamOptimizerTest {

    @Test
    void testUnpackGzip() {
        String encoded = "H4sIAAAAAAAA/0tMTExKSgIA2KWG6gYAAAA=";
        final byte[] gzipped = Base64.getDecoder().decode(encoded);
        final byte[] content = ArrayUtils.addAll("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 26\r\n\r\n".getBytes(), gzipped);

        Packet p = createPacket(content, false);
        List<Packet> list = new ArrayList<>();
        list.add(p);

        new HttpProcessor().process(list);
        final String processed = list.get(0).getContentString();
        assertTrue(processed.contains("aaabbb"));
    }

    @Test
    void testUrldecodeRequests() {
        Packet p = createPacket("GET /?q=%D0%B0+%D0%B1 HTTP/1.1\r\nHost: localhost:8080\r\n\r\n".getBytes(), true);
        List<Packet> list = new ArrayList<>();
        list.add(p);

        new HttpUrldecodeProcessor().urldecodeRequests(list);
        final String processed = list.get(0).getContentString();
        assertTrue(processed.contains("а б"));
    }

    @Test
    void testMergeAdjacentPackets() {
        Packet p1 = createPacket(1, false);
        Packet p2 = createPacket(2, true);
        Packet p3 = createPacket(3, true);
        Packet p4 = createPacket(4, false);
        Packet p5 = createPacket(5, true);
        Packet p6 = createPacket(6, true);

        List<Packet> list = new ArrayList<>();
        list.add(p1);
        list.add(p2);
        list.add(p3);
        list.add(p4);
        list.add(p5);
        list.add(p6);

        new PacketsMerger().mergeAdjacentPackets(list);

        assertEquals(4, list.size());
        assertEquals(2, list.get(1).getContent().length);
        assertEquals(1, list.get(2).getContent().length);
        assertEquals(2, list.get(3).getContent().length);
    }

    @Test
    void testChunkedTransferEncoding() {
        String content = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                         "6\r\nChunk1\r\n6\r\nChunk2\r\n0\r\n\r\n";

        List<Packet> packets = new ArrayList<>(List.of(createPacket(content.getBytes(), false)));
        new HttpProcessor().process(packets);

        assertEquals(1, packets.size());
        assertTrue(packets.get(0).getContentString().contains("Chunk1Chunk2"));
    }

    private Packet createPacket(int content, boolean incoming) {
        return createPacket(new byte[] {(byte) content}, incoming);
    }

    private Packet createPacket(byte[] content, boolean incoming) {
        Packet p = new Packet();
        p.setContent(content);
        p.setIncoming(incoming);
        return p;
    }

}