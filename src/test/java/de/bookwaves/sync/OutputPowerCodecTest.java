package de.bookwaves.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputPowerCodecTest {

    @Test
    @DisplayName("the current generation encodes ten steps from 0.1 W to 1.0 W")
    void newGenSteps() {
        assertEquals(0x10, OutputPowerCodec.NEW_GEN.code(0.1));
        assertEquals(0x14, OutputPowerCodec.NEW_GEN.code(0.5));
        assertEquals(0x19, OutputPowerCodec.NEW_GEN.code(1.0));
    }

    @Test
    @DisplayName("the older generation stops at 0.5 W, which is its full power")
    void oldGenStopsAtFullPower() {
        assertEquals(0x14, OutputPowerCodec.OLD_GEN.code(0.5));
        assertFalse(OutputPowerCodec.OLD_GEN.isSupported(0.6));
        assertFalse(OutputPowerCodec.OLD_GEN.isSupported(1.0));
    }

    @Test
    @DisplayName("the older generation has a low step the current generation does not")
    void oldGenHasAnExtraLowStep() {
        assertEquals(0x08, OutputPowerCodec.OLD_GEN.code(0.05));
        assertFalse(OutputPowerCodec.NEW_GEN.isSupported(0.05));
    }

    @Test
    @DisplayName("the codes the generations share mean the same power")
    void sharedCodesAgree() {
        for (double power : new double[] {0.1, 0.2, 0.3, 0.4, 0.5}) {
            assertEquals(OutputPowerCodec.NEW_GEN.code(power), OutputPowerCodec.OLD_GEN.code(power),
                "code for " + power + " W differs between generations");
        }
    }

    @Test
    @DisplayName("an unsupported power is rejected and the supported values are listed")
    void unsupportedPowerIsRejected() {
        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> OutputPowerCodec.OLD_GEN.code(1.0));

        assertTrue(thrown.getMessage().contains("1.0"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("0.5"), thrown.getMessage());
    }

    @Test
    @DisplayName("there is no code for switching an antenna off")
    void zeroIsNotAValidPower() {
        assertFalse(OutputPowerCodec.NEW_GEN.isSupported(0.0));
        assertFalse(OutputPowerCodec.OLD_GEN.isSupported(0.0));
        assertThrows(IllegalArgumentException.class, () -> OutputPowerCodec.NEW_GEN.code(0.0));
    }

    @Test
    @DisplayName("a power between two steps is not silently rounded")
    void betweenStepsIsRejected() {
        assertFalse(OutputPowerCodec.NEW_GEN.isSupported(0.15));
        assertThrows(IllegalArgumentException.class, () -> OutputPowerCodec.NEW_GEN.code(0.15));
    }

    @Test
    @DisplayName("each generation reports its own supported values in ascending order")
    void supportedValuesAreOrdered() {
        assertEquals(0.1, OutputPowerCodec.NEW_GEN.supportedValues().first());
        assertEquals(1.0, OutputPowerCodec.NEW_GEN.supportedValues().last());
        assertEquals(0.05, OutputPowerCodec.OLD_GEN.supportedValues().first());
        assertEquals(0.5, OutputPowerCodec.OLD_GEN.supportedValues().last());
    }
}
