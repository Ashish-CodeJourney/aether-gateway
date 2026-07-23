package com.aether.gateway.proxy.web;

import java.util.List;

public record ModelListDto(String object, List<ModelDto> data) {
}
