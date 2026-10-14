package ru.serega6531.packmate.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.serega6531.packmate.model.pojo.PacketDto;
import ru.serega6531.packmate.model.pojo.PacketPagination;
import ru.serega6531.packmate.service.StreamService;

import java.util.List;

@RestController
@RequestMapping("/api/packet/")
public class PacketController {

    private final StreamService streamService;

    @Autowired
    public PacketController(StreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping("/{streamId}")
    public List<PacketDto> getPacketsForStream(@PathVariable long streamId, @RequestBody PacketPagination pagination) {
        return streamService.getPackets(streamId, pagination.getStartingFrom(), pagination.getPageSize());
    }

}
