package ru.serega6531.packmate.model.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.serega6531.packmate.model.enums.SubscriptionMessageType;

@Data
@AllArgsConstructor
public class SubscriptionMessage {

    private SubscriptionMessageType type;
    private Object value;

}
