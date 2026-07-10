package datura.areamusic.client.audio;

public final class PcmMath {
    private PcmMath() {
    }

    public static short saturate(int sample) {
        if (sample > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (sample < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) sample;
    }

    public static short readLittleEndian(byte[] bytes, int offset) {
        int low = bytes[offset] & 0xff;
        int high = bytes[offset + 1];
        return (short) (low | high << 8);
    }

    public static void writeLittleEndian(byte[] bytes, int offset, int sample) {
        short saturated = saturate(sample);
        bytes[offset] = (byte) (saturated & 0xff);
        bytes[offset + 1] = (byte) (saturated >> 8 & 0xff);
    }

    public static int scale(short sample, float gain) {
        if (!Float.isFinite(gain)) {
            throw new IllegalArgumentException("Gain must be finite");
        }
        return Math.round(sample * gain);
    }
}
