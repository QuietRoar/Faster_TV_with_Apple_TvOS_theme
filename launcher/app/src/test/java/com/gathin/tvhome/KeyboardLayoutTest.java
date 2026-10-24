package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** The Apple TV keyboard's keys and the remote's moves over them. */
public class KeyboardLayoutTest {
    @Test
    public void lettersAreOneLineInOrderEndingInSpaceAndDelete() {
        KeyboardLayout k = new KeyboardLayout();
        List<String> keys = k.keys();
        assertEquals(28, keys.size());
        assertEquals("a", keys.get(0));
        assertEquals("z", keys.get(25));
        assertEquals(KeyboardLayout.SPACE, keys.get(26));
        assertEquals(KeyboardLayout.DELETE, keys.get(27));
    }

    @Test
    public void emailFieldsGetAtAndDotCom() {
        KeyboardLayout k = new KeyboardLayout();
        k.setWebOrEmail(true);
        List<String> keys = k.keys();
        assertEquals("@", keys.get(26));
        assertEquals(".com", keys.get(27));
        assertEquals(KeyboardLayout.SPACE, keys.get(28));
    }

    @Test
    public void capitalStartAndModes() {
        KeyboardLayout k = new KeyboardLayout();
        k.reset(true);
        assertEquals("A", k.selected());
        k.setMode(KeyboardLayout.NUMBERS);
        assertEquals("1", k.selected());
        assertEquals(KeyboardLayout.NUMBERS, KeyboardLayout.tabMode("123"));
        assertEquals(-1, KeyboardLayout.tabMode(KeyboardLayout.DONE));
    }

    @Test
    public void movingStopsAtTheEnds() {
        KeyboardLayout k = new KeyboardLayout();
        assertFalse(k.moveHorizontally(-1));
        assertTrue(k.moveHorizontally(1));
        assertEquals("b", k.selected());
    }

    @Test
    public void upGoesToTheNearestTabAndDownComesBackUnderIt() {
        KeyboardLayout k = new KeyboardLayout();
        // Tabs centred at 0.6 to 0.9 of the width; the selection at 0.88 lands on the last tab, Done.
        assertTrue(k.moveVertically(-1, 0.88f, new float[]{0.6f, 0.68f, 0.76f, 0.84f, 0.9f}));
        assertEquals(KeyboardLayout.TABS, k.row());
        assertEquals(KeyboardLayout.DONE, k.selected());
        assertFalse(k.moveVertically(-1, 0.9f, new float[]{0.5f}));
        float[] keyCentres = new float[28];
        for (int i = 0; i < 28; i++) keyCentres[i] = (i + 0.5f) / 28f;
        assertTrue(k.moveVertically(1, 0.9f, keyCentres));
        assertEquals(25, k.column());
    }
}
