package com.aether.gateway.admin.web;

import java.util.List;

public record RouteConfigDto(String alias, List<ChainMemberDto> chain) {

    public record ChainMemberDto(String provider, String model, Integer weight) {
    }
}
