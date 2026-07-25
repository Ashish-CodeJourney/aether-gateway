Feature: Prompt version rollback with no restart

  @m7 @F7.4
  Scenario: Rolling back the production alias takes effect immediately
    Given prompt "support-reply" has version 1 and version 2
    And alias "production" points at version 2
    When a client sends a request referencing "support-reply@production"
    Then the response reflects version 2's template
    When the "production" alias is repointed to version 1 via the admin API
    And a client sends a request referencing "support-reply@production"
    Then the response reflects version 1's template

  @m7 @F8.5
  Scenario: Gateway API keys cannot access admin endpoints
    Given a valid gateway API key with no admin privileges
    When that key is used to call an admin endpoint
    Then the response status is 401 or 403

  @m7 @F8.5
  Scenario: The correct admin key can access admin endpoints
    When the correct admin key is used to call an admin endpoint
    Then the admin call succeeds
