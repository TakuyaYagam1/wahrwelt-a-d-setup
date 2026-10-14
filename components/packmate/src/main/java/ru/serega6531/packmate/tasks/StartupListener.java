package ru.serega6531.packmate.tasks;

import org.pcap4j.core.PcapNativeException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.serega6531.packmate.properties.PackmateProperties;
import ru.serega6531.packmate.model.enums.CaptureMode;
import ru.serega6531.packmate.service.PcapService;
import ru.serega6531.packmate.service.ServicesService;

@Component
public class StartupListener {

    private final PackmateProperties packmateProperties;
    private final PcapService pcapService;
    private final ServicesService servicesService;

    public StartupListener(PcapService pcapService, ServicesService servicesService, PackmateProperties packmateProperties) {
        this.pcapService = pcapService;
        this.servicesService = servicesService;
        this.packmateProperties = packmateProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() throws PcapNativeException {
        servicesService.updateFilter();

        if (packmateProperties.captureMode() == CaptureMode.LIVE) {
            pcapService.start();
        }
    }

}
