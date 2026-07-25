Feature: SSRF protection on provider base URLs

  @m9 @F9.3
  Scenario: A provider base URL pointing at a private IP is rejected
    Given an operator attempts to configure a provider base URL pointing at a private IP range
    When the routing policy is reloaded with that configuration
    Then the configuration is rejected
    And a subsequent chat completion request through the "mock" route still succeeds
