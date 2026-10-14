package ru.serega6531.packmate.service.optimization;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.extensions.permessage_deflate.PerMessageDeflateExtension;
import org.java_websocket.framing.DataFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.java_websocket.handshake.HandshakeImpl1Server;
import ru.serega6531.packmate.model.Packet;
import ru.serega6531.packmate.utils.BytesUtils;
import ru.serega6531.packmate.utils.PacketUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class WebSocketsParser {

    private static final java.util.regex.Pattern WEBSOCKET_KEY_PATTERN =
            Pattern.compile("Sec-WebSocket-Key: (.+)\\r\\n");
    private static final java.util.regex.Pattern WEBSOCKET_EXTENSIONS_PATTERN =
            Pattern.compile("Sec-WebSocket-Extensions?: (.+)\\r\\n");
    private static final java.util.regex.Pattern WEBSOCKET_VERSION_PATTERN =
            Pattern.compile("Sec-WebSocket-Version: (\\d+)\\r\\n");
    private static final java.util.regex.Pattern WEBSOCKET_ACCEPT_PATTERN =
            Pattern.compile("Sec-WebSocket-Accept: (.+)\\r\\n");

    private static final String WEBSOCKET_UPGRADE_HEADER = "upgrade: websocket\r\n";
    private static final String WEBSOCKET_CONNECTION_HEADER = "connection: upgrade\r\n";

    private final List<Packet> packets;

    @Getter
    private boolean parsed = false;
    private List<Packet> parsedPackets;

    public WebSocketsParser(List<Packet> packets) {
        this.packets = packets;
        detectWebSockets();
    }

    private void detectWebSockets() {
        final List<Packet> clientHandshakePackets = packets.stream()
                .takeWhile(Packet::isIncoming)
                .collect(Collectors.toList());

        final String clientHandshake = getHandshake(clientHandshakePackets);
        if (clientHandshake == null) {
            return;
        }

        int httpEnd = -1;
        for (int i = clientHandshakePackets.size(); i < packets.size(); i++) {
            Packet packet = packets.get(i);
            byte[] content = packet.getContent();
            if (BytesUtils.startsWith(content, "HTTP/1.1 101 Switching Protocols".getBytes())) {
                int endPos = BytesUtils.indexOf(content, "\r\n\r\n".getBytes()) + "\r\n\r\n".length();
                if (endPos != content.length) {
                    byte[] handshakePart = ArrayUtils.subarray(content, 0, endPos);
                    byte[] payloadPart = ArrayUtils.subarray(content, endPos, content.length);

                    Packet handshakePacket = mimicPacket(packet, handshakePart, false);
                    Packet payloadPacket = mimicPacket(packet, payloadPart, true);

                    packets.add(i, handshakePacket);
                    packets.set(i + 1, payloadPacket);
                }
                httpEnd = i + 1;
                break;
            }
        }

        if (httpEnd == -1) {
            return;
        }

        final List<Packet> serverHandshakePackets = packets.subList(clientHandshakePackets.size(), httpEnd);
        final String serverHandshake = getHandshake(serverHandshakePackets);
        if (serverHandshake == null) {
            return;
        }

        HandshakeImpl1Server serverHandshakeImpl = fillServerHandshake(serverHandshake);
        HandshakeImpl1Client clientHandshakeImpl = fillClientHandshake(clientHandshake);

        if (serverHandshakeImpl == null || clientHandshakeImpl == null) {
            return;
        }

        Draft_6455 draft = new Draft_6455(new PerMessageDeflateExtension());

        try {
            draft.acceptHandshakeAsServer(clientHandshakeImpl);
            draft.acceptHandshakeAsClient(clientHandshakeImpl, serverHandshakeImpl);
        } catch (InvalidHandshakeException e) {
            log.warn("Не удалось разобрать рукопожатие WebSocket", e);
            return;
        }

        final List<Packet> wsPackets = packets.subList(
                httpEnd,
                packets.size());

        if (wsPackets.isEmpty()) {
            return;
        }

        final List<Packet> handshakes = packets.subList(0, httpEnd);

        parse(wsPackets, handshakes, draft);
    }

    private Packet mimicPacket(Packet packet, byte[] content, boolean ws) {
        return packet.toBuilder()
                .content(content)
                .webSocketParsed(ws)
                .build();
    }

    private void parse(final List<Packet> wsPackets, final List<Packet> handshakes, Draft_6455 draft) {
        List<List<Packet>> sides = PacketUtils.sliceToSides(wsPackets);
        parsedPackets = new ArrayList<>(handshakes);

        for (List<Packet> side : sides) {
            final Packet lastPacket = side.get(0);

            final byte[] wsContent = PacketUtils.mergePackets(side);

            final ByteBuffer buffer = ByteBuffer.wrap(wsContent);
            List<Framedata> frames;

            try {
                frames = draft.translateFrame(buffer);
            } catch (InvalidDataException e) {
                log.warn("Не удалось разобрать данные WebSocket", e);
                return;
            }

            for (Framedata frame : frames) {
                if (frame instanceof DataFrame) {
                    parsedPackets.add(
                            lastPacket.toBuilder()
                                    .content(frame.getPayloadData().array())
                                    .webSocketParsed(true)
                                    .build()
                    );
                }
            }
        }

        parsed = true;
    }

    public List<Packet> getParsedPackets() {
        if (!parsed) {
            throw new IllegalStateException("Данные WebSocket не разобраны.");
        }

        return parsedPackets;
    }

    private String getHandshake(final List<Packet> packets) {
        final String handshake = new String(PacketUtils.mergePackets(packets));

        if (!handshake.toLowerCase().contains(WEBSOCKET_CONNECTION_HEADER)
                || !handshake.toLowerCase().contains(WEBSOCKET_UPGRADE_HEADER)) {
            return null;
        }

        return handshake;
    }

    private HandshakeImpl1Client fillClientHandshake(String clientHandshake) {
        Matcher matcher = WEBSOCKET_VERSION_PATTERN.matcher(clientHandshake);
        if (!matcher.find()) {
            return null;
        }
        String version = matcher.group(1);

        matcher = WEBSOCKET_KEY_PATTERN.matcher(clientHandshake);
        if (!matcher.find()) {
            return null;
        }
        String key = matcher.group(1);

        matcher = WEBSOCKET_EXTENSIONS_PATTERN.matcher(clientHandshake);
        String extensions = null;
        if (matcher.find()) {
            extensions = matcher.group(1);
        }

        HandshakeImpl1Client clientHandshakeImpl = new HandshakeImpl1Client();

        clientHandshakeImpl.put("Upgrade", "websocket");
        clientHandshakeImpl.put("Connection", "Upgrade");
        clientHandshakeImpl.put("Sec-WebSocket-Version", version);
        clientHandshakeImpl.put("Sec-WebSocket-Key", key);

        if (extensions != null) {
            clientHandshakeImpl.put("Sec-WebSocket-Extensions", extensions);
        }

        return clientHandshakeImpl;
    }

    private HandshakeImpl1Server fillServerHandshake(String serverHandshake) {
        Matcher matcher = WEBSOCKET_ACCEPT_PATTERN.matcher(serverHandshake);
        if (!matcher.find()) {
            return null;
        }
        String accept = matcher.group(1);

        matcher = WEBSOCKET_EXTENSIONS_PATTERN.matcher(serverHandshake);
        String extensions = null;
        if (matcher.find()) {
            extensions = matcher.group(1);
        }

        HandshakeImpl1Server serverHandshakeImpl = new HandshakeImpl1Server();

        serverHandshakeImpl.put("Upgrade", "websocket");
        serverHandshakeImpl.put("Connection", "Upgrade");
        serverHandshakeImpl.put("Sec-WebSocket-Accept", accept);
        serverHandshakeImpl.put("Sec-WebSocket-Extensions", extensions);

        return serverHandshakeImpl;
    }

}
