package com.aether.gateway.mockprovider.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Named explicitly so binding does not depend on the application's SNAKE_CASE setting. */
public record StreamOptionsDto(@JsonProperty("include_usage") Boolean includeUsage) {
}
