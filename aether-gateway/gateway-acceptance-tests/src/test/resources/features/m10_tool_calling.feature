Feature: OpenAI-format tool calling passes through the gateway

  @m10 @tool-calling
  Scenario: A non-streaming request offering a tool gets the provider's tool call back
    Given the mock provider has no configured delay
    When a client offers the "get_weather" tool in a chat completion request
    Then the response status is 200 for the tool call request
    And the response finishes with reason "tool_calls"
    And the response calls "get_weather" with arguments {"location":"mock-city"}
    And the tool-calling assistant message has null content

  @m10 @tool-calling
  Scenario: A tool result sent back by the client reaches the provider
    Given the mock provider has no configured delay
    When a client returns the result "18C and sunny" for tool call "call_mock_0" of "get_weather"
    Then the response status is 200 for the tool call request
    And the response finishes with reason "stop"
    And the assistant replies "Mock response for model mock using tool result for call_mock_0: 18C and sunny"

  @m10 @tool-calling
  Scenario: A streaming request offering a tool relays the tool call incrementally
    Given the mock provider has no configured delay
    When a client offers the "get_weather" tool in a streaming chat completion request
    Then the first tool call fragment carries an id and the function name "get_weather"
    And the streamed tool call arguments reassemble to {"location":"mock-city"}
    And the stream finishes with reason "tool_calls"
    And the tool call stream ends with a DONE marker

  @m10 @tool-calling @F4.5
  Scenario: Tool-calling requests and their tool calls are never cached
    Given the mock provider has no configured delay
    When a client offers the "lookup_order" tool in the same chat completion request twice
    Then both responses bypassed the cache
    And both responses call "lookup_order"
