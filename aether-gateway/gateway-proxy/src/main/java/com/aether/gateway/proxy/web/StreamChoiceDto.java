package com.aether.gateway.proxy.web;

public record StreamChoiceDto(int index, DeltaDto delta, String finishReason) {
}
