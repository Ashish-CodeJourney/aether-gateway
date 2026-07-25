package com.aether.gateway.admin.web;

import com.aether.gateway.core.domain.ChatMessage;
import com.aether.gateway.core.domain.PromptVersion;
import com.aether.gateway.core.port.PromptRegistryPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/** F7.1/F7.3/F7.4, PRD 13.2: prompt registry admin surface. */
@RestController
public class PromptAdminController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PromptRegistryPort promptRegistryPort;
    private final Scheduler virtualThreadScheduler;

    public PromptAdminController(PromptRegistryPort promptRegistryPort, Scheduler virtualThreadScheduler) {
        this.promptRegistryPort = promptRegistryPort;
        this.virtualThreadScheduler = virtualThreadScheduler;
    }

    @PostMapping("/admin/prompts")
    public Mono<ResponseEntity<Object>> createPrompt(@RequestBody CreatePromptRequestDto request) {
        return Mono.fromCallable(() -> promptRegistryPort.createPrompt(request.name()))
                .subscribeOn(virtualThreadScheduler)
                .map(promptId -> ResponseEntity.status(HttpStatus.CREATED).body((Object) new PromptVersionResponseDto(promptId, request.name(), 0)));
    }

    @PostMapping("/admin/prompts/{name}/versions")
    public Mono<ResponseEntity<Object>> createVersion(@PathVariable("name") String name, @RequestBody CreatePromptVersionRequestDto request) {
        return Mono.fromCallable(() -> {
                    List<ChatMessage> template = request.template().stream()
                            .map(m -> new ChatMessage(m.role(), m.content()))
                            .toList();
                    Set<String> variables = request.variables() != null ? request.variables() : Set.of();
                    String modelDefaultsJson = request.modelDefaults() != null ? JSON.writeValueAsString(request.modelDefaults()) : null;
                    return promptRegistryPort.createVersion(name, template, variables, modelDefaultsJson);
                })
                .subscribeOn(virtualThreadScheduler)
                .map(this::toResponse);
    }

    @PutMapping("/admin/prompts/{name}/aliases/{alias}")
    public Mono<ResponseEntity<Object>> setAlias(
            @PathVariable("name") String name, @PathVariable("alias") String alias, @RequestBody SetAliasRequestDto request) {
        return Mono.fromRunnable(() -> promptRegistryPort.setAlias(name, alias, request.version()))
                .subscribeOn(virtualThreadScheduler)
                .then(Mono.just(ResponseEntity.ok().<Object>build()));
    }

    private ResponseEntity<Object> toResponse(PromptVersion version) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body((Object) new PromptVersionResponseDto(version.promptId(), version.promptName(), version.version()));
    }
}
