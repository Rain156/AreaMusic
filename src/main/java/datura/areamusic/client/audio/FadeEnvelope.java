package datura.areamusic.client.audio;

public final class FadeEnvelope {
    private float value;
    private float start;
    private float target;
    private long totalFrames;
    private long elapsedFrames;

    public FadeEnvelope(float initialValue) {
        validateGain(initialValue);
        value = initialValue;
        start = initialValue;
        target = initialValue;
    }

    public void fadeTo(float targetValue, int durationMs, int sampleRate) {
        validateGain(targetValue);
        if (durationMs < 0) {
            throw new IllegalArgumentException("Fade duration must not be negative");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }

        start = value;
        target = targetValue;
        elapsedFrames = 0L;
        if (durationMs == 0 || start == target) {
            value = target;
            totalFrames = 0L;
            return;
        }
        totalFrames = Math.max(1L, Math.round((double) durationMs * sampleRate / 1000.0d));
    }

    public void advance(int frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (totalFrames == 0L) {
            return;
        }
        elapsedFrames = Math.min(totalFrames, elapsedFrames + frames);
        float progress = (float) elapsedFrames / (float) totalFrames;
        value = start + (target - start) * progress;
        if (elapsedFrames == totalFrames) {
            value = target;
            totalFrames = 0L;
        }
    }

    public float value() {
        return value;
    }

    public float target() {
        return target;
    }

    public boolean isComplete() {
        return totalFrames == 0L;
    }

    private static void validateGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be finite and between 0 and 1");
        }
    }
}
