Feature: Cost and cache-outcome visibility per request

  @m5 @F6.2 @F6.4
  Scenario: A cache hit records its avoided cost
    Given a prior request was made and cached for prompt "Explain photosynthesis"
    When a client sends "Please explain how photosynthesis works" with cache threshold "0.75"
    Then a request log entry exists for this request
    And that entry's cache outcome is "SEMANTIC_HIT" or "EXACT_HIT"
    And that entry's "saved_usd" value is greater than zero

  @m5 @F6.3
  Scenario: Cost is computed from the configured cost model
    Given the cost model prices the "mock" model's input tokens at "0.50" and output tokens at "1.50" per million
    When a client sends "Cost model verification prompt, unique text" that is not served from cache
    Then a request log entry exists for this request
    And that entry's "cost_usd" matches the expected value computed from the configured rates and reported token usage
