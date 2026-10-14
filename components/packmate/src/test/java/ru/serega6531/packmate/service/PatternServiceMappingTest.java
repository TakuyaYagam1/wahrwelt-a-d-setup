package ru.serega6531.packmate.service;

import org.junit.jupiter.api.Test;
import ru.serega6531.packmate.model.Pattern;
import ru.serega6531.packmate.model.pojo.PatternCreateDto;
import ru.serega6531.packmate.repository.PatternRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class PatternServiceMappingTest {

    @Test
    void createDtoServiceIdDoesNotBecomeGeneratedPatternId() {
        PatternCreateDto dto = new PatternCreateDto();
        dto.setName("Тестовый флаг");
        dto.setValue("[A-Z0-9]{31}=");
        dto.setColor("#ffcc00");
        dto.setServiceId(8081);

        PatternService service = new PatternService(
                mock(PatternRepository.class),
                mock(StreamService.class),
                mock(SubscriptionService.class));

        Pattern pattern = service.fromDto(dto);

        assertNull(pattern.getId());
        assertEquals(8081, pattern.getServiceId());
    }
}
