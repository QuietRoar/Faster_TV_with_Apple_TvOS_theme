package com.gathin.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.Locale;

/**
 * AirPods (airpods_popup): while Home is on screen, listens for the Bluetooth LE message AirPods send while their
 * case is open (Apple's "proximity pairing" message, type 0x07 under Apple's company id 0x004C). When it is heard
 * close by and AirPods paired with this TV are not connected, the host shows the connect card, with the model and
 * the batteries the message carries. When the message stops (the case was closed, or they went out of range) for
 * GONE_MS, or the AirPods connect, the host is told to take the card away. The scan runs only between start() and
 * stop() (Home's onResume and onPause), never in the background, and not at all when no AirPods are paired.
 *
 * The message carries no address (Apple sends it from a random one), so it cannot say which AirPods were opened:
 * the first paired device named AirPods that is not connected is offered.
 */
final class AirPodsWatch {
    private static final String TAG = "AirPodsWatch";
    static final int APPLE_COMPANY_ID = 0x004C;
    static final int PROXIMITY_PAIRING = 0x07;
    /** Closer than this (dBm) counts as "opened next to the TV"; AirPods across the room are ignored. */
    static final int NEAR_RSSI = -80;
    /** Restarting scans only: no message for this long (two restarts and more) means the case was closed. */
    static final long GONE_MS = 26_000L;
    /** After "Not now" or the close button, the same AirPods are not asked about again for this long. */
    static final long QUIET_MS = 3 * 60_000L;
    private static final long CHECK_MS = 1_000L;
    /** How long the one-off profile connection is kept for the connect request to go through. */
    private static final long PROXY_HOLD_MS = 10_000L;
    /** A battery nibble of 15 means "not known" (the bud is not out, or the case is closed). */
    static final int UNKNOWN = -1;

    /** What one message says: the model and the batteries in percent (UNKNOWN when not given). */
    static final class Info {
        final String model;
        final int left, right, box;
        final boolean leftCharging, rightCharging, boxCharging;

        Info(String model, int left, int right, int box, boolean leftCharging, boolean rightCharging, boolean boxCharging) {
            this.model = model;
            this.left = left;
            this.right = right;
            this.box = box;
            this.leftCharging = leftCharging;
            this.rightCharging = rightCharging;
            this.boxCharging = boxCharging;
        }
    }

    interface Host {
        /** Show (or update) the card: AirPods paired with this TV are open close by and not connected. */
        void onAirPodsNearby(BluetoothDevice device, Info info);

        /** The case was closed, they went away, or they connected: take the card away. */
        void onAirPodsGone();

        /** Every case message heard, connected or not: the batteries it carries, for a card already showing. */
        void onAirPodsBattery(Info info);
    }

    private final Context ctx;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private long quietUntil;
    private long lastHeard;
    private BluetoothDevice showing;

