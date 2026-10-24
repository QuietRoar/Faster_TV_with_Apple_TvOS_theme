package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Apple TV keyboard's keys and how the remote moves over them, apart from any drawing so it can be tested on
 * its own. Two rows, as tvOS lays out its keyboard: the tabs (abc, ABC, 123, #+= and Done) over one long line of
 * characters, the whole alphabet in order, ending in Space and Delete. Email and web fields get "@" and ".com"
 * before Space.
 */
final class KeyboardLayout {
    static final int TABS = 0, KEYS = 1;
    static final int LOWER = 0, UPPER = 1, NUMBERS = 2, SYMBOLS = 3;
    static final String SPACE = "space", DELETE = "delete", DONE = "done";
    static final String[] TAB_KEYS = {"abc", "ABC", "123", "#+=", DONE};

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String NUMBER_KEYS = "1234567890-/:;()&@\".,?!'";
    private static final String SYMBOL_KEYS = "[]{}#%^*+=_\\|~<>$€£¥•.,?!'";

    private int mode = LOWER;
    private boolean webOrEmail;
    private int row = KEYS, column;

    int mode() {
        return mode;
    }

    int row() {
        return row;
    }

    int column() {
        return column;
    }

    /** For an email or web address field: "@" and ".com" join the line. */
    void setWebOrEmail(boolean on) {
        webOrEmail = on;
        column = Math.min(column, row(row).size() - 1);
    }

    /** A new field starts on the letters, small or capital, on the first key. */
    void reset(boolean capitalise) {
        mode = capitalise ? UPPER : LOWER;
        row = KEYS;
        column = 0;
    }

    void setMode(int m) {
        mode = m;
        column = Math.min(column, row(row).size() - 1);
    }

    /** The line of character keys in the current mode, then "@" and ".com" when wanted, Space and Delete. */
    List<String> keys() {
        String chars = mode == NUMBERS ? NUMBER_KEYS : mode == SYMBOLS ? SYMBOL_KEYS
                : mode == UPPER ? LETTERS.toUpperCase(java.util.Locale.ROOT) : LETTERS;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) out.add(String.valueOf(chars.charAt(i)));
        if (webOrEmail) {
            out.add("@");
            out.add(".com");
        }
        out.add(SPACE);
        out.add(DELETE);
        return out;
    }

    List<String> row(int r) {
        return r == TABS ? Arrays.asList(TAB_KEYS) : keys();
    }

    String selected() {
        return row(row).get(column);
    }

    /** Left and Right along the row, stopping at its ends; false when nothing moved. */
    boolean moveHorizontally(int delta) {
        int next = column + delta;
        if (next < 0 || next >= row(row).size()) return false;
        column = next;
        return true;
    }

    /**
     * Up to the tabs and down to the keys. The column follows the screen: fraction is where the selection sits
     * across the width (0 to 1), and the key in the other row whose centre (otherRowCentres) is nearest is picked.
     */
    boolean moveVertically(int delta, float fraction, float[] otherRowCentres) {
        int next = row + delta;
        if (next < TABS || next > KEYS) return false;
        row = next;
        int best = 0;
        for (int i = 1; i < otherRowCentres.length; i++) {
            if (Math.abs(otherRowCentres[i] - fraction) < Math.abs(otherRowCentres[best] - fraction)) best = i;
        }
        column = Math.min(best, row(row).size() - 1);
        return true;
    }

    /** OK on a tab: the mode it names; -1 for Done. */
    static int tabMode(String tab) {
        switch (tab) {
            case "abc": return LOWER;
            case "ABC": return UPPER;
            case "123": return NUMBERS;
            case "#+=": return SYMBOLS;
            default: return -1;
        }
    }
}
