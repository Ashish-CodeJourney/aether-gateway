package com.aether.gateway.mockprovider.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ModelsController {

    @GetMapping("/v1/models")
    public ModelListDto listModels() {
        return new ModelListDto("list", List.of(new ModelDto("mock", "model")));
    }
}
