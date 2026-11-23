package com.aether.gateway.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Is aspirin safe to take during pregnancy?                     | Is aspirin not safe to take during pregnancy?
            Patient reports no known drug allergies.                      | Patient reports known drug allergies.
            This medication should be taken with food.                    | This medication should not be taken with food.
            The shipment is expected to arrive on Monday.                 | The shipment is not expected to arrive on Monday.
            Employee ID 48213 is authorized for level-2 database access.  | Employee ID 48213 is not authorized for level-2 database access.
            Does the API support pagination?                              | Doesn't the API support pagination?
            Can I deploy on Fridays?                                      | I can't deploy on Fridays, right?
            I can deploy on Fridays.                                      | I cannot deploy on Fridays.
            The tests run with the cache.                                 | The tests run without the cache.
            Has anyone reported this bug?                                 | Has nobody reported this bug?
            We always retry failed requests.                              | We never retry failed requests.
            Is it safe to delete these files?                             | Isn’t it safe to delete these files?
            """)
    void flagsANegationPresentInOnlyOnePromptAsNotSafeToServeFromCache(String affirmative, String negated) {
        assertThat(EntityNumericGuard.sameFingerprint(affirmative, negated)).isFalse();
        assertThat(EntityNumericGuard.sameFingerprint(negated, affirmative)).isFalse();
    }

    @Test
    void allowsParaphrasesThatAreBothNegatedThrough() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Why doesn't my build cache work?", "Why is my build cache not working?");

        assertThat(safe).isTrue();
    }

    @Test
    void doesNotMistakeWordsThatMerelyContainNegationLettersForANegation() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Do you know a good notebook app?", "Can you recommend a good notebook app?");

        assertThat(safe).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The vaccine requires two doses.               | The vaccine requires three doses.
            The vaccine requires two doses.               | The vaccine requires 3 doses.
            The team has twenty engineers.                | The team has 21 engineers.
            The team has twenty engineers.                | The team has twenty-one engineers.
            The hall seats one hundred people.            | The hall seats two hundred people.
            The hall seats a hundred and five people.     | The hall seats 150 people.
            The prize is two thousand dollars.            | The prize is 2,500 dollars.
            The city has three million residents.         | The city has 3 thousand residents.
            The quiz has twelve questions.                | The quiz has seventeen questions.
            The function takes zero arguments.            | The function takes two arguments.
            """)
    void flagsSpelledOutNumbersThatDifferAsNotSafeToServeFromCache(String promptA, String promptB) {
        assertThat(EntityNumericGuard.sameFingerprint(promptA, promptB)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The vaccine requires two doses.                    | The vaccine requires 2 doses.
            The team has twenty-one engineers.                 | The team has 21 engineers.
            The team has twenty one engineers.                 | The team is 21 engineers.
            The hall seats one hundred people.                 | The hall seats 100 people.
            The hall seats a hundred and five people.          | The hall seats 105 people.
            The prize is two thousand five hundred dollars.    | The prize is 2,500 dollars.
            The city has three million residents.              | The city has 3000000 residents.
            The city has three million residents.              | The city has 3 million residents.
            The startup raised 1.5 million dollars.            | The startup raised 1,500,000 dollars.
            Summarize the plot of Romeo and Juliet in two sentences. | Give me a two-sentence summary of Romeo and Juliet's plot.
            What's a good structure for a five-paragraph essay? | How should I structure a FIVE-paragraph essay?
            """)
    void treatsASpelledOutNumberAndItsDigitFormAsTheSameNumber(String promptA, String promptB) {
        assertThat(EntityNumericGuard.sameFingerprint(promptA, promptB)).isTrue();
    }

    @Test
    void doesNotTreatTheWordOneOnItsOwnAsANumberBecauseItIsUsuallyAPronounOrArticle() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Explain photosynthesis in one paragraph.", "Can you describe photosynthesis in a single paragraph?");

        assertThat(safe).isTrue();
    }

    @Test
    void treatsDigitGroupingSeparatorsAsPartOfTheSameNumber() {
        assertThat(EntityNumericGuard.sameFingerprint(
                "Account balance after the transfer is $4,200.", "Account balance after the transfer is $4200.")).isTrue();
        assertThat(EntityNumericGuard.sameFingerprint(
                "Account balance after the transfer is $4,200.", "Account balance after the transfer is $2,400.")).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Flight 227 departs from gate B12 at 6:45pm.      | Flight 227 departs from gate B12 at 6:45am.
            The meeting is scheduled for 3 PM on Tuesday.    | The meeting is scheduled for 3 AM on Tuesday.
            The store opens at 9 a.m. on Tuesday.            | The store opens at 9 p.m. on Tuesday.
            The API rate limit resets every 60 minutes.      | The API rate limit resets every 60 seconds.
            The package weighs 2 kilograms.                  | The package weighs 2 pounds.
            The bridge spans 3 kilometers.                   | The bridge spans 3 miles.
            Plan a two-week trip.                            | Plan a two-day trip.
            Revenue grew by 12%.                             | Revenue grew by 12 points.
            The disk holds 512 GB.                           | The disk holds 512 MB.
            The invoice total is $4,200.                     | The invoice total is €4,200.
            """)
    void flagsAChangedTimeOfDayUnitOrCurrencyOnTheSameNumberAsNotSafeToServeFromCache(String promptA, String promptB) {
        assertThat(EntityNumericGuard.sameFingerprint(promptA, promptB)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The API rate limit resets every 60 minutes.      | The API rate limit resets every 60 mins.
            The API rate limit resets every 60 minutes.      | Every 60 minutes, the API rate limit resets.
            The package weighs 2 kilograms.                  | The package weighs 2 kg.
            Revenue grew by 12%.                             | Revenue grew by 12 percent.
            The meeting is at 3 PM.                          | The meeting is at 3pm.
            The meeting is at 3 PM.                          | The meeting is at 3 p.m.
            I am leaving at 6pm.                             | I'm leaving at 6pm.
            """)
    void treatsTheSameUnitOrTimeOfDayWrittenDifferentlyAsTheSame(String promptA, String promptB) {
        assertThat(EntityNumericGuard.sameFingerprint(promptA, promptB)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The library is open until 8 PM.                  | The library is closed until 8 PM.
            The store closes at 9 PM on weekdays.            | The store closes at 9 PM on weekends.
            Interest rates rose by 0.25 percent.             | Interest rates fell by 0.25 percent.
            Revenue increased by 7% this quarter.            | Revenue decreased by 7% this quarter.
            Is aspirin safe to take during pregnancy?        | Is aspirin unsafe to take during pregnancy?
            Take the tablet before meals.                    | Take the tablet after meals.
            The password needs at least 8 characters.        | The password needs at most 8 characters.
            Which plan has the higher monthly fee?           | Which plan has the lower monthly fee?
            Is the feature flag enabled in staging?          | Is the feature flag disabled in staging?
            Did the deploy pass its health checks?           | Did the deploy fail its health checks?
            Should I buy shares this week?                   | Should I sell shares this week?
            Sort the results in ascending order.             | Sort the results in descending order.
            Is the API public?                               | Is the API private?
            Is this statement true?                          | Is this statement false?
            """)
    void flagsPromptsThatTakeOppositeSidesOfACommonOppositionAsNotSafeToServeFromCache(String promptA, String promptB) {
        assertThat(EntityNumericGuard.sameFingerprint(promptA, promptB)).isFalse();
    }

    @Test
    void treatsDifferentInflectionsOfTheSameSideOfAnOppositionAsTheSame() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Why did revenue increase this quarter?", "Why has revenue increased this quarter?");

        assertThat(safe).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Write a Python function that reverses a string.  | Give me a Python function to reverse a given string.
            Write a haiku about autumn.                       | Can you compose a haiku on the theme of autumn?
            Draft a brief LinkedIn bio for an engineer.       | Write a short LinkedIn bio for an engineer.
            Recommend a beginner-friendly book on cooking.    | What's a good cooking book for beginners?
            At sea level, what temperature does water boil at? | What's the boiling point of water at sea level?
            In Python, how do lists differ from tuples?       | What's the difference between a list and a tuple in Python?
            Our team has 8 engineers.                         | The team has 8 engineers.
            Isn't it a good idea to back up nightly?          | Is it not a good idea to back up nightly?
            """)
    void doesNotTreatSentenceInitialImperativesPrepositionsOrDeterminersAsEntities(String promptA, String promptB) {
        assertThat(EntityNumericGuard.sameFingerprint(promptA, promptB)).isTrue();
    }

    @Test
    void stillTreatsASentenceInitialProperNounAsAnEntity() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "Paris is the capital of France.", "Berlin is the capital of France.");

        assertThat(safe).isFalse();
    }

    @Test
    void producesFingerprintTokensThatSurviveCommaSeparatedStorage() {
        assertThat(EntityNumericGuard.fingerprint("The contract expires on December 1, 2026."))
                .isNotEmpty()
                .noneMatch(token -> token.contains(","));
    }

    @Test
    void treatsANamedMonthDateWithOrWithoutTheYearCommaAsTheSameDate() {
        boolean safe = EntityNumericGuard.sameFingerprint(
                "The contract expires on December 1, 2026.", "When does the contract expire? December 1 2026.");

        assertThat(safe).isTrue();
    }
}
