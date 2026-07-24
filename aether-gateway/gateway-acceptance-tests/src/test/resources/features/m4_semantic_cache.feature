Feature: Semantic cache correctness

  @m4 @F4.2 @AC3
  Scenario: A near-duplicate prompt is served from the semantic cache
    Given a prior request was made and cached for prompt "What is the capital of France?"
    When a client sends "Can you tell me France's capital city?" with cache threshold "0.75"
    Then the response header "X-Aether-Cache" is "SEMANTIC_HIT"
    And the response header "X-Aether-Similarity" is at least "0.75"

  @m4 @F4.5 @AC4
  Scenario: An adversarial near-miss is never served as a cache hit
    Given a prior request was made and cached for prompt "What is 2 + 2?"
    When a client sends "What is 2 + 3?" with cache threshold "0.5"
    Then the response header "X-Aether-Cache" is not "SEMANTIC_HIT"
    And the response header "X-Aether-Cache" is not "EXACT_HIT"

  @m4 @F4.4
  Scenario: Cache entries never cross API key namespaces
    Given API key "tenant-a" has a cached response for "Summarise this contract"
    When API key "tenant-b" sends the identical prompt "Summarise this contract"
    Then the response header "X-Aether-Cache" is not "EXACT_HIT"
    And the response header "X-Aether-Cache" is not "SEMANTIC_HIT"

  @m4 @F4.5
  Scenario Outline: Corpus-sampled near-duplicate pairs are served from the semantic cache
    Given a prior request was made and cached for prompt "<seed>"
    When a client sends "<variant>" with cache threshold "0.75"
    Then the response header "X-Aether-Cache" is "SEMANTIC_HIT"

    Examples:
      | seed                                                          | variant                                                      |
      | How do I reset my router to factory settings?                 | What's the process for restoring my router to its default settings? |
      | What are the main differences between TCP and UDP?            | Can you explain how TCP differs from UDP?                    |
      | How does photosynthesis work?                                 | Can you explain the process of photosynthesis?                |
      | What's a simple recipe for banana bread?                      | Do you have an easy banana bread recipe?                      |
      | How can I improve my sleep quality?                           | What are some ways to get better sleep?                       |
      | Explain the difference between stocks and bonds.              | How do stocks differ from bonds?                               |
      | What are the benefits of regular exercise?                    | Why is regular exercise good for you?                          |
      | How do I create a budget spreadsheet?                         | What's a good way to build a budget spreadsheet?               |

  @m4 @F4.5
  Scenario Outline: Corpus-sampled adversarial near-misses are never served as a cache hit
    Given a prior request was made and cached for prompt "<seed>"
    When a client sends "<variant>" with cache threshold "0.5"
    Then the response header "X-Aether-Cache" is not "SEMANTIC_HIT"

    Examples:
      | seed                                              | variant                                             |
      | What's the capital of Japan?                       | What's the capital of South Korea?                  |
      | The meeting is scheduled for 3 PM on Tuesday.       | The meeting is scheduled for 3 PM on Wednesday.      |
      | Our warehouse has 500 units of inventory left.      | Our warehouse has 50 units of inventory left.        |
      | Employees get 15 days of paid vacation per year.    | Employees get 25 days of paid vacation per year.     |
      | The recipe calls for 2 cups of flour.               | The recipe calls for 3 cups of flour.                |
      | The project deadline is March 15th.                 | The project deadline is March 25th.                  |
      | The hotel has 120 rooms.                            | The hotel has 220 rooms.                             |
      | The conference will be held in Berlin.              | The conference will be held in Munich.               |
