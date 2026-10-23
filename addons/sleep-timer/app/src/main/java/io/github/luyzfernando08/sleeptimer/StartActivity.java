package io.github.luyzfernando08.sleeptimer;

import android.app.Activity;
import android.os.Bundle;

/**
 * Starts the timer for "minutes" (1 to 240), or cancels it with 0, with no screen of its own: the cleanyourtv
 * launcher's Night mode uses it. Setting a timer to put the TV to sleep is what the remote's power key does anyway.
 */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int minutes = getIntent().getIntExtra("minutes", -1);
        if (minutes == 0) Timer.cancel(this);
        else if (minutes > 0 && minutes <= 240) Timer.start(this, minutes);
        finish();
    }
}
