package me.agramakov.nosmultitasking;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

/** Keeps a shell-privileged helper bound (through Shizuku) for the lifetime of the app process. */
public final class ShizukuShell {
    private static final Shizuku.UserServiceArgs ARGS = new Shizuku.UserServiceArgs(
            new ComponentName("me.agramakov.nosmultitasking", ShellService.class.getName()))
            .daemon(false).processNameSuffix("shell").debuggable(false).version(1);

    private static volatile IShellService svc;
    private static boolean initialized;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = IShellService.Stub.asInterface(binder);
            MultitaskService.log("shizuku shell connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            MultitaskService.log("shizuku shell disconnected");
        }
    };

    private ShizukuShell() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        Shizuku.addBinderReceivedListenerSticky(ShizukuShell::tryBind);
        Shizuku.addBinderDeadListener(() -> svc = null);
    }

    public static void tryBind() {
        try {
            if (svc == null && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.bindUserService(ARGS, CONN);
            }
        } catch (Throwable t) {
            MultitaskService.log("shizuku bind failed: " + t);
        }
    }

    public static boolean ready() {
        IShellService s = svc;
        return s != null && s.asBinder().isBinderAlive();
    }

    /** Runs a shell command off the main thread and reports its output. */
    public static void exec(String command, Consumer<String> done) {
        IShellService s = svc;
        if (s == null) {
            done.accept("shizuku not connected");
            return;
        }
        new Thread(() -> {
            String out;
            try {
                out = s.exec(command);
            } catch (Throwable t) {
                out = "error: " + t;
            }
            done.accept(out);
        }).start();
    }
}
