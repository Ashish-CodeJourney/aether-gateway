Feature: Streaming proxy with cancellation propagation

  @m1 @F1.4 @AC7
  Scenario: Client aborts mid-stream and the upstream call is cancelled
    Given the mock provider is configured with a 200 millisecond delay between stream chunks
    When a client sends a streaming chat completion request and aborts after receiving 2 chunks
    Then the upstream connection to the mock provider closes within 100 milliseconds

  @m1 @F1.2 @F1.3
  Scenario: A streaming request returns real content chunks terminated by DONE
    Given the mock provider has no configured delay
    When a client sends a streaming chat completion request to completion
    Then the client receives at least one non-empty content chunk
    And the stream ends with a DONE marker

  @m1 @F1.6
  Scenario: Listing available models
    Given the gateway is running with the mock provider configured
    When a client requests the list of available models
    Then the response includes the mock provider's declared models
