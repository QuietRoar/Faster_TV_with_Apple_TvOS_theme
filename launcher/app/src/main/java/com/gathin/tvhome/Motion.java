package com.gathin.tvhome;

import android.view.KeyEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    /**
     * tvos_style's press: OK held down squeezes the focused control a little (about 6 dp at most, so a wide row
     * barely moves and a tile goes to 96%), and letting go springs it back to the size its focus gave it.
     */
    static void press(KeyEvent event, View view) {
        int code = event.getKeyCode();
        if (view == null || (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER)) return;
        Object rest = view.getTag(R.id.press_scale);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0 || rest != null || view.getWidth() == 0) return;
            float base = view.getScaleX();
            view.setTag(R.id.press_scale, base);
            float f = Math.max(0.96f, 1f - 12f * view.getResources().getDisplayMetrics().density / view.getWidth());
            view.animate().scaleX(base * f).scaleY(base * f).setDuration(80).setInterpolator(null).start();
        } else if (event.getAction() == KeyEvent.ACTION_UP && rest != null) {
            view.setTag(R.id.press_scale, null);
            view.animate().scaleX((Float) rest).scaleY((Float) rest).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
