package ru.serega6531.packmate.tasks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.serega6531.packmate.service.PcapService;

@Component
@Slf4j
public class ExecutorStateLoggerTask {

    private final PcapService service;

    public ExecutorStateLoggerTask(PcapService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void cleanup() {
        log.info("Состояние обработчика: {}", service.getExecutorState());
    }

}
