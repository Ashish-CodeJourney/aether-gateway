Feature: OpenAI-compatible embeddings

  @m10 @embeddings
  Scenario: A client embeds a batch of texts through the gateway
    Given both mock providers are healthy
    When a client requests embeddings for "hello" and "world" with model "mock"
    Then the embeddings response is successful
    And the embeddings response is an OpenAI-shaped list of 2 embeddings
    And the embeddings response reports prompt token usage
    And the embeddings response was served by "mock-primary"
    And the embeddings response carries a cost header

  @m10 @embeddings
  Scenario: Embeddings are deterministic for the same input
    Given both mock providers are healthy
    When a client requests embeddings for "hello" and "world" with model "mock"
    And the client requests embeddings for "hello" and "world" with model "mock" again
    Then both embeddings responses carry identical vectors

  @m10 @embeddings @F3.2
  Scenario: Primary provider fails, embeddings are served by the fallback
    Given both mock providers are healthy
    And the primary mock provider fails every request with a 503
    When a client requests embeddings for "hello" and "world" with model "mock"
    Then the embeddings response is successful
    And the embeddings response was served by "mock-fallback"
    And the embeddings response reports more than 1 attempt

  @m10 @embeddings
  Scenario: Token-array input is rejected with a clear client error
    When a client requests embeddings for token ids with model "mock"
    Then the embeddings response status is 400
    And the embeddings error mentions "token"
