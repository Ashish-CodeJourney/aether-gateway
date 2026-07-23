package com.aether.gateway.mockprovider.web;

import com.aether.gateway.mockprovider.DefaultControlsHolder;
import com.aether.gateway.mockprovider.MockControls;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only control surface, not part of the OpenAI-compatible wire
 * contract. Lets acceptance tests configure this instance's default
 * failure behaviour before issuing a request through the gateway (see
 * DefaultControlsHolder's javadoc for why this is necessary).
 */
@RestController
public class MockConfigController {

    private final DefaultControlsHolder defaultControlsHolder;

    public MockConfigController(DefaultControlsHolder defaultControlsHolder) {
        this.defaultControlsHolder = defaultControlsHolder;
    }

    @PostMapping("/_mock/config")
    public ResponseEntity<Void> configure(@RequestBody MockControls controls) {
        defaultControlsHolder.set(controls);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/_mock/reset")
    public ResponseEntity<Void> reset() {
        defaultControlsHolder.reset();
        return ResponseEntity.ok().build();
    }
}
