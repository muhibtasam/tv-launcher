package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.speech.RecognizerIntent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Control Center (the round Settings button's panel), moved out of HomeView as it was: its state, layout, keys,
 * AirPlay lookup and drawing. HomeView keeps its layer (ccLayer) and calls openCC, closeCC, ccKey, stepCC, drawCC and
 * findAirplay; what it borrows from HomeView (paints, glass, the drawer for Inputs) goes through `home`.
 */
final class ControlCenter {
    private final HomeView home;

    ControlCenter(HomeView home) {
        this.home = home;
    }

    // MARK: - Control Center (the round Settings button)

    /*
     * A glass panel over the home screen, laid out like the reference: the title; Wi-Fi and Bluetooth; AirPlay and Turn
     * off; the volume slider; a "Home screen" group (refresh, Home Settings, clear history); All settings. The screen
     * under it isn't touched (tab, grid, focus stay as they are); while it's open every key is its own; Back or Home
     * closes it and the focus is on the Settings button again, where it was.
     */
    // The tiles and their layout: ControlCenterNav (tested without Android).
    private static final int CC_WIFI = ControlCenterNav.WIFI, CC_BT = ControlCenterNav.BT, CC_AIRPLAY = ControlCenterNav.AIRPLAY,
            CC_OFF = ControlCenterNav.OFF, CC_VOLUME = ControlCenterNav.VOLUME, CC_REFRESH = ControlCenterNav.REFRESH,
            CC_HOME_SETTINGS = ControlCenterNav.HOME_SETTINGS, CC_CLEAR = ControlCenterNav.CLEAR, CC_ALL = ControlCenterNav.ALL,
            CC_COUNT = ControlCenterNav.COUNT;
    private static final float CC_PAD = ControlCenterNav.PAD;
    private final float[][] ccTiles = new float[CC_COUNT][4];   // {left, top, right, bottom}, for the arrows
    boolean ccOpen, ccOkDown, ccBackDown;
    private int ccIndex;
    private final Spring ccShow = new Spring(0.3f, 1f, 0);   // the panel's fade and scale (Explore's springs, faster)
    private final Spring[] ccFocus = new Spring[CC_COUNT];
    private final RectF[] ccRects = new RectF[CC_COUNT];
    private final RectF ccPanel = new RectF();
    private float ccSectionY;
    private int ccWifi = Radios.UNKNOWN, ccBt = Radios.UNKNOWN, ccVolume, ccVolumeMax = 1;
    private String ccAirplayPkg, ccAirplayName;
    private Intent ccAirplayIntent;          // the TV's own AirPlay screen (or receiver app), found once
    private boolean ccAirplayInput;   // the TV lists an AirPlay input (switched to like HDMI)
    private static volatile Object[] airplayFound;   // {Intent, name} or {null, null}: looked up once per process
    private Paint ccTitleIt, ccRing;
    private Paint ccTitle, ccName, ccSub, ccSection, ccPct;

    void openCC() {
        if (ccOpen) return;
        if (ccFocus[0] == null) for (int i = 0; i < CC_COUNT; i++) {
            ccFocus[i] = new Spring(0.24f, 0.9f, 0);
            ccRects[i] = new RectF();
        }
        ccOpen = true;
        ccIndex = CC_WIFI;
        for (int i = 0; i < CC_COUNT; i++) ccFocus[i].snap(i == ccIndex ? 1 : 0);
        ccOkDown = ccBackDown = false;
        readCCState();
        findAirplay();
        ccShow.target = 1;
        home.ccLayer.setVisibility(home.VISIBLE);
        home.ccLayer.invalidate();
        home.wake();
    }

    /** Closes it; `snap`: at once (an app is opening over it, or Home). The focus is still on the Settings button. */
    void closeCC(boolean snap) {
        if (!ccOpen && ccShow.value <= 0.001f) return;
        ccOpen = false;
        ccShow.target = 0;
        if (snap) {
            ccShow.snap(0);
            home.ccLayer.setVisibility(home.GONE);
        }
        home.retarget(snap);   // the focus under it, as it is now (its cached layers redrawn: no glass left from before)
        home.statusDirty = true;
        home.tilesDirty = true;
        home.heroDirty = true;
        home.gridDirty = true;
        home.wake();
    }

    /** Wi-Fi, Bluetooth (Radios: the TV's real state) and the volume (the media stream, as the remote's keys set it). */
    private void readCCState() {
        ccAirplayInput = airplayInputId() != null;
        Context c = home.getContext();
        ccWifi = Radios.wifi(c);
        ccBt = Radios.bluetooth(c);
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            ccVolumeMax = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            ccVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        }
    }

    /**
     * AirPlay: TVs that have it built in (TCL's Google TVs with AirPlay 2, among others) ship it as a system app or a
     * screen inside their settings, often with no launcher icon. So every installed package is looked at once (in the
     * background, cached for the app's life): an app named for AirPlay that can be launched, else an exported screen
     * whose name says AirPlay (a settings one first). Everything found is logged: adb logcat -s FreeMyTV | grep airplay
     */
    /** The lookup is done and found no AirPlay (no input, no screen, no app): its tile shows the TV's Inputs instead. */
    private boolean ccNoAirplay() {
        return airplayFound != null && !ccAirplayInput && ccAirplayIntent == null;
    }

    void findAirplay() {
        Object[] known = airplayFound;
        if (known != null) {
            ccAirplayIntent = (Intent) known[0];
            ccAirplayName = (String) known[1];
            ccAirplayPkg = ccAirplayIntent == null ? null : ccAirplayIntent.getPackage();
            return;
        }
        final Context c = home.getContext().getApplicationContext();
        home.LOADER.execute(() -> {
            Object[] found = lookUpAirplay(c);
            airplayFound = found;
            home.handler.post(() -> {
                ccAirplayIntent = (Intent) found[0];
                ccAirplayName = (String) found[1];
                ccAirplayPkg = ccAirplayIntent == null ? null : ccAirplayIntent.getPackage();
                home.ccLayer.invalidate();
            });
        });
    }

    private static boolean namesAirplay(String s) {
        String l = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
        return l.contains("airplay") || l.contains("air_play") || l.contains("air play") || l.contains("airscreen")
                || l.contains("airreceiver") || l.contains("homekit");
    }

    private static Object[] lookUpAirplay(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent best = null;
        String bestName = null;
        int bestRank = -1;
        List<String> seen = new ArrayList<>();
        List<android.content.pm.ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (RuntimeException e) {
            AppLog.w("airplay: can't list apps: " + e);
            return new Object[] {null, null};
        }
        for (android.content.pm.ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            boolean appIsAirplay = namesAirplay(pkg) || namesAirplay(label);
            // 1. The app itself, when it can be launched (its TV or phone launcher screen).
            if (appIsAirplay) {
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                // MediaTek's launcher entry is the black playback screen too: not an AirPlay screen to open.
                if (launch != null && launch.getComponent() != null
                        && launch.getComponent().getClassName().endsWith(".TVActivity")) launch = null;
                seen.add(pkg + " \"" + label + "\"" + (launch != null ? " (launchable)" : ""));
                if (launch != null && bestRank < 3) {
                    best = launch;
                    bestName = label;
                    bestRank = 3;
                }
            }
            // 2. Its screens: an exported one named for AirPlay (in a TV settings app, or in the AirPlay app).
            android.content.pm.ActivityInfo[] acts;
            try {
                acts = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities;
            } catch (Exception e) {
                continue;
            }
            if (acts == null) continue;
            for (android.content.pm.ActivityInfo act : acts) {
                if (!act.exported || !act.enabled || (!appIsAirplay && !namesAirplay(act.name))) continue;
                // MediaTek's TVActivity is the screen AirPlay video plays on (black until a phone sends something):
                // never the one to open from a tile.
                if (act.name.endsWith(".TVActivity")) continue;
                String n = act.name.toLowerCase(java.util.Locale.ROOT);
                int rank = n.contains("setting") ? 2 : n.contains("main") || n.contains("home") || n.contains("launch") ? 1 : 0;
                seen.add(pkg + "/" + act.name);
                if (rank > bestRank) {
                    best = new Intent().setClassName(pkg, act.name);
                    bestName = label;
                    bestRank = rank;
                }
            }
        }
        AppLog.w("airplay: " + (seen.isEmpty() ? "nothing named AirPlay on this TV" : String.join(", ", seen))
                + (best == null ? "" : " -> opens " + best.getComponent() + (best.getComponent() == null ? best.getPackage() : "")));
        if (best != null && best.getPackage() == null && best.getComponent() != null) best.setPackage(best.getComponent().getPackageName());
        return new Object[] {best, bestName};
    }

    /**
     * The TV's AirPlay input (TCL/MediaTek: "com.mediatek.AirplayAPK/.AirPlayTvInputService"), or null. On these TVs
     * AirPlay is a source like HDMI: the TV app (com.tcl.tv) shows its waiting screen once the source is switched to it.
     */
    private String airplayInputId() {
        TvInputManager tim = (TvInputManager) home.getContext().getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        try {
            for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
                String id = info.getId();
                if (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("airplay")) return id;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Switches the TV to its AirPlay input, as the console cards do for HDMI. False when there's none or it's refused. */
    private boolean openAirplayInput() {
        String id = airplayInputId();
        if (id == null) return false;
        Intent in = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            home.activity.startActivity(in);
            AppLog.w("airplay: switched to input " + id);
            return true;
        } catch (RuntimeException e) {
            AppLog.w("airplay: input " + id + " refused: " + e);
            return false;
        }
    }

    private void layoutCC() {
        float[] p = ControlCenterNav.layout(home.k, home.CONTENT_R, ccTiles);
        for (int i = 0; i < CC_COUNT; i++) ccRects[i].set(ccTiles[i][0], ccTiles[i][1], ccTiles[i][2], ccTiles[i][3]);
        ccSectionY = p[4];   // "LAUNCHER"
        ccPanel.set(p[0], p[1], p[2], p[3]);
    }

    /** One frame: the panel's fade and scale (a layer property, no redraw) and the focus (redrawn while it moves). */
    boolean stepCC(float dt) {
        if (ccFocus[0] == null) return false;
        boolean moving = ccShow.step(dt);
        float v = Ui.clamp(ccShow.value);
        if (moving || ccOpen) {
            home.ccLayer.setAlpha(v);
            float sc = 0.97f + 0.03f * v;
            home.ccLayer.setPivotX(ccPanel.centerX());
            home.ccLayer.setPivotY(ccPanel.top);
            home.ccLayer.setScaleX(sc);
            home.ccLayer.setScaleY(sc);
        }
        if (!ccOpen && v <= 0.002f && home.ccLayer.getVisibility() != home.GONE) home.ccLayer.setVisibility(home.GONE);
        boolean focus = false;
        for (int i = 0; i < CC_COUNT; i++) {
            ccFocus[i].target = ccOpen && i == ccIndex ? 1 : 0;
            focus |= ccFocus[i].step(dt);
        }
        if (focus) home.ccLayer.invalidate();
        return moving || focus;
    }

    boolean ccKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (!down) return true;
                int dx = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                if (ccIndex == CC_VOLUME) {   // on the slider: the volume itself
                    android.media.AudioManager am = (android.media.AudioManager) home.getContext().getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                dx > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
                        readCCState();
                        home.ccLayer.invalidate();
                    }
                    return true;
                }
                ccMove(dx, 0);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) ccMove(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) ccMove(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // On release, and only if pressed here (no double activation, nothing from a press made elsewhere).
                if (down) {
                    if (e.getRepeatCount() == 0) ccOkDown = true;
                } else if (ccOkDown && !e.isCanceled()) {
                    ccOkDown = false;
                    home.click.open();
                    ccActivate(ccIndex);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) ccBackDown = true;
                } else if (ccBackDown && !e.isCanceled()) {
                    ccBackDown = false;
                    closeCC(false);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                home.handler.postDelayed(() -> {   // the TV sets it; the slider follows
                    if (ccOpen) {
                        readCCState();
                        home.ccLayer.invalidate();
                    }
                }, 120);
                return false;
            default:
                return true;   // nothing else reaches the screen under it
        }
    }

    /** The nearest tile past the focused one's edge in that direction (as the grid moves); stays at the edges. */
    private void ccMove(int dx, int dy) {
        int best = ControlCenterNav.move(ccTiles, ccIndex, dx, dy);
        if (best < 0) return;
        ccIndex = best;
        home.click.move();
        home.wake();
    }

    private void ccActivate(int i) {
        switch (i) {
            case CC_WIFI:
                ccOpenItem(HomeApps.action(HomeApps.WIFI, HomeApps.TOP_WIFI, Ui.tr("Wi-Fi"), "wifi"));
                break;
            case CC_BT:
                ccOpenItem(HomeApps.action(HomeApps.BLUETOOTH, HomeApps.TOP_BLUETOOTH, Ui.tr("Bluetooth"), "bluetooth"));
                break;
            case CC_AIRPLAY:
                if (ccNoAirplay()) {   // this TV has no AirPlay: the tile is Inputs, the drawer's HDMI sources
                    closeCC(true);
                    home.openDrawer();
                    for (int d = 0; d < home.drawerItems.size(); d++) {
                        if (home.drawerItems.get(d).kind == HomeApps.INPUT) {
                            home.drawerIndex = d;
                            break;
                        }
                    }
                    home.retarget(false);
                    break;
                }
                if (openAirplayInput()) {
                    closeCC(true);
                } else if (ccAirplayIntent != null) {
                    try {
                        home.activity.startActivity(new Intent(ccAirplayIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        closeCC(true);
                    } catch (RuntimeException ex) {
                        // MediaTek's AirPlay screen asks for its own permission (declared in the manifest: granted only
                        // if it's a normal one). Refused: the same screen through the TV's ADB, as the shell (the
                        // debugging Free My TV mode already uses), which may hold it.
                        AppLog.w("airplay: couldn't open " + ccAirplayIntent + ": " + ex);
                        final android.content.ComponentName cn = ccAirplayIntent.getComponent();
                        final Context app = home.getContext().getApplicationContext();
                        if (cn == null) {
                            home.showToast(Ui.tr("Couldn't open AirPlay."));
                        } else {
                            closeCC(false);
                            home.LOADER.execute(() -> {
                                String out;
                                try (Adb adb = Adb.connect(app, 10_000, null)) {
                                    out = adb.shell("am start -n " + cn.flattenToShortString());
                                } catch (Exception adbError) {
                                    out = "Error: " + adbError.getMessage();
                                }
                                AppLog.w("airplay via adb: " + out.trim());
                                if (out.contains("Error") || out.contains("Exception")) {
                                    home.handler.post(() -> home.showToast(Ui.tr("The TV doesn't let apps open its AirPlay screen.")));
                                }
                            });
                        }
                    }
                } else if (airplayFound == null) {
                    home.showToast(Ui.tr("Looking for AirPlay…"));   // the first lookup is still running
                } else {
                    // TCL's AirPlay page lives inside the TV's Settings (no screen of its own): Settings, where it's
                    // listed, with a word on where to find it.
                    ccOpenItem(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Settings"), "settings"));
                    home.showToast(Ui.tr("AirPlay is in the TV's Settings."));
                }
                break;
            case CC_OFF: {
                // The TV's own sleep key, sent through its ADB (the debugging that Free My TV mode already uses).
                closeCC(false);
                final Context c = home.getContext().getApplicationContext();
                home.LOADER.execute(() -> {
                    try (Adb adb = Adb.connect(c, 10_000, null)) {
                        adb.shell("input keyevent KEYCODE_SLEEP");
                    } catch (Exception ex) {
                        AppLog.w("turn off: " + ex.getMessage());
                        home.handler.post(() -> home.showToast(Ui.tr("Couldn't turn off the screen: it needs Free My TV mode (USB debugging on).")));
                    }
                });
                break;
            }
            case CC_VOLUME: {   // OK on the slider: mute or unmute
                android.media.AudioManager am = (android.media.AudioManager) home.getContext().getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    readCCState();
                    home.ccLayer.invalidate();
                }
                break;
            }
            case CC_REFRESH:
                home.recs.refreshNow();
                home.showToast(Ui.tr("Getting new picks…"));
                closeCC(false);
                break;
            case CC_HOME_SETTINGS:
                ccOpenItem(HomeApps.settings());
                break;
            case CC_CLEAR:
                home.recs.clearHistory();
                home.showToast(Ui.tr("Watch history cleared."));
                break;
            default:
                ccOpenItem(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Settings"), "settings"));
        }
    }

    /** Opens a settings page or an app from a tile: the panel goes at once (the page opens over Home). */
    private void ccOpenItem(HomeApps.Item it) {
        RectF r = ccRects[ccIndex];
        boolean opened = HomeApps.open(home.activity, it, home, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (opened) closeCC(true);
        else home.showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    void drawCC(Canvas c) {
        if (ccFocus[0] == null) return;
        if (ccTitle == null) {
            ccTitle = Ui.text(Ui.displaySemibold, 42 * home.k, 0xFFF3EDE2);
            ccTitleIt = Ui.text(Ui.displayItalic, 42 * home.k, 0xFFF3EDE2);
            ccName = Ui.text(Ui.semibold, 25 * home.k, 0xFFFFFFFF);
            ccSub = Ui.text(Ui.medium, 19 * home.k, 0xB3FFFFFF);
            ccSection = Ui.text(Ui.semibold, 19 * home.k, 0x99FFFFFF);
            ccSection.setLetterSpacing(0.08f);
            ccPct = Ui.numbers(Ui.semibold, 24 * home.k, 0xFFFFFFFF);
            for (Paint p : new Paint[] {ccTitle, ccName, ccSub, ccSection, ccPct}) p.setColorFilter(home.IVORY_TEXT);
        }
        layoutCC();
        // The screen under it, dimmed (a little past the edges, so the opening scale never shows a border).
        home.fill.setShader(null);
        home.fill.setColor(0x59000000);
        c.drawRect(-60 * home.k, -60 * home.k, home.w + 60 * home.k, home.h + 60 * home.k, home.fill);
        // The panel: the app's liquid glass (as the round buttons and the weather pill), larger, with a soft shadow.
        float radius = 44 * home.k;
        home.tmp.set(ccPanel);
        home.tmp.offset(0, 14 * home.k);
        home.shadow.setColor(0x59000000);
        home.shadows.draw(c, home.tmp, radius, home.SHADOW_BLUR * home.k, home.shadow);
        home.fill.setShader(null);   // warm dark under the dock's glass (its fill, hairline and lit edge): readable on any art
        home.fill.setColor(0xFF161214);   // opaque: at 96% the clock and the cards' white titles showed through it
        c.drawRoundRect(ccPanel, radius, radius, home.fill);
        home.drawFlatGlass(c, ccPanel, radius);
        // No white wash or outline on top: the glass's own light-catching edge is its outline (glass over glass
        // looked like a transparent panel with a white border).
        float tb = ccPanel.top + CC_PAD * home.k + Ui.halfCap(ccTitle) * 2;   // "Control center": the launcher's serif headings
        c.drawText(Ui.tr("Control") + " ", ccPanel.left + CC_PAD * home.k, tb, ccTitle);
        c.drawText(Ui.tr("center"), ccPanel.left + CC_PAD * home.k + ccTitle.measureText(Ui.tr("Control") + " "), tb, ccTitleIt);
        // Wi-Fi and Bluetooth: their real state; the icon's circle lights up while it's on.
        String wifiSub = ccWifi == Radios.CONNECTED ? Ui.tr("Connected") : ccWifi == Radios.ON ? Ui.tr("Not connected")
                : ccWifi == Radios.OFF ? Ui.tr("Off") : Ui.tr("Settings");
        drawCCTile(c, CC_WIFI, ccWifi == Radios.OFF ? "wifi_off" : "wifi", Ui.tr("Wi-Fi"), wifiSub, true, ccWifi >= Radios.ON);
        String btSub = ccBt == Radios.ON ? Ui.tr("On") : ccBt == Radios.OFF ? Ui.tr("Off") : Ui.tr("Settings");
        drawCCTile(c, CC_BT, ccBt == Radios.OFF ? "bluetooth_disabled" : "bluetooth", Ui.tr("Bluetooth"), btSub, true, ccBt == Radios.ON);
        if (ccNoAirplay()) drawCCTile(c, CC_AIRPLAY, "hdmi", Ui.tr("Inputs"), Ui.tr("TV sources"), true, false);
        else drawCCTile(c, CC_AIRPLAY, "airplay", Ui.tr("AirPlay"), ccAirplayInput || ccAirplayIntent != null
                ? Ui.tr("Open AirPlay") : airplayFound == null ? Ui.tr("Looking…") : Ui.tr("In TV Settings"), true,
                false);   // an action like Turn off, not a switch: never drawn as "on"
        drawCCTile(c, CC_OFF, "power_settings_new", Ui.tr("Turn off"), Ui.tr("Screen off"), true, false);
        drawCCVolume(c);
        ccSection.setColor(0xFFFFB27A);
        c.drawText(Ui.tr("LAUNCHER"), ccPanel.left + CC_PAD * home.k + 4 * home.k, ccSectionY, ccSection);
        drawCCTile(c, CC_REFRESH, "refresh", Ui.tr("Refresh"), null, false, false);
        drawCCTile(c, CC_HOME_SETTINGS, "home", Ui.tr("Launcher"), null, false, false);
        drawCCTile(c, CC_CLEAR, "delete", Ui.tr("Clear history"), null, false, false);
        drawCCTile(c, CC_ALL, "settings", Ui.tr("TV settings"), null, false, false);
    }

    /**
     * A tile: glass a little lighter than the panel, an icon (in a round chip on the top two rows: white when the thing
     * is on, with a blue icon), the name and a line under it. Focused: it grows 3%, lighter, with a white rim.
     */
    private void drawCCTile(Canvas c, int i, String symbol, String name, String sub, boolean chip, boolean on) {
        float f = Ui.clamp(ccFocus[i].value), s = 1 + 0.03f * f;
        RectF base = ccRects[i];
        float cx = base.centerX(), cy = base.centerY();
        home.cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = home.cell.height() * (chip ? 0.32f : 0.3f);
        // A lighter layer of the panel's glass, lit from the top; its edge catches the light. Focused: brighter body
        // and a full, wider edge (with the 3% growth), clearly visible from the sofa.
        home.fill.setShader(null);
        home.fill.setColor(home.blend(0x0DF3EDE2, 0xFFF3EADB, f));
        c.drawRoundRect(home.cell, r, r, home.fill);
        home.border.setStrokeWidth(1 * home.k);
        home.border.setColor(Ui.alpha(0xFFF3EDE2, 0.12f * (1 - f)));
        c.drawRoundRect(home.cell, r, r, home.border);
        if (f > 0.01f) {   // the launcher's focus: ivory ring 4 px out
            if (ccRing == null) {
                ccRing = new Paint(Paint.ANTI_ALIAS_FLAG);
                ccRing.setStyle(Paint.Style.STROKE);
            }
            ccRing.setStrokeWidth(3 * home.k);
            ccRing.setColor(Ui.alpha(0xFFFFFFE0, f));
            home.tmp.set(home.cell);
            home.tmp.inset(-5.5f * home.k, -5.5f * home.k);
            c.drawRoundRect(home.tmp, r + 5.5f * home.k, r + 5.5f * home.k, ccRing);
        }
        home.border.setStrokeWidth(2 * home.k);
        int ink = home.blend(0xFFF3EDE2, 0xFF1A1614, f);
        ccName.setColorFilter(null);
        ccSub.setColorFilter(null);
        ccName.setColor(ink);
        ccSub.setColor(Ui.alpha(ink, 0.7f));
        float x = home.cell.left + 26 * home.k * s;
        if (chip) {
            float d = 54 * home.k * s, ccx = x + d / 2;
            home.fill.setColor(home.blend(on ? 0x33FFB27A : 0x1AF3EDE2, 0xFF1A1614, f));
            c.drawCircle(ccx, cy, d / 2, home.fill);
            Symbols.draw(home.getContext(), c, symbol, ccx, cy, 26 * home.k * s, home.blend(on ? 0xFFFFB27A : 0xFFF3EDE2, 0xFFFFF8EC, f));
            x += d + 18 * home.k * s;
        } else if (i == CC_REFRESH || i == CC_HOME_SETTINGS || i == CC_CLEAR) {   // the Launcher row: icon over name
            Symbols.draw(home.getContext(), c, symbol, cx, cy - 16 * home.k * s, 30 * home.k * s, ink);
            ccName.setTextSize(20 * home.k * s);
            ccName.setTextAlign(Paint.Align.CENTER);
            c.drawText(name, cx, cy + 30 * home.k * s, ccName);
            ccName.setTextAlign(Paint.Align.LEFT);
            return;
        } else {
            Symbols.draw(home.getContext(), c, symbol, x + 15 * home.k * s, cy, 30 * home.k * s, ink);
            x += 30 * home.k * s + 22 * home.k * s;
        }
        float room = home.cell.right - x - (i == CC_ALL ? 56 : 18) * home.k;
        ccName.setTextSize(25 * home.k * s);
        ccSub.setTextSize(19 * home.k * s);
        home.fitPaint.set(ccName);
        String n = TextUtils.ellipsize(name, home.fitPaint, room, TextUtils.TruncateAt.END).toString();
        if (sub == null || sub.isEmpty()) {
            c.drawText(n, x, cy + Ui.halfCap(ccName), ccName);
        } else {
            c.drawText(n, x, cy - 3 * home.k * s, ccName);
            home.fitPaint.set(ccSub);
            c.drawText(TextUtils.ellipsize(sub, home.fitPaint, room, TextUtils.TruncateAt.END).toString(), x, cy + 24 * home.k * s, ccSub);
        }
        if (i == CC_ALL) Symbols.draw(home.getContext(), c, "chevron_right", home.cell.right - 34 * home.k * s, cy, 26 * home.k * s, ink);
    }

    /** The volume: a glass track, filled in white up to the level (the speaker icon on the white), the percentage. */
    private void drawCCVolume(Canvas c) {
        float f = Ui.clamp(ccFocus[CC_VOLUME].value), s = 1 + 0.02f * f;
        RectF base = ccRects[CC_VOLUME];
        float cx = base.centerX(), cy = base.centerY();
        home.cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = home.cell.height() / 2;
        home.glassBody(c, home.cell, r, 0.13f + 0.2f * f);
        home.fill.setShader(null);
        float level = Ui.clamp(ccVolume / (float) ccVolumeMax);
        float thumb = 116 * home.k * s, fillW = Math.max(thumb, home.cell.width() * level);
        home.tmp.set(home.cell.left, home.cell.top, home.cell.left + fillW, home.cell.bottom);
        home.fill.setColor(0xF2FFFFFF);
        c.drawRoundRect(home.tmp, r, r, home.fill);
        home.focusOutline(c, home.cell, r, f);
        home.glassEdge(c, home.cell, r, 0.45f + 0.55f * f, (1.2f + 1.3f * f) * home.k);
        Symbols.draw(home.getContext(), c, ccVolume == 0 ? "volume_off" : "volume_up", home.cell.left + 46 * home.k * s, cy, 28 * home.k * s, home.INK);
        String pct = Math.round(level * 100) + "%";
        c.drawText(pct, home.cell.right - 30 * home.k * s - ccPct.measureText(pct), cy + Ui.halfCap(ccPct), ccPct);
    }
}
