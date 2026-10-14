package ru.serega6531.packmate.model.pojo;

import java.util.Map;

public record CountersHolder(Map<Integer, Integer> servicesPackets, Map<Integer, Integer> servicesStreams,
                             int totalPackets, int totalStreams) {

}
