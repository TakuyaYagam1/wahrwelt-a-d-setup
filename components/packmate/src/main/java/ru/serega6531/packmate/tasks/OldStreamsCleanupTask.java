package ru.serega6531.packmate.tasks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.serega6531.packmate.properties.PackmateProperties;
import ru.serega6531.packmate.service.StreamService;

import java.time.ZonedDateTime;

@Component
@Slf4j
@ConditionalOnExpression("${packmate.cleanup.enabled:false} && '${packmate.capture-mode}' == 'LIVE'")
public class OldStreamsCleanupTask {

    private final StreamService service;
    private final int oldStreamsThreshold;

    public OldStreamsCleanupTask(StreamService service, PackmateProperties properties) {
        this.service = service;
        this.oldStreamsThreshold = properties.cleanup().threshold();
    }

    @Scheduled(fixedDelayString = "PT${packmate.cleanup.interval}M", initialDelayString = "PT1M")
    public void cleanup() {
        ZonedDateTime before = ZonedDateTime.now().minusMinutes(oldStreamsThreshold);
        log.info("Удаляются старые неизбранные потоки до {}", before);
        long deleted = service.cleanupOldStreams(before);
        log.info("Удалено записей: {}", deleted);
    }

}
