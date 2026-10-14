package ru.serega6531.packmate.service.optimization;

import lombok.extern.slf4j.Slf4j;
import rawhttp.core.HttpMessage;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpOptions;
import rawhttp.core.body.BodyReader;
import rawhttp.core.errors.InvalidHttpHeader;
import rawhttp.core.errors.InvalidHttpRequest;
import rawhttp.core.errors.InvalidHttpResponse;
import rawhttp.core.errors.InvalidMessageFrame;
import rawhttp.core.errors.UnknownEncodingException;
import ru.serega6531.packmate.model.Packet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
public class HttpProcessor {

    private static final RawHttp rawHttp = new RawHttp(RawHttpOptions.strict());

    public void process(List<Packet> packets) {
        packets.stream()
                .filter(p -> !p.isWebSocketParsed())
                .forEach(this::processPacket);
    }

    private void processPacket(Packet packet) {
        try {
            ByteArrayInputStream contentStream = new ByteArrayInputStream(packet.getContent());
            HttpMessage message;

            if (packet.isIncoming()) {
                message = rawHttp.parseRequest(contentStream).eagerly();
            } else {
                message = rawHttp.parseResponse(contentStream).eagerly();
            }

            packet.setContent(getDecodedMessage(message));
            packet.setHasHttpBody(message.getBody().isPresent());
        } catch (IOException | InvalidHttpRequest | InvalidHttpResponse | InvalidHttpHeader | InvalidMessageFrame |
                 UnknownEncodingException e) {
            log.warn("Не удалось разобрать HTTP-пакет", e);
        }
    }

    private byte[] getDecodedMessage(HttpMessage message) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(256);

        message.getStartLine().writeTo(os);
        message.getHeaders().writeTo(os);

        Optional<? extends BodyReader> body = message.getBody();
        if (body.isPresent()) {
            body.get().writeDecodedTo(os, 256);
        }

        return os.toByteArray();
    }

}