    AirPodsWatch(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            byte[] data = record == null ? null : record.getManufacturerSpecificData(APPLE_COMPANY_ID);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "callback=" + type + " from=" + result.getDevice().getAddress() + " apple type="
                        + (data != null && data.length > 0 ? data[0] & 0xFF : -1)
                        + " rssi=" + result.getRssi());
            }
            if (type == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                main.post(AirPodsWatch.this::lost);   // the case was closed
                return;
            }
            if (!isCaseOpen(data, result.getRssi())) return;
            Info info = parse(data);
            main.post(() -> heard(info));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.i(TAG, "scan failed: " + errorCode + (lostMode ? ", trying plain filtered scans" : ""));
            scanner = null;
            if (lostMode) {
                plainOnly = true;
                main.post(AirPodsWatch.this::start);
            }
        }
    };

    /** Apple's proximity-pairing message, heard close by. */
    static boolean isCaseOpen(byte[] appleData, int rssi) {
        return appleData != null && appleData.length >= 8 && appleData[0] == PROXIMITY_PAIRING && rssi >= NEAR_RSSI;
    }

    /**
     * Reads the message: [0] type 0x07, [1] length, [2] prefix, [3..4] model, [5] status, [6] the buds' batteries
     * (a nibble each, in tens of percent), [7] charging flags (high nibble) and the case battery (low nibble).
     * Status bit 0x20 says which bud's battery comes first.
     */
    static Info parse(byte[] d) {
        int model = ((d[3] & 0xFF) << 8) | (d[4] & 0xFF);
        boolean flipped = (d[5] & 0x20) == 0;
        int high = (d[6] >> 4) & 0x0F, low = d[6] & 0x0F;
        int box = d[7] & 0x0F, charging = (d[7] >> 4) & 0x0F;
        int left = flipped ? high : low, right = flipped ? low : high;
        boolean leftCharging = (charging & (flipped ? 0x02 : 0x01)) != 0;
        boolean rightCharging = (charging & (flipped ? 0x01 : 0x02)) != 0;
        return new Info(modelName(model), percent(left), percent(right), percent(box),
                leftCharging, rightCharging, (charging & 0x04) != 0);
    }

    static int percent(int nibble) {
        return nibble >= 0 && nibble <= 10 ? nibble * 10 : UNKNOWN;
    }

    /** The model's name, from the two model bytes. */
    static String modelName(int model) {
        switch (model) {
            case 0x0220: return "AirPods";
            case 0x0F20: return "AirPods";
            case 0x1320: return "AirPods";
            case 0x1920: return "AirPods 4";
            case 0x1B20: return "AirPods 4";
            case 0x0E20: return "AirPods Pro";
            case 0x1420: return "AirPods Pro";
            case 0x2420: return "AirPods Pro";
            case 0x0A20: return "AirPods Max";
            case 0x1F20: return "AirPods Max";
            default: return "AirPods";
        }
    }

    /** A paired device's name says it is AirPods (or Beats, which send the same message). */
    static boolean isAirPodsName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("airpods") || n.contains("beats");
    }

    /** Starts listening, if Bluetooth is on and AirPods are paired. Safe to call again. */
    void start() {
        if (scanner != null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            // Also while they are connected: their messages carry the batteries the Connected card shows.
            if (adapter == null || !adapter.isEnabled() || !anyPaired(adapter)) return;
            BluetoothLeScanner le = adapter.getBluetoothLeScanner();
            if (le == null) return;
            // The chip's own filter for Apple's case-open message: without it this TV (Amlogic, Android 9) passes on
            // only a trickle of advertisements and the message is missed. Filtered, the chip reports a sender once,
            // so where it can, it is asked for "found" and "lost": lost comes when the case closes and the message
            // stops. Where it cannot, the scan restarts every RESTART_MS so the message is reported again.
            ScanFilter apple = new ScanFilter.Builder()
                    .setManufacturerData(APPLE_COMPANY_ID, new byte[]{PROXIMITY_PAIRING}, new byte[]{(byte) 0xFF})
                    .build();
            lostMode = !plainOnly && adapter.isOffloadedFilteringSupported();
            ScanSettings.Builder settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED);
            if (lostMode) {
                settings.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT);
            }
            // Diagnosis only (setprop log.tag.AirPodsWatch VERBOSE): every advertisement, no filter, full duty.
            boolean raw = Log.isLoggable(TAG, Log.VERBOSE);
            if (raw) {
                lostMode = false;
                le.startScan(Collections.<ScanFilter>emptyList(), new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            } else {
                le.startScan(Collections.singletonList(apple), settings.build(), callback);
            }
            scanner = le;
            Log.i(TAG, "listening for AirPods" + (lostMode ? " (found and lost)" : " (restarting)"));
            if (!lostMode) main.postDelayed(restart, RESTART_MS);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
            Log.i(TAG, "cannot listen for AirPods: " + e.getMessage());
            scanner = null;
        }
    }

    /** Android allows 5 scan starts in 30 s per app; this chip also needs time between them to find anything. */
    private static final long RESTART_MS = 12_000L;
    /** The chip reports found and lost (the case closing) itself; else the scan restarts and time decides. */
    private boolean lostMode;
    /** Found-and-lost was refused once: plain filtered scans from then on. */
    private boolean plainOnly;

    private final Runnable restart = () -> {
        halt();
        start();
    };

    /** Stops listening (Home is going away); a card on screen goes too. */
    void stop() {
        main.removeCallbacks(check);
        if (showing != null) gone();
        halt();
    }

    private void halt() {
        main.removeCallbacks(restart);
        if (scanner == null) return;
        try {
            scanner.stopScan(callback);
        } catch (SecurityException | IllegalStateException e) {
            Log.i(TAG, "stop scan: " + e.getMessage());
        }
        scanner = null;
    }

    /** "Not now" or the close button: stay quiet for a while. */
    void dismissed() {
        showing = null;
        main.removeCallbacks(check);
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS;
    }

    /** The chip lost the case-open message: the case was closed (or they left). */
    private void lost() {
        if (showing != null) gone();
    }

    private void heard(Info info) {
        host.onAirPodsBattery(info);
        long now = SystemClock.elapsedRealtime();
        lastHeard = now;
        if (now < quietUntil) return;
        BluetoothDevice device = showing;
        if (device == null) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            try {
                device = adapter == null ? null : pairedAirPods(adapter);
            } catch (SecurityException e) {
                return;
            }
            if (device == null) return;
            showing = device;
            main.postDelayed(check, CHECK_MS);
        }
        host.onAirPodsNearby(device, info);
    }

    /** While the card shows: gone once the message stops or the AirPods connect. */
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (showing == null) return;
            boolean silent = !lostMode && SystemClock.elapsedRealtime() - lastHeard > GONE_MS;
            if (silent || connected(showing)) {
                gone();
                return;
            }
            main.postDelayed(this, CHECK_MS);
        }
    };

    private void gone() {
        showing = null;
        main.removeCallbacks(check);
        host.onAirPodsGone();
    }

    private static boolean anyPaired(BluetoothAdapter adapter) {
        for (BluetoothDevice d : adapter.getBondedDevices()) if (isAirPodsName(d.getName())) return true;
        return false;
    }

    /** The first paired AirPods that is not connected now, or null. */
    private static BluetoothDevice pairedAirPods(BluetoothAdapter adapter) {
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (isAirPodsName(d.getName()) && !connected(d)) return d;
        }
        return null;
    }

    static boolean connected(BluetoothDevice device) {
        try {
            return Boolean.TRUE.equals(BluetoothDevice.class.getMethod("isConnected").invoke(device));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Connects the AirPods for audio: asks A2DP and the headset profile to connect them (hidden connect(), which
     * BLUETOOTH_ADMIN allows up to Android 9; newer Android may refuse it, then fallback opens Bluetooth settings).
     * The profile connections are closed again after PROXY_HOLD_MS.
     */
    void connect(BluetoothDevice device, Runnable fallback) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            fallback.run();
            return;
        }
        final boolean[] asked = {false};
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    Object ok = proxy.getClass().getMethod("connect", BluetoothDevice.class).invoke(proxy, device);
                    asked[0] |= Boolean.TRUE.equals(ok);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Log.i(TAG, "connect through profile " + profile + " refused: " + e);
                }
                main.postDelayed(() -> adapter.closeProfileProxy(profile, proxy), PROXY_HOLD_MS);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                // Nothing held: the proxy is closed on its own timer.
            }
        };
        try {
            adapter.getProfileProxy(ctx, listener, BluetoothProfile.A2DP);
            adapter.getProfileProxy(ctx, listener, BluetoothProfile.HEADSET);
        } catch (SecurityException | IllegalStateException e) {
            fallback.run();
            return;
        }
        // Neither profile took the request: let the owner do it in the TV's settings.
        main.postDelayed(() -> {
            if (!asked[0] && !connected(device)) fallback.run();
        }, PROXY_HOLD_MS / 2);
    }
}
