package me.agramakov.nosmultitasking;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

/** Probes which direct (no Recents UI) windowing routes this phone accepts. Results go to the log. */
public class ExperimentsActivity extends Activity {
    private EditText pkg;
    private TextView out;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        pkg = new EditText(this);
        pkg.setHint("package to launch, e.g. org.mozilla.firefox");
        pkg.setText("org.mozilla.firefox");
        root.addView(pkg);

        root.addView(button("Dump diagnostics", v -> diagnostics()));
        root.addView(button("Launch: freeform (windowingMode 5)", v -> launch(5, false, true)));
        root.addView(button("Launch: freeform, no bounds", v -> launch(5, false, false)));
        root.addView(button("Launch: split-secondary (mode 4)", v -> launch(4, false, false)));
        root.addView(button("Launch: LAUNCH_ADJACENT only", v -> launch(0, true, false)));
        root.addView(button("Copy log", v -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", MultitaskService.getLog()));
        }));

        out = new TextView(this);
        out.setTextSize(11);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        refresh();
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        out.setText(MultitaskService.getLog());
    }

    private void diagnostics() {
        PackageManager pm = getPackageManager();
        MultitaskService.log("--- diagnostics ---");
        MultitaskService.log("android " + android.os.Build.VERSION.RELEASE + " sdk "
                + android.os.Build.VERSION.SDK_INT + " " + android.os.Build.DISPLAY);
        MultitaskService.log("feature freeform=" + pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                + " pip=" + pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                + " multiwindow=" + pm.hasSystemFeature("android.software.activities_on_secondary_displays")
                + " isInMultiWindow=" + isInMultiWindowMode());
        for (String k : new String[]{"enable_freeform_support", "force_resizable_activities",
                "freeform_window_management", "development_settings_enabled",
                "enable_non_resizable_multi_window", "enable_desktop_mode"}) {
            MultitaskService.log("global " + k + "=" + Settings.Global.getString(getContentResolver(), k));
        }
        MultitaskService.log(MultitaskService.systemActions());

        Pattern pat = Pattern.compile("(?i).*(pop-?up|freeform|floating|multiwindow|multi_window|split|nothing).*");
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String label = String.valueOf(ai.loadLabel(pm));
            if (pat.matcher(ai.packageName).matches() || (sys && pat.matcher(label).matches())) {
                MultitaskService.log("pkg " + ai.packageName + " [" + label + "]" + (sys ? " system" : ""));
            }
        }
        refresh();
    }

    private void launch(int windowingMode, boolean adjacent, boolean bounds) {
        String p = pkg.getText().toString().trim();
        Intent i = getPackageManager().getLaunchIntentForPackage(p);
        if (i == null) {
            MultitaskService.log("no launch intent for " + p);
            refresh();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        if (adjacent) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        ActivityOptions o = ActivityOptions.makeBasic();
        String what = "launch " + p + " mode=" + windowingMode + " adjacent=" + adjacent + " bounds=" + bounds;
        try {
            if (windowingMode != 0) {
                Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, windowingMode);
            }
            if (bounds) {
                int w = getResources().getDisplayMetrics().widthPixels;
                int h = getResources().getDisplayMetrics().heightPixels;
                o.setLaunchBounds(new Rect(w / 8, h / 6, w * 7 / 8, h * 2 / 3));
            }
            startActivity(i, o.toBundle());
            MultitaskService.log(what + " -> started (check what you see)");
        } catch (Throwable t) {
            MultitaskService.log(what + " -> " + t);
        }
        refresh();
    }
}
