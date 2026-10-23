package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * tvos_style's Clear background apps: ends the background processes of the apps you open (the ones with a tile),
 * so the next one starts on free memory. Android lets an app do this much and no more: it cannot force-stop
 * another app, and a process that is in front or playing is left alone by Android itself. It never touches this
 * launcher, the keyboard, enabled accessibility helpers (remote buttons, the sleep timer), the screensaver,
 * Google's services, the Play Store or Android's own parts, which have no tile anyway.
 */
final class Boost {
    private Boost() { }

    /** What a clear did: apps cleared, memory available before and after, total memory, in bytes. */
    static final class Result {
        int apps;
        long before, after, total;
    }

    static Result clear(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Result result = new Result();
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memory);
        result.before = memory.availMem;
        result.total = memory.totalMem;
        Set<String> keep = protectedPackages(ctx);
        Set<String> apps = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found = ctx.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            for (ResolveInfo r : found) apps.add(r.activityInfo.packageName);
        }
        apps.removeAll(keep);
        for (String pkg : apps) {
            am.killBackgroundProcesses(pkg);
            result.apps++;
        }
        // Give the system a moment to take the processes down before measuring.
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        am.getMemoryInfo(memory);
        result.after = memory.availMem;
        return result;
    }

    private static Set<String> protectedPackages(Context ctx) {
        Set<String> keep = new HashSet<>();
        keep.add(ctx.getPackageName());
        keep.add("com.google.android.gms");
        keep.add("com.google.android.gsf");
        keep.add("com.android.vending");          // Play Store: installs and updates
        keep.add("com.google.android.katniss");   // the assistant behind the remote's mic
        keep.add("io.github.luyzfernando08.sleeptimer");
        keep.add("com.gathin.photodream");
        String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        addOwner(keep, ime);
        String services = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services != null) for (String service : TextUtils.split(services, ":")) addOwner(keep, service);
        addOwner(keep, Settings.Secure.getString(ctx.getContentResolver(), "screensaver_components"));
        return keep;
    }

    private static void addOwner(Set<String> keep, String components) {
        if (TextUtils.isEmpty(components)) return;
        for (String one : TextUtils.split(components, ",")) {
            ComponentName name = ComponentName.unflattenFromString(one);
            if (name != null) keep.add(name.getPackageName());
        }
    }
}
