package datura.areamusic.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PcmMathTest {
    @Test
    void saturatesInsteadOfWrapping() {
        assertEquals(Short.MAX_VALUE, PcmMath.saturate(40_000));
        assertEquals(Short.MIN_VALUE, PcmMath.saturate(-40_000));
        assertEquals((short) 1234, PcmMath.saturate(1234));
    }

    @Test
    void readsAndWritesSignedLittleEndianSamples() {
        byte[] bytes = {(byte) 0x34, (byte) 0x12, (byte) 0x00, (byte) 0x80};

        assertEquals((short) 0x1234, PcmMath.readLittleEndian(bytes, 0));
        assertEquals(Short.MIN_VALUE, PcmMath.readLittleEndian(bytes, 2));

        byte[] output = new byte[4];
        PcmMath.writeLittleEndian(output, 0, 40_000);
        PcmMath.writeLittleEndian(output, 2, -2);
        assertArrayEquals(new byte[]{(byte) 0xff, 0x7f, (byte) 0xfe, (byte) 0xff}, output);
    }

    @Test
    void scalesSamplesUsingTheCurrentGain() {
        assertEquals(5000, PcmMath.scale((short) 10_000, 0.5f));
        assertEquals(-2500, PcmMath.scale((short) -10_000, 0.25f));
    }
}
