package datura.areamusic.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FadeEnvelopeTest {
    @Test
    void advancesLinearlyByAudioFrames() {
        FadeEnvelope fade = new FadeEnvelope(0.0f);
        fade.fadeTo(1.0f, 1000, 1000);

        fade.advance(500);
        assertEquals(0.5f, fade.value(), 0.0001f);
        assertFalse(fade.isComplete());

        fade.advance(500);
        assertEquals(1.0f, fade.value(), 0.0001f);
        assertTrue(fade.isComplete());
    }

    @Test
    void retargetsFromTheCurrentValueAndClampsAtTheTarget() {
        FadeEnvelope fade = new FadeEnvelope(0.0f);
        fade.fadeTo(1.0f, 1000, 1000);
        fade.advance(250);

        fade.fadeTo(0.0f, 500, 1000);
        fade.advance(1000);

        assertEquals(0.0f, fade.value(), 0.0001f);
        assertTrue(fade.isComplete());
    }

    @Test
    void zeroDurationSnapsAndInvalidInputsAreRejected() {
        FadeEnvelope fade = new FadeEnvelope(0.25f);

        fade.fadeTo(0.75f, 0, 44_100);

        assertEquals(0.75f, fade.value(), 0.0001f);
        assertTrue(fade.isComplete());
        assertThrows(IllegalArgumentException.class, () -> fade.fadeTo(Float.NaN, 100, 44_100));
        assertThrows(IllegalArgumentException.class, () -> fade.fadeTo(1.0f, -1, 44_100));
        assertThrows(IllegalArgumentException.class, () -> fade.fadeTo(1.0f, 100, 0));
    }

    @Test
    void reportsTheExactFramesRemainingUntilCompletion() {
        FadeEnvelope fade = new FadeEnvelope(1.0f);
        assertEquals(0L, fade.framesUntilComplete());

        fade.fadeTo(0.0f, 1000, 1000);
        assertEquals(1000L, fade.framesUntilComplete());

        fade.advance(375);
        assertEquals(625L, fade.framesUntilComplete());

        fade.advance(10_000);
        assertEquals(0L, fade.framesUntilComplete());
    }
}
