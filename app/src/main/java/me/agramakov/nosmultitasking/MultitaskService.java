package me.agramakov.nosmultitasking;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Accessibility service that performs the two actions.
 *
 * Split screen: the system global action GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN.
 * Pop-up view: opens Recents, taps the app icon of the previously used app and
 * taps the "Pop-up view" entry in the menu that appears.
 */
public class MultitaskService extends AccessibilityService {

    public static final String ACTION_SPLIT = "split";
    public static final String ACTION_POPUP = "popup";

    private static final long POPUP_TIMEOUT_MS = 6000;
    private static final long POLL_MS = 250;

    private static volatile MultitaskService instance;
    private static final StringBuilder log = new StringBuilder();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastAppPackage;

    public static boolean isRunning() {
        return instance != null;
    }

    /** Describes the global actions this device lets the service perform. */
    public static String systemActions() {
        MultitaskService s = instance;
        if (s == null) return "system actions: service not running";
        StringBuilder sb = new StringBuilder("system actions:");
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a : s.getSystemActions()) {
            sb.append(" ").append(a.getId()).append("=").append(a.getLabel());
        }
        return sb.toString();
    }

    public static synchronized String getLog() {
        return log.length() == 0 ? "(empty)" : log.toString();
    }

    static synchronized void log(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.append(ts).append("  ").append(line).append('\n');
        if (log.length() > 40000) log.delete(0, log.length() - 30000);
    }

    /** Runs an action on the app that was in the foreground before the trigger. */
    public static boolean run(String action) {
        MultitaskService s = instance;
        if (s == null) return false;
        s.handler.post(() -> s.execute(action));
        return true;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        log("service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;
        if (isHome(p)) return;
        lastAppPackage = p;
    }

    private boolean isHome(String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            if (ri.activityInfo.packageName.equals(pkg)) return true;
        }
        return false;
    }

    private String labelOf(String pkg) {
        if (pkg == null) return null;
        try {
            return getPackageManager().getApplicationInfo(pkg, 0)
                    .loadLabel(getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private int generation = 0;

    private void execute(String action) {
        // A newer trigger cancels any run still in flight.
        generation++;
        final int gen = generation;
        handler.removeCallbacksAndMessages(null);
        log("=== action=" + action + " lastApp=" + lastAppPackage + " gen=" + gen);
        // Close the notification shade in case a Quick Settings tile triggered us.
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        final boolean split = ACTION_SPLIT.equals(action);
        handler.postDelayed(() -> viaRecents(gen, split ? "Split screen" : "Pop-up view"), 350);
    }

    // ---- Recents automation ----------------------------------------------
    // Nothing's Recents shows "Split screen" / "Pop-up view" buttons under the
    // centred app card. Open Recents, make sure the target app's card is the
    // centred one (swipe if not), then click the button.

    private void viaRecents(int gen, String name) {
        refreshHomePkgs();
        final String label = labelOf(lastAppPackage);
        log(name + ": opening Recents, target=" + label);
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) {
            toast("Could not open Recents");
            return;
        }
        final long start = System.currentTimeMillis();
        final int[] swipes = {0};
        final long[] lastSwipe = {0};
        final String[] lastState = {""};
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (gen != generation) return;
                long now = System.currentTimeMillis();
                List<AccessibilityNodeInfo> roots = roots();
                AccessibilityNodeInfo entry = findEntry(roots, name);
                AccessibilityNodeInfo centered = centeredCard(roots);
                AccessibilityNodeInfo target = label == null ? null : cardWithLabel(roots, label);
                String cLabel = centered == null ? null : String.valueOf(centered.getContentDescription());
                String state = "entry=" + (entry != null) + " centered=" + cLabel
                        + " targetVisible=" + (target != null) + " t=" + (now - start) + "ms";
                if (!state.equals(lastState[0])) {
                    log("  " + state);
                    lastState[0] = state;
                }

                if (entry != null && now - lastSwipe[0] > 700) {
                    boolean focused = label == null || target == null || centered == null
                            || label.equalsIgnoreCase(cLabel);
                    if (focused || swipes[0] >= 3) {
                        boolean ok = clickUp(entry);
                        log(name + ": click " + describe(entry) + " -> " + ok
                                + " (" + (now - start) + "ms)");
                        if (!ok) tapCenter(entry);
                        return;
                    }
                    // Target card is on screen but not centred: scroll it into the middle.
                    Rect t = new Rect(), c = new Rect();
                    target.getBoundsInScreen(t);
                    centered.getBoundsInScreen(c);
                    int w = getResources().getDisplayMetrics().widthPixels;
                    int y = getResources().getDisplayMetrics().heightPixels / 2;
                    boolean right = t.left < c.left;
                    log("  swipe " + (right ? "right" : "left") + " to centre " + label);
                    swipe(right ? w * 0.15f : w * 0.85f, y, right ? w * 0.85f : w * 0.15f, y);
                    swipes[0]++;
                    lastSwipe[0] = now;
                }

                if (now - start > POPUP_TIMEOUT_MS) {
                    log(name + ": timed out. Tree:");
                    dump(roots);
                    toast(name + ": failed, see log in app");
                    return;
                }
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.postDelayed(poll, 400);
    }

    /** The bottom-bar button whose label is exactly {@code name}, inside the launcher window. */
    private AccessibilityNodeInfo findEntry(List<AccessibilityNodeInfo> roots, String name) {
        return findFirst(roots, n -> {
            if (ignored(n)) return false;
            CharSequence d = n.getContentDescription();
            CharSequence t = n.getText();
            boolean hit = (d != null && d.toString().equalsIgnoreCase(name))
                    || (t != null && t.toString().equalsIgnoreCase(name));
            if (!hit) return false;
            // Must be clickable itself or inside something clickable, and near the bottom.
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r.top > getResources().getDisplayMetrics().heightPixels / 2;
        });
    }

    private boolean isCard(AccessibilityNodeInfo n) {
        if (ignored(n)) return false;
        CharSequence d = n.getContentDescription();
        if (d == null || d.length() == 0) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.height() > 1000 && r.width() > 400;
    }

    /** The Recents card that contains the horizontal screen centre. */
    private AccessibilityNodeInfo centeredCard(List<AccessibilityNodeInfo> roots) {
        int cx = getResources().getDisplayMetrics().widthPixels / 2;
        return findFirst(roots, n -> {
            if (!isCard(n)) return false;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r.left <= cx && r.right >= cx;
        });
    }

    private AccessibilityNodeInfo cardWithLabel(List<AccessibilityNodeInfo> roots, String label) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        return findFirst(roots, n -> {
            if (!isCard(n)) return false;
            if (!label.equalsIgnoreCase(String.valueOf(n.getContentDescription()))) return false;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r.right > 0 && r.left < sw;
        });
    }

    private void swipe(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null);
    }

    private final java.util.Set<String> homePkgs = new java.util.HashSet<>();

    private void refreshHomePkgs() {
        homePkgs.clear();
        homePkgs.add("com.nothing.launcher");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            homePkgs.add(ri.activityInfo.packageName);
        }
    }

    /** Only the launcher (which hosts Recents) is of interest; apps behind it are ignored. */
    private boolean ignored(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p == null || !homePkgs.contains(p.toString());
    }

    private void tapCenter(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        log("tap center " + r.centerX() + "," + r.centerY());
        tap(r.centerX(), r.centerY());
    }

    private void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && !ignored(r)) out.add(r);
        }
        return out;
    }

    private AccessibilityNodeInfo findFirst(List<AccessibilityNodeInfo> roots, Pred p) {
        final AccessibilityNodeInfo[] found = {null};
        for (AccessibilityNodeInfo root : roots) {
            walk(root, n -> {
                if (found[0] == null && p.test(n)) found[0] = AccessibilityNodeInfo.obtain(n);
            });
            if (found[0] != null) break;
        }
        return found[0];
    }

    private boolean clickUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 6; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private interface Pred {
        boolean test(AccessibilityNodeInfo n);
    }

    private interface Visitor {
        void visit(AccessibilityNodeInfo n);
    }

    private void walk(AccessibilityNodeInfo n, Visitor v) {
        if (n == null) return;
        v.visit(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), v);
    }

    private String describe(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return n.getClassName() + " id=" + n.getViewIdResourceName()
                + " text=" + n.getText() + " desc=" + n.getContentDescription()
                + " click=" + n.isClickable() + " " + r.toShortString();
    }

    /** Logs every node (not just labelled ones) of the non-own, non-systemui windows. */
    private void dump(List<AccessibilityNodeInfo> roots) {
        int[] count = {0};
        for (AccessibilityNodeInfo root : roots) {
            log("window pkg=" + root.getPackageName());
            walk(root, n -> {
                if (count[0] >= 120) return;
                log("  " + describe(n));
                count[0]++;
            });
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
