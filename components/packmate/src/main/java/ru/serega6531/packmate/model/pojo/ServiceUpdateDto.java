package ru.serega6531.packmate.model.pojo;

import lombok.Data;

@Data
public class ServiceUpdateDto {

    private int port;
    private String name;
    private boolean decryptTls;
    private boolean http;
    private boolean urldecodeHttpRequests;
    private boolean mergeAdjacentPackets;
    private boolean parseWebSockets;

}