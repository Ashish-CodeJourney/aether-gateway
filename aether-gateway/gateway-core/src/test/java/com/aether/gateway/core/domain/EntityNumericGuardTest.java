package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 correctness note, mitigation 2: reject a semantic hit outright if
 * the two prompts differ in any extracted number, named entity, or
 * date, regardless of similarity score. This is the direct mitigation
 * against the PRD's named failure mode ("what is 2+2 vs what is 2+3").
 */
class EntityNumericGuardTest {

    @Test
    void flagsPromptsThatDifferOnlyInANumberAsNotSafeToServeFromCache() {
        boolean safe = EntityNumericGuard.sameFingerprint("What is 2 + 2?", "What is 2 + 3?");

        assertThat(safe).isFalse();
    }

    @Test
    void flagsPromptsThatDifferInANamedEntityAsNotSafeToServeFromCache() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "What is the capital of France?", "What is the capital of Germany?");

        assertThat(safe).isFalse();
    }

    @Test
    void flagsPromptsThatDifferInADateAsNotSafeToServeFromCache() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "What happened on 2024-01-01?", "What happened on 2024-01-02?");

        assertThat(safe).isFalse();
    }

    @Test
    void allowsGenuineParaphrasesThatShareTheSameEntityThrough() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "What's the capital of France?", "Can you tell me France's capital city?");

        assertThat(safe).isTrue();
    }

    @Test
    void allowsGenuineParaphrasesWithNoNumbersOrEntitiesThrough() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Summarise this contract for me", "Can you give me a summary of this contract?");

        assertThat(safe).isTrue();
    }

    @Test
    void treatsDifferentPhrasingsOfTheSameNumberAsTheSameFingerprint() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "I have 100 apples", "There are 100 apples here");

        assertThat(safe).isTrue();
    }

    @Test
    void flagsAnAddedNumberThatWasNotInTheOriginalPromptAtAll() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "How many days in a week?", "How many days in 2 weeks?");

        assertThat(safe).isFalse();
    }

    @Test
    void isCaseInsensitiveForEntityComparison() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Tell me about FRANCE", "Tell me about France");

        assertThat(safe).isTrue();
    }

    @Test
    void doesNotFlagOrdinarySentenceInitialCapitalizationAsAPhantomEntityMismatch() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "What is the weather today?", "How is the weather today?");

        assertThat(safe).isTrue();
    }
}
