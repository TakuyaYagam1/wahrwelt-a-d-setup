package ru.serega6531.packmate.service.optimization.tls.numbers;

import org.pcap4j.packet.namednumber.NamedNumber;

import java.util.HashMap;
import java.util.Map;

public class HeartbeatMessageType extends NamedNumber<Byte, HeartbeatMessageType> {

    private static final Map<Byte, HeartbeatMessageType> registry = new HashMap<>();

    public static final HeartbeatMessageType HEARTBEAT_REQUEST = new HeartbeatMessageType((byte) 1, "heartbeat_request");
    public static final HeartbeatMessageType HEARTBEAT_RESPONSE = new HeartbeatMessageType((byte) 2, "heartbeat_response");

    public HeartbeatMessageType(Byte value, String name) {
        super(value, name);
        registry.put(value, this);
    }

    public static HeartbeatMessageType getInstance(Byte value) {
        if (registry.containsKey(value)) {
            return registry.get(value);
        } else {
            throw new IllegalArgumentException("Неизвестный тип сообщения Heartbeat: " + value);
        }
    }

    @Override
    public int compareTo(HeartbeatMessageType o) {
        return value().compareTo(o.value());
    }

}
