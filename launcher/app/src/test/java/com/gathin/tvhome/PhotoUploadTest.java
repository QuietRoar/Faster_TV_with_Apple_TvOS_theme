package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The phone upload's checks: a safe file name from whatever the phone sends, and only photos let in. */
public class PhotoUploadTest {
    @Test
    public void keepsAPlainNameWithoutItsExtension() {
        assertEquals("IMG_2041", PhotoUpload.safeName("IMG_2041.HEIC"));
    }

    @Test
    public void dropsFoldersAndUnsafeCharacters() {
        assertEquals("passwd", PhotoUpload.safeName("../../etc/passwd"));
        assertEquals("a_b_c", PhotoUpload.safeName("a:b*c.jpg"));
    }

    @Test
    public void givesAnEmptyOrHiddenNameADefault() {
        assertEquals("Photo", PhotoUpload.safeName(""));
        assertEquals("Photo", PhotoUpload.safeName(null));
        assertEquals("Photo", PhotoUpload.safeName(".jpg"));
    }

    @Test
    public void recognisesJpegPngAndWebp() {
        assertEquals("jpg", PhotoUpload.imageType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, 4));
        assertEquals("png", PhotoUpload.imageType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 8));
        assertEquals("webp", PhotoUpload.imageType(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, 12));
    }

    @Test
    public void refusesAnythingElse() {
        assertNull(PhotoUpload.imageType("<html>".getBytes(), 6));
        assertNull(PhotoUpload.imageType(new byte[]{(byte) 0xFF, (byte) 0xD8}, 2));
    }
}
