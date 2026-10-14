package ru.serega6531.packmate.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.serega6531.packmate.model.pojo.StreamDto;
import ru.serega6531.packmate.model.pojo.StreamPagination;
import ru.serega6531.packmate.service.StreamService;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/stream/")
public class StreamController {

    private final StreamService service;

    @Autowired
    public StreamController(StreamService service) {
        this.service = service;
    }

    @PostMapping("/all")
    public List<StreamDto> getStreams(@RequestBody StreamPagination pagination) {
        return service.findAll(pagination, Optional.empty(), pagination.isFavorites());
    }

    @PostMapping("/{port}")
    public List<StreamDto> getStreams(@PathVariable int port, @RequestBody StreamPagination pagination) {
        return service.findAll(pagination, Optional.of(port), pagination.isFavorites());
    }

    @PostMapping("/{id}/favorite")
    public void favoriteStream(@PathVariable long id) {
        service.setFavorite(id, true);
    }

    @PostMapping("/{id}/unfavorite")
    public void unfavoriteStream(@PathVariable long id) {
        service.setFavorite(id, false);
    }

}
