package com.gathin.tvhome;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/**
 * An Apple TV style keyboard for the whole TV (Settings, Keyboard, picks it). The remote moves over two rows,
 * Left and Right along them and Up and Down between them; OK types the key, holding OK on Delete keeps deleting,
 * and Back puts the keyboard away. The field stays visible above it: the keyboard never takes the whole screen.
 * It is drawn by TvKeyboardView from KeyboardLayout and plays Home's own tick and tock when they are on.
 */
public class TvKeyboard extends InputMethodService {
    private final KeyboardLayout layout = new KeyboardLayout();
    private TvKeyboardView view;

    @Override
    public View onCreateInputView() {
        view = new TvKeyboardView(this);
        view.bind(layout);
        view.setAccent(Themes.accent(this));
        return view;
    }

    /** The app's own field stays on screen above the keyboard, as on Apple TV, rather than a full-screen copy. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int kind = info.inputType & InputType.TYPE_MASK_CLASS;
        layout.setWebOrEmail(kind == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));
        if (!restarting) {
            layout.reset((info.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0 && fieldText().length() == 0);
            if (kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE) layout.setMode(KeyboardLayout.NUMBERS);
        }
        if (view != null) {
            view.setAccent(Themes.accent(this));
            refresh();
        }
    }

    /** What the field holds now, for the line above the keys; a password shows as dots. */
    private CharSequence fieldText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        CharSequence text = et != null && et.text != null ? et.text : ic.getTextBeforeCursor(200, 0);
        if (text == null) return "";
        EditorInfo info = getCurrentInputEditorInfo();
        int variation = info == null ? 0 : info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean secret = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if (!secret) return text;
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < text.length(); i++) dots.append('•');
        return dots;
    }

    private void refresh() {
        EditorInfo info = getCurrentInputEditorInfo();
        view.setText(fieldText(), info != null && info.hintText != null ? info.hintText : "");
        view.invalidate();
    }

    @Override
    public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candStart, int candEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candStart, candEnd);
        if (view != null) refresh();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isInputViewShown() || view == null) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (layout.moveHorizontally(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) TvSounds.move();
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int delta = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                int other = layout.row() + delta;
                if (other >= KeyboardLayout.TABS && other <= KeyboardLayout.KEYS
                        && layout.moveVertically(delta, view.selectedFraction(), view.centres(other))) {
                    TvSounds.move();
                    view.invalidate();
                    return true;
                }
                // Up from the tabs leaves the keyboard for the app (its field, a list above it).
                return keyCode == KeyEvent.KEYCODE_DPAD_DOWN || super.onKeyDown(keyCode, event);
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Held OK repeats only Delete; any other key types once per press.
                if (event.getRepeatCount() == 0 || KeyboardLayout.DELETE.equals(layout.selected())) press();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isInputViewShown() && view != null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_UP && layout.row() == KeyboardLayout.KEYS)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** OK on the selected key: a tab changes the keys or finishes, the rest type. */
    private void press() {
        InputConnection ic = getCurrentInputConnection();
        String k = layout.selected();
        TvSounds.select();
        if (layout.row() == KeyboardLayout.TABS) {
            int mode = KeyboardLayout.tabMode(k);
            if (mode >= 0) {
                layout.setMode(mode);
                view.invalidate();
            } else {
                done(ic);
            }
            return;
        }
        if (ic == null) return;
        if (KeyboardLayout.DELETE.equals(k)) {
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) ic.commitText("", 1);
            else ic.deleteSurroundingText(1, 0);
        } else if (KeyboardLayout.SPACE.equals(k)) {
            ic.commitText(" ", 1);
        } else {
            ic.commitText(k, 1);
            // Like a phone: after the first capital of a sentence the letters go back to small.
            if (layout.mode() == KeyboardLayout.UPPER) layout.setMode(KeyboardLayout.LOWER);
        }
        refresh();
    }

    /** Done: the field's own action (Search, Go, Next) when it has one, then the keyboard goes away. */
    private void done(InputConnection ic) {
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (ic != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(action);
        } else if (ic != null) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
        requestHideSelf(0);
    }
}
