package ru.serega6531.packmate.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.serega6531.packmate.model.pojo.ServiceCreateDto;
import ru.serega6531.packmate.model.pojo.ServiceDto;
import ru.serega6531.packmate.model.pojo.ServiceUpdateDto;
import ru.serega6531.packmate.service.ServicesService;

import java.util.List;

@RestController
@RequestMapping("/api/service/")
public class ServiceController {

    private final ServicesService service;

    @Autowired
    public ServiceController(ServicesService service) {
        this.service = service;
    }

    @GetMapping
    public List<ServiceDto> getServices() {
        return service.findAll();
    }

    @DeleteMapping("/{port}")
    public void deleteService(@PathVariable int port) {
        service.deleteByPort(port);
    }

    @PostMapping
    public ServiceDto addService(@RequestBody ServiceCreateDto dto) {
        return this.service.create(dto);
    }

    @PostMapping("/{port}")
    public ServiceDto updateService(@PathVariable int port, @RequestBody ServiceUpdateDto dto) {
        return this.service.update(port, dto);
    }

}
