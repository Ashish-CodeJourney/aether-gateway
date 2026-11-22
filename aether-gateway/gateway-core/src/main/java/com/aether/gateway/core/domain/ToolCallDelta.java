package com.aether.gateway.core.domain;

/**
 * One streamed fragment of a tool call, in OpenAI's {@code delta.tool_calls}
 * shape: the first fragment for a given {@code index} carries {@code id} and
 * {@code functionName}; later ones carry only the next slice of
 * {@code argumentsFragment}, which the client concatenates. Null fields mean
 * "not present in this fragment".
 */
public record ToolCallDelta(int index, String id, String functionName, String argumentsFragment) {
}
