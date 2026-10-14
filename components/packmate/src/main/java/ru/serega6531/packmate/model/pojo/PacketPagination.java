package ru.serega6531.packmate.model.pojo;

import lombok.Data;
import org.jetbrains.annotations.Nullable;

@Data
public class PacketPagination {

    @Nullable
    private Long startingFrom;

    private int pageSize;

}
