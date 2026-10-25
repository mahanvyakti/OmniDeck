package com.mahanapps.omnideck.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class QrCodeTest {

    @Test
    public void testEncodeText() {
        QrCode qr = QrCode.encodeText("http://192.168.1.100:8080", QrCode.Ecc.MEDIUM);
        assertNotNull(qr);
        assertTrue(qr.size >= 21); // Version 1 is 21x21, higher versions larger

        // Check finder patterns (top-left 7x7)
        assertTrue(qr.getModule(0, 0));
        assertTrue(qr.getModule(6, 0));
        assertTrue(qr.getModule(0, 6));
        assertTrue(qr.getModule(6, 6));
    }
}
