package me.agramakov.nosmultitasking;

import android.accessibilityservice.AccessibilityService;
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
    private static final long POPUP_TIMEOUT_MS = 5000;
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
        if (log.length() > 6000) log.delete(0, log.length() - 5000);
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
                if (!ok) toast("Split screen not available here");
            } else {
                startPopup();
            }
        }, 400);
    }

    // ---- pop-up view ------------------------------------------------------

    private void startPopup() {
        String label = labelOf(lastAppPackage);
        log("popup: target label=" + label);
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) {
            toast("Could not open Recents");
            return;
        }
        final long start = System.currentTimeMillis();
        final boolean[] iconClicked = {false};
        final long[] lastIconClick = {0};
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                List<AccessibilityNodeInfo> roots = roots();

                // 1) Menu entry visible? Tap it.
                AccessibilityNodeInfo entry = findFirst(roots, n -> matchesPopup(n));
                if (entry != null && clickUp(entry)) {
                    log("clicked pop-up entry: " + describe(entry));
                    return;
                }

                // 2) Tap the app icon (smallest node carrying the app label).
                if (label != null && now - lastIconClick[0] > 1500) {
                    AccessibilityNodeInfo icon = smallestWithLabel(roots, label);
                    if (icon != null && clickUp(icon)) {
                        iconClicked[0] = true;
                        lastIconClick[0] = now;
                        log("clicked icon: " + describe(icon));
                    }
                }

                if (now - start > POPUP_TIMEOUT_MS) {
                    log("popup: timed out (iconClicked=" + iconClicked[0] + "). Visible nodes:");
                    dump(roots);
                    toast("Pop-up view: menu entry not found (see log in app)");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    return;
                }
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.postDelayed(poll, 600);
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) out.add(r);
        }
        return out;
    }

    private boolean matchesPopup(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        return (t != null && POPUP_TEXT.matcher(t).matches())
                || (d != null && POPUP_TEXT.matcher(d).matches());
    }

    private AccessibilityNodeInfo smallestWithLabel(List<AccessibilityNodeInfo> roots, String label) {
        final AccessibilityNodeInfo[] best = {null};
        final int[] bestArea = {Integer.MAX_VALUE};
        Rect r = new Rect();
        for (AccessibilityNodeInfo root : roots) {
            walk(root, n -> {
                CharSequence d = n.getContentDescription();
                CharSequence t = n.getText();
                boolean hit = (d != null && d.toString().equalsIgnoreCase(label))
                        || (t != null && t.toString().equalsIgnoreCase(label));
                if (!hit) return;
                n.getBoundsInScreen(r);
                int area = Math.max(1, r.width()) * Math.max(1, r.height());
                if (area < bestArea[0]) {
                    bestArea[0] = area;
                    best[0] = AccessibilityNodeInfo.obtain(n);
                }
            });
        }
        return best[0];
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
        return n.getPackageName() + "/" + n.getClassName()
                + " text=" + n.getText() + " desc=" + n.getContentDescription()
                + " clickable=" + n.isClickable() + " " + r.toShortString();
    }

    private void dump(List<AccessibilityNodeInfo> roots) {
        int[] count = {0};
        for (AccessibilityNodeInfo root : roots) {
            walk(root, n -> {
                if (count[0] >= 40) return;
                if (n.getText() != null || n.getContentDescription() != null) {
                    log("  " + describe(n));
                    count[0]++;
                }
            });
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
