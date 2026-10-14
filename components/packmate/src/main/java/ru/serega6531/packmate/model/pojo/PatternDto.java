package ru.serega6531.packmate.model.pojo;

import lombok.Data;
import ru.serega6531.packmate.model.enums.PatternActionType;
import ru.serega6531.packmate.model.enums.PatternDirectionType;
import ru.serega6531.packmate.model.enums.PatternSearchType;

@Data
public class PatternDto {

    private int id;
    private boolean enabled;
    private boolean deleted;
    private String name;
    private String value;
    private String color;
    private PatternSearchType searchType;
    private PatternDirectionType directionType;
    private PatternActionType actionType;
    private Integer serviceId;

}
