package ru.serega6531.packmate.model.pojo;

import ru.serega6531.packmate.model.enums.Protocol;

import java.net.InetAddress;

public record UnfinishedStream(InetAddress firstIp, InetAddress secondIp, int firstPort, int secondPort,
                               Protocol protocol) {

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UnfinishedStream o)) {
            return false;
        }

        boolean ipEq1 = firstIp.equals(o.firstIp) && secondIp.equals(o.secondIp);
        boolean ipEq2 = firstIp.equals(o.secondIp) && secondIp.equals(o.firstIp);
        boolean portEq1 = firstPort == o.firstPort && secondPort == o.secondPort;
        boolean portEq2 = firstPort == o.secondPort && secondPort == o.firstPort;

        return (ipEq1 || ipEq2) && (portEq1 || portEq2) && protocol == o.protocol;
    }

    @Override
    public int hashCode() {
        final int PRIME = 59;

        int result = firstIp.hashCode() * secondIp.hashCode();
        result = result * PRIME + (firstPort * secondPort);
        result = result * PRIME + protocol.hashCode();

        return result;
    }
}
