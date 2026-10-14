package ru.serega6531.packmate.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {

    @GetMapping("/{servicePort:[0-9]+}")
    public String serviceRoute() {
        return "forward:/index.html";
    }

    @GetMapping("/{servicePort:[0-9]+}/{streamId:[0-9]+}")
    public String streamRoute() {
        return "forward:/index.html";
    }
}
