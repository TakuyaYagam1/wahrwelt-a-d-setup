package ru.serega6531.packmate.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class PcapInterfaceNotFoundException extends RuntimeException {

    private final String requestedInterface;
    private final List<String> existingInterfaces;

}
