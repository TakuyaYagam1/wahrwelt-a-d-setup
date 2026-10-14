package ru.serega6531.packmate.pcap;

import com.google.common.collect.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.pcap4j.core.BpfProgram;
import org.pcap4j.core.PacketListener;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapPacket;
import org.pcap4j.packet.*;
import ru.serega6531.packmate.model.CtfService;
import ru.serega6531.packmate.model.enums.Protocol;
import ru.serega6531.packmate.model.pojo.UnfinishedStream;
import ru.serega6531.packmate.service.ServicesService;
import ru.serega6531.packmate.service.StreamService;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractPcapWorker implements PcapWorker, PacketListener {

    private final ServicesService servicesService;
    private final StreamService streamService;

    protected volatile PcapHandle pcap = null;
    protected final ExecutorService loopExecutorService;

    protected String filter = "tcp or udp";

    // Во время работы должен быть задан.
    protected ExecutorService processorExecutorService;

    private final InetAddress localIp;

    private long packetIdCounter = 0;  // Доступ однопоточный, поэтому AtomicLong не требуется.

    private final ListMultimap<UnfinishedStream, ru.serega6531.packmate.model.Packet> unfinishedTcpStreams = ArrayListMultimap.create();
    private final ListMultimap<UnfinishedStream, ru.serega6531.packmate.model.Packet> unfinishedUdpStreams = ArrayListMultimap.create();

    // В следующих множествах хранятся адреса источника соответствующего пакета.
    private final SetMultimap<UnfinishedStream, Pair<InetAddress, Integer>> fins = HashMultimap.create();
    private final SetMultimap<UnfinishedStream, Pair<InetAddress, Integer>> acks = HashMultimap.create();

    protected AbstractPcapWorker(ServicesService servicesService,
                              StreamService streamService,
                              InetAddress localIp) throws UnknownHostException {
        this.servicesService = servicesService;
        this.streamService = streamService;

        this.localIp = localIp;

        BasicThreadFactory factory = new BasicThreadFactory.Builder()
                .namingPattern("pcap-loop").build();
        loopExecutorService = Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void gotPacket(PcapPacket capturedPacket) {
        gotPacket(capturedPacket, pcap);
    }

    protected void gotPacket(PcapPacket capturedPacket, PcapHandle capture) {
        if (capture == null) {
            return;
        }

        final Packet rawPacket = capturedPacket.getPacket();

        InetAddress sourceIp;
        InetAddress destIp;
        int ttl;

        if (rawPacket.contains(IpV4Packet.class)) {
            final IpV4Packet.IpV4Header ipHeader = rawPacket.get(IpV4Packet.class).getHeader();
            sourceIp = ipHeader.getSrcAddr();
            destIp = ipHeader.getDstAddr();
            ttl = Byte.toUnsignedInt(ipHeader.getTtl());
        } else if (rawPacket.contains(IpV6Packet.class)) {
            final IpV6Packet.IpV6Header ipHeader = rawPacket.get(IpV6Packet.class).getHeader();
            sourceIp = ipHeader.getSrcAddr();
            destIp = ipHeader.getDstAddr();
            ttl = Byte.toUnsignedInt(ipHeader.getHopLimit());
        } else {
            return;
        }

        final long time = capturedPacket.getTimestamp().toEpochMilli();

        if (rawPacket.contains(TcpPacket.class)) {
            final TcpPacket packet = rawPacket.get(TcpPacket.class);
            gotTcpPacket(packet, sourceIp, destIp, ttl, time);
        } else if (rawPacket.contains(UdpPacket.class)) {
            final UdpPacket packet = rawPacket.get(UdpPacket.class);
            gotUdpPacket(packet, sourceIp, destIp, ttl, time);
        }
    }

    private void gotTcpPacket(TcpPacket packet, InetAddress sourceIp, InetAddress destIp, int ttl, long time) {
        final TcpPacket.TcpHeader tcpHeader = packet.getHeader();
        int sourcePort = tcpHeader.getSrcPort().valueAsInt();
        int destPort = tcpHeader.getDstPort().valueAsInt();
        boolean ack = tcpHeader.getAck();
        boolean fin = tcpHeader.getFin();
        boolean rst = tcpHeader.getRst();
        byte[] content = packet.getPayload() != null ? packet.getPayload().getRawData() : new byte[0];

        String sourceIpString = sourceIp.getHostAddress();
        String destIpString = destIp.getHostAddress();

        final Optional<CtfService> serviceOptional =
                servicesService.findService(sourceIp, sourcePort, destIp, destPort);

        if (serviceOptional.isPresent()) {
            processorExecutorService.execute(() -> {
                UnfinishedStream stream = addNewPacket(sourceIp, destIp, time, sourcePort, destPort, ttl, content, Protocol.TCP);

                if (log.isDebugEnabled()) {
                    log.debug("TCP {} {}:{} -> {}:{}, номер пакета {}",
                            serviceOptional.get(), sourceIpString, sourcePort, destIpString, destPort,
                            unfinishedTcpStreams.get(stream).size());
                }

                checkTcpTermination(ack, fin, rst, new ImmutablePair<>(sourceIp, sourcePort), new ImmutablePair<>(destIp, destPort), stream);
            });
        } else { // сервис не найден
            if (log.isTraceEnabled()) {
                log.trace("tcp {}:{} -> {}:{}", sourceIpString, sourcePort, destIpString, destPort);
            }
        }
    }

    private void gotUdpPacket(UdpPacket packet, InetAddress sourceIp, InetAddress destIp, int ttl, long time) {
        final UdpPacket.UdpHeader udpHeader = packet.getHeader();
        int sourcePort = udpHeader.getSrcPort().valueAsInt();
        int destPort = udpHeader.getDstPort().valueAsInt();
        byte[] content = packet.getPayload() != null ? packet.getPayload().getRawData() : new byte[0];

        String sourceIpString = sourceIp.getHostAddress();
        String destIpString = destIp.getHostAddress();

        final Optional<CtfService> serviceOptional =
                servicesService.findService(sourceIp, sourcePort, destIp, destPort);

        if (serviceOptional.isPresent()) {
            processorExecutorService.execute(() -> {
                UnfinishedStream stream = addNewPacket(sourceIp, destIp, time, sourcePort, destPort, ttl, content, Protocol.UDP);

                if (log.isDebugEnabled()) {
                    log.debug("UDP {} {}:{} -> {}:{}, номер пакета {}",
                            serviceOptional.get(), sourceIpString, sourcePort, destIpString, destPort,
                            unfinishedUdpStreams.get(stream).size());
                }
            });
        } else { // сервис не найден
            if (log.isTraceEnabled()) {
                log.trace("udp {}:{} -> {}:{}", sourceIpString, sourcePort, destIpString, destPort);
            }
        }
    }

    private UnfinishedStream addNewPacket(InetAddress sourceIp, InetAddress destIp, long time,
                                          int sourcePort, int destPort, int ttl, byte[] content, Protocol protocol) {
        var incoming = destIp.equals(localIp);
        var stream = new UnfinishedStream(sourceIp, destIp, sourcePort, destPort, protocol);

        var packet = ru.serega6531.packmate.model.Packet.builder()
                .tempId(packetIdCounter++)
                .ttl(ttl)
                .timestamp(time)
                .incoming(incoming)
                .content(content)
                .build();

        final var streams = (protocol == Protocol.TCP) ? this.unfinishedTcpStreams : this.unfinishedUdpStreams;

        if (!streams.containsKey(stream)) {
            log.debug("Начат новый поток");
        }

        streams.put(stream, packet);
        return stream;
    }

    /**
     * Udp не имеет фазы закрытия, поэтому закрывается только по таймауту
     */
    private void checkTcpTermination(boolean ack, boolean fin, boolean rst,
                                     Pair<InetAddress, Integer> sourceIpAndPort,
                                     Pair<InetAddress, Integer> destIpAndPort,
                                     UnfinishedStream stream) {

        if (fin) {
            fins.put(stream, sourceIpAndPort);
        }

        if (ack && fins.containsEntry(stream, destIpAndPort)) {  // проверяем destIp, потому что ищем ответ на его fin
            acks.put(stream, sourceIpAndPort);
        }

        // если соединение разорвано с помощью rst или закрыто с помощью fin-ack-fin-ack
        if (rst || (acks.containsEntry(stream, sourceIpAndPort) && acks.containsEntry(stream, destIpAndPort))) {
            streamService.saveNewStream(stream, unfinishedTcpStreams.get(stream));

            unfinishedTcpStreams.removeAll(stream);
            fins.removeAll(stream);
            acks.removeAll(stream);
        }
    }

    @Override
    @SneakyThrows
    public void closeAllStreams(Protocol protocol) {
        final var streams = (protocol == Protocol.TCP) ? this.unfinishedTcpStreams : this.unfinishedUdpStreams;

        Multimaps.asMap(streams).forEach((key, value) ->
                streamService.saveNewStream(key, new ArrayList<>(value)));

        streams.clear();
        if (protocol == Protocol.TCP) {
            fins.clear();
            acks.clear();
        }
    }

    @Override
    @SneakyThrows
    public int closeTimeoutStreams(Protocol protocol, long timeoutMillis) {
        return processorExecutorService.submit(() -> {
            int streamsClosed = 0;

            final long time = System.currentTimeMillis();
            final var streams = (protocol == Protocol.TCP) ? this.unfinishedTcpStreams : this.unfinishedUdpStreams;

            final var oldStreams = Multimaps.asMap(streams).entrySet().stream()
                    .filter(entry -> {
                        final var packets = entry.getValue();
                        return time - packets.get(packets.size() - 1).getTimestamp() > timeoutMillis;
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            for (var entry : oldStreams.entrySet()) {
                final UnfinishedStream stream = entry.getKey();
                final var packets = entry.getValue();

                if (streamService.saveNewStream(stream, packets)) {
                    streamsClosed++;
                }

                if (protocol == Protocol.TCP) {
                    fins.removeAll(stream);
                    acks.removeAll(stream);
                }

                streams.removeAll(stream);
            }

            return streamsClosed;
        }).get();
    }

    @Override
    public void setFilter(String filter) {
        this.filter = filter;
        applyFilter();
    }

    @SneakyThrows
    protected void applyFilter() {
        if(filter != null && pcap != null && pcap.isOpen()) {
            pcap.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE);
        }
    }

    protected void shutdownExecutor(ExecutorService executor, String executorName) {
        if (executor == null) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Поток обработки {} не завершился вовремя, отправляется сигнал остановки", executorName);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Поток обработки {} не завершился после сигнала остановки", executorName);
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
