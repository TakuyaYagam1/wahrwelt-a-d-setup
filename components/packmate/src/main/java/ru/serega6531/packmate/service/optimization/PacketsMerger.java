package ru.serega6531.packmate.service.optimization;

import ru.serega6531.packmate.model.Packet;
import ru.serega6531.packmate.utils.PacketUtils;

import java.util.List;

public class PacketsMerger {

    /**
     * Сжать соседние пакеты в одном направлении в один. Не склеивает WS и не-WS пакеты.
     */
    public void mergeAdjacentPackets(List<Packet> packets) {
        int start = 0;
        int packetsInRow = 0;
        Packet previous = null;

        for (int i = 0; i < packets.size(); i++) {
            Packet packet = packets.get(i);
            if (previous == null || !shouldBeInSameBatch(packet, previous)) {
                if (packetsInRow > 1) {
                    compress(packets, start, i);

                    i = start + 1;  // Переходим к элементу сразу после объединенного блока.
                }
                start = i;
                packetsInRow = 1;
            } else {
                packetsInRow++;
            }

            previous = packet;
        }

        if (packetsInRow > 1) {
            compress(packets, start, packets.size());
        }
    }

    /**
     * Объединяет элементы от start до end в один пакет.
     */
    private void compress(List<Packet> packets, int start, int end) {
        final List<Packet> cut = packets.subList(start, end);
        final long timestamp = cut.get(0).getTimestamp();
        final boolean httpProcessed = cut.stream().anyMatch(Packet::isHttpProcessed);
        final boolean webSocketParsed = cut.stream().anyMatch(Packet::isWebSocketParsed);
        final boolean tlsDecrypted = cut.get(0).isTlsDecrypted();
        final boolean incoming = cut.get(0).isIncoming();
        final byte[] content = PacketUtils.mergePackets(cut);

        packets.removeAll(cut);
        packets.add(start, Packet.builder()
                .incoming(incoming)
                .timestamp(timestamp)
                .httpProcessed(httpProcessed)
                .webSocketParsed(webSocketParsed)
                .tlsDecrypted(tlsDecrypted)
                .content(content)
                .build());
    }

    private boolean shouldBeInSameBatch(Packet p1, Packet p2) {
        return p1.isIncoming() == p2.isIncoming() &&
                p1.isWebSocketParsed() == p2.isWebSocketParsed();
    }

}
