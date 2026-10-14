package ru.serega6531.packmate.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.File;

@EqualsAndHashCode(callSuper = true)
@Data
public class PcapFileNotFoundException extends RuntimeException {

    private final File file;
    private final File directory;

}
