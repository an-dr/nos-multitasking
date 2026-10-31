package me.agramakov.nosmultitasking;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);

        TextView help = new TextView(this);
        help.setText("1. Enable the accessibility service (on Android 16 sideloaded apps may first "
                + "need \"Allow restricted settings\" in App info, via the ⋮ menu).\n"
                + "2. Long-press this app's icon for Split screen / Pop-up view shortcuts, pin them "
                + "below, or add the Quick Settings tiles.\n"
                + "3. Open any app, then trigger a shortcut.");
        help.setPadding(0, pad, 0, pad);
        root.addView(help);

        root.addView(button("Open accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("Open app info (restricted settings)", v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))));
        root.addView(button("Pin Split screen shortcut", v ->
                pin(MultitaskService.ACTION_SPLIT, "Split screen", R.drawable.ic_split)));
        root.addView(button("Pin Pop-up view shortcut", v ->
                pin(MultitaskService.ACTION_POPUP, "Pop-up view", R.drawable.ic_popup)));
        root.addView(button("Add Split screen tile to Quick Settings", v ->
                addTile(SplitTile.class, "Split screen", R.drawable.ic_split)));
        root.addView(button("Add Pop-up view tile to Quick Settings", v ->
                addTile(PopupTile.class, "Pop-up view", R.drawable.ic_popup)));
        root.addView(button("Refresh log", v -> refresh()));

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        status.setText(MultitaskService.isRunning()
                ? "Accessibility service: ON" : "Accessibility service: OFF");
        logView.setText(MultitaskService.getLog());
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private void addTile(Class<?> cls, String label, int iconRes) {
        android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
        sbm.requestAddTileService(new android.content.ComponentName(this, cls), label,
                Icon.createWithResource(this, iconRes), getMainExecutor(), result ->
                        Toast.makeText(this, result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                || result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                ? "Tile added" : "Not added (code " + result + ")",
                                Toast.LENGTH_SHORT).show());
    }

    private void pin(String action, String label, int iconRes) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Launcher doesn't support pinning", Toast.LENGTH_LONG).show();
            return;
        }
        Intent target = new Intent(this, ActionActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra("action", action);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "pin_" + action)
                .setShortLabel(label)
                .setIcon(Icon.createWithResource(this, iconRes))
                .setIntent(target)
                .build();
        sm.requestPinShortcut(info, null);
    }
}
