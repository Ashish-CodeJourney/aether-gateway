package com.aether.gateway.mockprovider.web;

public record StreamChoiceDto(int index, DeltaDto delta, String finishReason) {
}
