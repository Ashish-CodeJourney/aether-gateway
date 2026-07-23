Feature: Gateway forwards a basic chat completion to the mock provider

  @m0 @F1.1
  Scenario: A client gets an OpenAI-shaped response through the gateway
    Given the gateway is running with the mock provider configured
    When a client sends a chat completion request for model "mock"
    Then the response has an OpenAI-shaped choices array
    And the response status is 200
