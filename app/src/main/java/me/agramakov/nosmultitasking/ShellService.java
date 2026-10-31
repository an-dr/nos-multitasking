package me.agramakov.nosmultitasking;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Runs inside the Shizuku server process with shell (adb) privileges. */
public class ShellService extends IShellService.Stub {
    public ShellService() {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String command) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < 20000) out.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            out.append("[exit ").append(code).append("]");
        } catch (Throwable t) {
            out.append("error: ").append(t);
        }
        return out.toString();
    }
}
