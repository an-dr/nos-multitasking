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
import java.util.regex.Pattern;

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

    private static final Pattern POPUP_TEXT =
            Pattern.compile("(?i).*(pop-?\\s?up|floating|freeform).*");
    private static final Pattern SPLIT_TEXT = Pattern.compile("(?i).*split.*");
    private static final long POPUP_TIMEOUT_MS = 8000;
    private static final long POLL_MS = 250;

    private static volatile MultitaskService instance;
    private static final StringBuilder log = new StringBuilder();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastAppPackage;

    public static boolean isRunning() {
        return instance != null;
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

    private void execute(String action) {
        log("action=" + action + " lastApp=" + lastAppPackage);
        // Close the notification shade in case a Quick Settings tile triggered us.
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        handler.postDelayed(() -> {
            if (ACTION_SPLIT.equals(action)) {
                boolean ok = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
                log("toggle split screen -> " + ok);
                if (!ok) viaRecents(SPLIT_TEXT, "Split screen");
            } else {
                viaRecents(POPUP_TEXT, "Pop-up view");
            }
        }, 500);
    }

    // ---- Recents-menu automation -----------------------------------------

    /** Opens Recents, taps the app pill on the previous app's card, taps the matching menu entry. */
    private void viaRecents(Pattern entryPattern, String name) {
        refreshHomePkgs();
        String label = labelOf(lastAppPackage);
        log(name + ": via Recents (launchers=" + homePkgs + "), target label=" + label);
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) {
            toast("Could not open Recents");
            return;
        }
        final long start = System.currentTimeMillis();
        final long[] lastTap = {0};
        final int[] taps = {0};
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                List<AccessibilityNodeInfo> roots = roots();

                // 1) Menu entry visible? Tap it.
                AccessibilityNodeInfo entry = findFirst(roots, n -> matches(n, entryPattern));
                if (entry != null) {
                    log("tapping entry: " + describe(entry));
                    if (!clickUp(entry)) tapCenter(entry);
                    return;
                }

                // 2) Tap the app pill at the top of the app's card.
                if (label != null && taps[0] < 3 && now - lastTap[0] > 2000) {
                    AccessibilityNodeInfo card = bestCard(roots, label);
                    if (card != null) {
                        tapHeader(card);
                        taps[0]++;
                        lastTap[0] = now;
                        handler.postDelayed(() -> {
                            log("--- tree 1s after header tap ---");
                            dump(roots());
                        }, 1000);
                    }
                }

                if (now - start > POPUP_TIMEOUT_MS) {
                    log(name + ": timed out (header taps=" + taps[0] + "). Tree:");
                    dump(roots);
                    toast(name + ": menu entry not found (see log in app)");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    return;
                }
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.postDelayed(poll, 700);
    }

    private final java.util.Set<String> homePkgs = new java.util.HashSet<>();

    private void refreshHomePkgs() {
        homePkgs.clear();
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

    private boolean matches(AccessibilityNodeInfo n, Pattern pat) {
        if (ignored(n)) return false;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        return (t != null && t.length() <= 40 && pat.matcher(t).matches())
                || (d != null && d.length() <= 40 && pat.matcher(d).matches());
    }

    /** The on-screen Recents card that carries the app's label. */
    private AccessibilityNodeInfo bestCard(List<AccessibilityNodeInfo> roots, String label) {
        final AccessibilityNodeInfo[] best = {null};
        final long[] bestArea = {0};
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        Rect r = new Rect();
        for (AccessibilityNodeInfo root : roots) {
            walk(root, n -> {
                if (ignored(n)) return;
                CharSequence d = n.getContentDescription();
                CharSequence t = n.getText();
                boolean hit = (d != null && d.toString().equalsIgnoreCase(label))
                        || (t != null && t.toString().equalsIgnoreCase(label));
                if (!hit) return;
                n.getBoundsInScreen(r);
                Rect vis = new Rect(r);
                if (!vis.intersect(0, 0, sw, sh)) return;
                long area = (long) vis.width() * vis.height();
                if (area > bestArea[0]) {
                    bestArea[0] = area;
                    best[0] = AccessibilityNodeInfo.obtain(n);
                }
            });
        }
        return best[0];
    }

    /** Taps the app-name pill: a clickable child near the card top, else a positional tap. */
    private void tapHeader(AccessibilityNodeInfo card) {
        Rect c = new Rect();
        card.getBoundsInScreen(c);
        final AccessibilityNodeInfo[] child = {null};
        Rect r = new Rect();
        walk(card, n -> {
            if (child[0] != null || n == card || !n.isClickable()) return;
            n.getBoundsInScreen(r);
            if (r.top < c.top + c.height() * 0.2 && r.width() < c.width() * 0.9) {
                child[0] = AccessibilityNodeInfo.obtain(n);
            }
        });
        if (child[0] != null && child[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("clicked header child: " + describe(child[0]));
            return;
        }
        // Pill sits ~30% across and ~4.5% down from the card's top-left corner.
        float x = c.left + c.width() * 0.30f;
        float y = c.top + c.height() * 0.045f;
        log("tap header at " + (int) x + "," + (int) y + " card=" + c.toShortString());
        tap(x, y);
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
