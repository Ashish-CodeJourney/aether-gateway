Feature: Routing and resilience: failover to a healthy provider

  @m2 @F3.2 @AC6
  Scenario: Primary provider fails, request succeeds via fallback
    Given the mock provider is configured to fail every request with a 503
    When a client sends a chat completion request through the gateway
    Then the response is successful
    And the response was returned within 500 milliseconds

  @m2 @F2.7
  Scenario: Routing policy hot reload takes effect without a restart
    Given the mock provider is configured to fail every request with a 503
    When the routing policy is reloaded
    And a client sends a chat completion request through the gateway
    Then the response is successful
