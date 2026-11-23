Feature: Lite mode - one process, no Postgres, no Redis, no embedding model

  Lite mode keeps every store in memory (ADR-010). These scenarios only
  run against a gateway started with the lite profile; the rest of the
  suite runs against both modes, apart from scenarios tagged
  @requires-semantic-cache.

  @m10 @lite-only
  Scenario: The gateway reports that it is running in lite mode
    When an operator reads the gateway's info endpoint
    Then the gateway reports that it is running in "lite" mode

  @m10 @lite-only
  Scenario: An echo route answers in-process and repeats are served from the cache
    When a client sends "hello from lite mode" to the "echo" route
    Then the reply content is "Echo: hello from lite mode"
    And the reply was served with cache outcome "MISS"
    When a client sends "hello from lite mode" to the "echo" route
    Then the reply was served with cache outcome "EXACT_HIT"

  @m10 @lite-only @F4.7
  Scenario: A route asking for semantic matching is served exact matches only
    Given cache requests target the "mock-lenient-cache" route
    And a prior request was made and cached for prompt "What is the capital of Peru?"
    When a client sends "Can you tell me Peru's capital city?" with cache threshold "0.75"
    Then the response header "X-Aether-Cache" is "MISS"
    When a client sends "What is the capital of Peru?" with no cache threshold header
    Then the response header "X-Aether-Cache" is "EXACT_HIT"

  @m10 @lite-only @F8.4
  Scenario: Usage is reported from the in-memory request log
    When a client sends "count me in the usage report" to the "echo" route
    Then the usage report grouped by model includes "echo-1"
