package ru.serega6531.packmate.model.pojo;

import lombok.Data;
import ru.serega6531.packmate.model.enums.Protocol;

import java.util.Set;

@Data
public class StreamDto {

    private Long id;
    private int service;
    private Protocol protocol;
    private long startTimestamp;
    private long endTimestamp;
    private Set<Integer> foundPatternsIds;
    private boolean favorite;
    private int ttl;
    private String userAgentHash;
    private int sizeBytes;
    private int packetsCount;

}
