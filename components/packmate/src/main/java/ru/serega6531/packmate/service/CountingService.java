package ru.serega6531.packmate.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.serega6531.packmate.model.enums.SubscriptionMessageType;
import ru.serega6531.packmate.model.pojo.Counter;
import ru.serega6531.packmate.model.pojo.CountersHolder;
import ru.serega6531.packmate.model.pojo.SubscriptionMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CountingService {

    private final SubscriptionService subscriptionService;

    private final Map<Integer, Counter> servicesPackets = new HashMap<>();
    private final Map<Integer, Counter> servicesStreams = new HashMap<>();

    private Counter totalPackets = new Counter();
    private Counter totalStreams = new Counter();

    @Autowired
    public CountingService(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    synchronized void countStream(int serviceId, int packets) {
        getCounter(servicesPackets, serviceId).increment(packets);
        getCounter(servicesStreams, serviceId).increment();

        totalPackets.increment(packets);
        totalStreams.increment();
    }

    @Scheduled(cron = "0 * * ? * *")
    public void sendCounters() {
        CountersHolder counters;
        synchronized (this) {
            counters = new CountersHolder(toIntegerMap(servicesPackets), toIntegerMap(servicesStreams),
                    totalPackets.getValue(), totalStreams.getValue());
            servicesPackets.clear();
            servicesStreams.clear();
            totalPackets = new Counter();
            totalStreams = new Counter();
        }
        subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.COUNTERS_UPDATE, counters));
    }

    private Map<Integer, Integer> toIntegerMap(Map<Integer, Counter> map) {
        return map.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        ent -> ent.getValue().getValue()));
    }

    private Counter getCounter(Map<Integer, Counter> counters, int serviceId) {
        return counters.computeIfAbsent(serviceId, c -> new Counter());
    }

}
