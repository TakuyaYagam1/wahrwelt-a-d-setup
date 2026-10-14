package ru.serega6531.packmate.controller;

import org.pcap4j.core.PcapNativeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.serega6531.packmate.service.PcapService;

@RestController
@RequestMapping("/api/pcap/")
public class PcapController {

    private final PcapService service;

    @Autowired
    public PcapController(PcapService service) {
        this.service = service;
    }

    @GetMapping("/started")
    public boolean started() {
        return service.isStarted();
    }

    @PostMapping("/start")
    public void start() throws PcapNativeException {
        service.start();
    }

}
