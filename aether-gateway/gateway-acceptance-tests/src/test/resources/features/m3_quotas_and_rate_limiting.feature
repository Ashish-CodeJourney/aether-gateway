Feature: Quota enforcement under concurrency

  @m3 @F5.5 @AC8
  Scenario: Exactly the quota limit succeeds under concurrent load
    Given an API key with a monthly token budget allowing exactly 50 requests of the test workload
    When 100 concurrent chat completion requests are sent using that key
    Then exactly 50 requests succeed
    And exactly 50 requests are rejected with status 429
    And each rejected response includes an "X-Aether-Quota-Remaining" header of "0"

  @m3 @F5.1
  Scenario: RPS token bucket rejects a burst above its rate
    Given an API key with an RPS limit of 5
    When 20 requests are sent within 1 second using that key
    Then at least one request is rejected with status 429
