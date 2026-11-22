package com.aether.gateway.proxy.web;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionCallDto(String name, String arguments) {
}
