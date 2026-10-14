package ru.serega6531.packmate.model.pojo;

import lombok.Data;
import org.jetbrains.annotations.Nullable;
import ru.serega6531.packmate.model.Pattern;

@Data
public class StreamPagination {

    @Nullable
    private Long startingFrom;

    private int pageSize;

    private boolean favorites; // Искать только избранные потоки или все.

    @Nullable
    private Pattern pattern; // Если задан, искать потоки с этим паттерном.

}
