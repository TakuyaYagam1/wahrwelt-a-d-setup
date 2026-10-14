package ru.serega6531.packmate;

import org.springframework.security.crypto.codec.Hex;
import ru.serega6531.packmate.model.Packet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class PackmateDumpFileLoader {

    private final File file;

    public PackmateDumpFileLoader(String path) {
        this.file = new File(getClass().getClassLoader().getResource(path).getFile());
    }

    public List<Packet> getPackets() throws IOException {
        boolean in = true;
        List<Packet> packets = new ArrayList<>();

        for (String line : Files.readAllLines(file.toPath())) {
            if (line.startsWith("#")) {
                continue;
            }

            switch (line) {
                case "in" -> in = true;
                case "out" -> in = false;
                default -> packets.add(Packet.builder()
                        .content(Hex.decode(line))
                        .incoming(in)
                        .build());
            }
        }

        return packets;
    }

}
