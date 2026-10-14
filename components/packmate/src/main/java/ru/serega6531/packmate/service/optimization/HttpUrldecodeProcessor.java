package ru.serega6531.packmate.service.optimization;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import ru.serega6531.packmate.model.Packet;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public class HttpUrldecodeProcessor {

    /**
     * Декодирует URL-кодирование HTTP-пакетов до смены направления или конца потока.
     */
    @SneakyThrows
    public void urldecodeRequests(List<Packet> packets) {
        boolean httpStarted = false;

        for (Packet packet : packets) {
            if (packet.isIncoming()) {
                String content = packet.getContentString();
                if (content.contains("HTTP/")) {
                    httpStarted = true;
                }

                if (httpStarted) {
                    try {
                        content = URLDecoder.decode(content, StandardCharsets.UTF_8);
                        packet.setContent(content.getBytes());
                    } catch (IllegalArgumentException e) {
                        log.warn("Не удалось декодировать URL-кодированные данные HTTP", e);
                    }
                }
            } else {
                httpStarted = false;
            }
        }
    }

}
