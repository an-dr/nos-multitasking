package me.agramakov.nosmultitasking;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/** Runs shell commands through Shizuku (after a delay, so you can switch to another app first). */
public class ShizukuConsoleActivity extends Activity {
    private static final String[][] PRESETS = {
            {"Top activity",
                    "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -5"},
            {"Task list + modes",
                    "dumpsys activity activities | grep -E 'Task\\{' | head -40"},
            {"Logcat: popup/split/freeform",
                    "logcat -d -t 800 | grep -iE 'popup|pop-up|freeform|split|bubble|StageCoordinator' | tail -80"},
            {"Key combo Meta+Ctrl+Left (split)",
                    "input keycombination KEYCODE_META_LEFT KEYCODE_CTRL_LEFT KEYCODE_DPAD_LEFT"},
            {"Key combo Meta+Ctrl+Right (split)",
                    "input keycombination KEYCODE_META_LEFT KEYCODE_CTRL_LEFT KEYCODE_DPAD_RIGHT"},
            {"Launch Firefox freeform",
                    "am start --windowingMode 5 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p org.mozilla.firefox"},
            {"Launch Firefox split-secondary",
                    "am start --windowingMode 4 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p org.mozilla.firefox"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private IShellService shell;
    private TextView status;
    private TextView out;
    private EditText cmd;
    private EditText delay;

    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
            new ComponentName("me.agramakov.nosmultitasking", ShellService.class.getName()))
            .daemon(false).processNameSuffix("shell").debuggable(false).version(1);

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            shell = IShellService.Stub.asInterface(binder);
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shell = null;
            refresh();
        }
    };

    private final Shizuku.OnBinderReceivedListener binderListener = this::connect;
    private final Shizuku.OnRequestPermissionResultListener permListener =
            (code, result) -> connect();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 3);

        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);
        root.addView(button("Grant Shizuku permission / connect", v -> connect()));

        cmd = new EditText(this);
        cmd.setText(PRESETS[0][1]);
        root.addView(cmd);
        delay = new EditText(this);
        delay.setHint("delay seconds (time to switch to the target app)");
        delay.setText("5");
        delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(delay);
        root.addView(button("Run command after delay", v -> run(cmd.getText().toString())));

        for (String[] p : PRESETS) {
            root.addView(button(p[0], v -> cmd.setText(p[1])));
        }

        root.addView(button("Copy log", v -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", MultitaskService.getLog()));
        }));
        out = new TextView(this);
        out.setTextSize(11);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        root.addView(out);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(root);
        setContentView(sv);

        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addRequestPermissionResultListener(permListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        connect();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeRequestPermissionResultListener(permListener);
        super.onDestroy();
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        ui.post(() -> {
            String s;
            if (!Shizuku.pingBinder()) s = "Shizuku: not running";
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                s = "Shizuku: running, permission NOT granted";
            else s = "Shizuku: permission granted, shell " + (shell != null ? "connected" : "not bound");
            status.setText(s);
            out.setText(MultitaskService.getLog());
        });
    }

    private void connect() {
        if (!Shizuku.pingBinder()) {
            MultitaskService.log("Shizuku is not running; start it first");
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1);
        } else if (shell == null) {
            Shizuku.bindUserService(args, conn);
        }
        refresh();
    }

    private void run(String command) {
        int parsed = 0;
        try {
            parsed = Integer.parseInt(delay.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        final int secs = parsed;
        if (shell == null) {
            MultitaskService.log("shell not connected; tap the grant/connect button first");
            refresh();
            return;
        }
        MultitaskService.log("$ " + command + "   (in " + secs + "s)");
        final IShellService svc = shell;
        new Thread(() -> {
            try {
                Thread.sleep(secs * 1000L);
                String result = svc.exec(command);
                for (String line : result.split("\n")) MultitaskService.log("  " + line);
            } catch (Throwable t) {
                MultitaskService.log("  error: " + t);
            }
            refresh();
        }).start();
        refresh();
    }
}
