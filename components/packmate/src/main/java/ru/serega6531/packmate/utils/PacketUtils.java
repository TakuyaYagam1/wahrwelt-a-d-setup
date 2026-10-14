package ru.serega6531.packmate.utils;

import lombok.experimental.UtilityClass;
import ru.serega6531.packmate.model.Packet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@UtilityClass
public class PacketUtils {

    public byte[] mergePackets(List<Packet> cut) {
        int size = cut.stream()
                .map(Packet::getContent)
                .mapToInt(c -> c.length)
                .sum();

        ByteArrayOutputStream os = new ByteArrayOutputStream(size);

        cut.stream()
                .map(Packet::getContent)
                .forEach(os::writeBytes);

        return os.toByteArray();
    }

    public List<List<Packet>> sliceToSides(List<Packet> packets) {
        List<List<Packet>> result = new ArrayList<>();
        List<Packet> side = new ArrayList<>();
        boolean incoming = true;

        for (Packet packet : packets) {
            if(packet.isIncoming() != incoming) {
                incoming = packet.isIncoming();

                if(!side.isEmpty()) {
                    result.add(side);
                    side = new ArrayList<>();
                }
            }

            side.add(packet);
        }

        if(!side.isEmpty()) {
            result.add(side);
        }

        return result;
    }

}
