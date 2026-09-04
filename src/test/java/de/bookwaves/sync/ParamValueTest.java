package de.bookwaves.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamValueTest {

    @Test
    @DisplayName("an unsigned byte survives narrowing to the SDK and back")
    void unsignedByteRoundTrips() {
        // 0xC0 is the notification operating mode. As a Java byte it is -64; the adapter
        // narrows it for the SDK and masks it back on the way in, so both ends see 192.
        ParamValue.Numeric mode = (ParamValue.Numeric) ParamValue.ofByte(0xC0);

        assertEquals(192L, mode.value());
        assertEquals((byte) -64, mode.asByte());
        assertEquals(mode, ParamValue.ofByte(mode.asByte() & 0xFF));
    }

    @Test
    @DisplayName("values of different types are never equal")
    void typesDoNotCompareEqual() {
        // The type is part of the value, so a type mismatch cannot masquerade as a
        // value difference.
        assertNotEquals(ParamValue.ofByte(1), ParamValue.ofLong(1));
        assertNotEquals(ParamValue.ofByte(1), ParamValue.bool(true));
        assertEquals(ParamValue.ofByte(1), ParamValue.ofByte(1));
        assertEquals(ParamValue.ofLong(60), ParamValue.ofLong(60));
    }

    @Test
    @DisplayName("a byte value outside the reader's range is rejected at construction")
    void byteRangeIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> ParamValue.ofByte(256));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.ofByte(-1));
    }

    @Test
    @DisplayName("descriptions read naturally in log messages")
    void describesValues() {
        assertEquals("0xC0", ParamValue.ofByte(0xC0).describe());
        assertEquals("60", ParamValue.ofLong(60).describe());
        assertEquals("true", ParamValue.bool(true).describe());
        assertEquals("\"10.0.0.5\"", ParamValue.text("10.0.0.5").describe());
    }

    @Test
    @DisplayName("an unrecognised mode is rejected and the supported modes are listed")
    void modeParsing() {
        assertEquals(ReaderMode.HOST, ReaderMode.parse("host"));
        assertEquals(ReaderMode.NOTIFICATION, ReaderMode.parse(" Notification "));

        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> ReaderMode.parse("polling"));
        assertTrue(thrown.getMessage().contains("host"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("notification"), thrown.getMessage());

        assertThrows(IllegalArgumentException.class, () -> ReaderMode.parse(null));
    }
}
