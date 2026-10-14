package ru.serega6531.packmate.service.optimization.tls.keys.enums;

public enum CurveType {

    NAMED((byte) 0x03);

    private final byte value;

    CurveType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }
}
