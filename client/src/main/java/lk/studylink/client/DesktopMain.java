package lk.studylink.client;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lk.studylink.client.ui.RoomLifecyclePanel;
import lk.studylink.client.ui.StarterFrame;
import lk.studylink.shared.config.CliOptions;
import lk.studylink.shared.config.ConfigException;
import lk.studylink.shared.config.ConfigLoader;
import lk.studylink.shared.config.StudyLinkConfig;

/**
 * Entry point for the StudyLink desktop client executable ({@code client/target/studylink-client.jar}).
 * Processes {@code --help} and {@code --check-config} headlessly before initializing AWT/Swing.
 * On normal launch with a desktop display, applies FlatLaf Light and opens {@link StarterFrame}
 * (with its interactive {@link RoomLifecyclePanel}) on Swing's Event Dispatch Thread.
 */
public final class DesktopMain {

    private static final String COMPONENT_NAME = "studylink-client";
    private static final String JAR_PATH = "client/target/studylink-client.jar";

    private DesktopMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err, true);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes the client CLI workflow or launches the Swing desktop window.
     *
     * @param args           command-line arguments
     * @param out            standard output stream
     * @param err            standard error stream
     * @param launchGuiIfSet whether to open the Swing window on normal launch
     * @return process exit code (0 on success, nonzero on failure)
     */
    public static int run(String[] args, PrintStream out, PrintStream err, boolean launchGuiIfSet) {
        final CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (ConfigException e) {
            err.println("[studylink-client] Command-line error: " + e.getMessage());
            return 2;
        }

        if (options.helpRequested()) {
            out.print(CliOptions.formatUsage(
                    JAR_PATH,
                    DesktopMain.class.getName(),
                    "Swing desktop client with FlatLaf Light and TCP Room Control"
            ));
            return 0;
        }

        if (options.checkConfigRequested()) {
            Path checkPath = options.effectiveCheckConfigPath();
            try {
                StudyLinkConfig config = ConfigLoader.load(checkPath);
                out.println(config.toSummaryJson(COMPONENT_NAME, checkPath.toString()));
                return 0;
            } catch (ConfigException e) {
                err.println("[studylink-client] Configuration check failed: " + e.getMessage());
                return 1;
            }
        }

        final ConfigLoader.LoadedConfig loaded;
        try {
            loaded = ConfigLoader.resolveForNormalLaunch(options.configPath());
        } catch (ConfigException e) {
            err.println("[studylink-client] Startup aborted due to invalid configuration: " + e.getMessage());
            return 1;
        }

        if (!launchGuiIfSet) {
            out.println("[studylink-client] Configuration validated from " + loaded.sourceDescription()
                    + " (GUI launch skipped by test caller).");
            return 0;
        }

        if (GraphicsEnvironment.isHeadless()) {
            err.println("[studylink-client] Cannot open Swing desktop window: no desktop display is available "
                    + "(GraphicsEnvironment.isHeadless() == true). Use --check-config or --help in headless environments.");
            return 1;
        }

        Integer autoCloseMs = parseAutoCloseMs();
        String demoSteps = System.getProperty("studylink.demoSteps");

        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            StarterFrame frame = new StarterFrame(loaded.config(), loaded.sourceDescription());
            frame.setVisible(true);
            out.println("[studylink-client] Opened StudyLink desktop window on Swing EDT using FlatLaf Light "
                    + "(config: " + loaded.sourceDescription() + ").");
            out.flush();

            if (demoSteps != null && !demoSteps.isBlank()) {
                Thread driver = new Thread(
                        () -> executeDemoSteps(frame, demoSteps, out),
                        "studylink-ui-demo-driver"
                );
                driver.setDaemon(false);
                driver.start();
            } else if (autoCloseMs != null && autoCloseMs > 0) {
                Timer timer = new Timer(autoCloseMs, e -> {
                    out.println("[studylink-client] Auto-closing desktop window after " + autoCloseMs + " ms.");
                    out.flush();
                    frame.dispose();
                });
                timer.setRepeats(false);
                timer.start();
            }
        });

        return 0;
    }

    private static void executeDemoSteps(StarterFrame frame, String demoSteps, PrintStream out) {
        RoomLifecyclePanel panel = frame.roomLifecyclePanel();
        String[] steps = demoSteps.split(",");
        try {
            for (String rawStep : steps) {
                String step = rawStep.trim();
                if (step.isEmpty()) {
                    continue;
                }
                if (step.startsWith("host=")) {
                    String host = step.substring("host=".length());
                    SwingUtilities.invokeAndWait(() -> panel.hostField().setText(host));
                } else if (step.startsWith("port=")) {
                    String port = step.substring("port=".length());
                    SwingUtilities.invokeAndWait(() -> panel.portField().setText(port));
                } else if (step.startsWith("name=")) {
                    String name = step.substring("name=".length());
                    SwingUtilities.invokeAndWait(() -> panel.displayNameField().setText(name));
                } else if ("connect".equals(step)) {
                    panel.connectAsync().get(5, TimeUnit.SECONDS);
                    logUiSnapshot(panel, "AFTER_CONNECT", out);
                } else if (step.startsWith("join=")) {
                    String roomId = step.substring("join=".length());
                    panel.joinRoomAsync(roomId).get(5, TimeUnit.SECONDS);
                    logUiSnapshot(panel, "AFTER_JOIN(" + roomId + ")", out);
                } else if (step.startsWith("switch=")) {
                    String roomId = step.substring("switch=".length());
                    panel.joinRoomAsync(roomId).get(5, TimeUnit.SECONDS);
                    logUiSnapshot(panel, "AFTER_SWITCH(" + roomId + ")", out);
                } else if ("leave".equals(step)) {
                    panel.leaveRoomAsync().get(5, TimeUnit.SECONDS);
                    logUiSnapshot(panel, "AFTER_LEAVE", out);
                } else if ("disconnect".equals(step)) {
                    panel.disconnectAsync().get(5, TimeUnit.SECONDS);
                    logUiSnapshot(panel, "AFTER_DISCONNECT", out);
                } else if (step.startsWith("pause=")) {
                    long ms = Long.parseLong(step.substring("pause=".length()));
                    Thread.sleep(ms);
                } else if ("close".equals(step)) {
                    SwingUtilities.invokeAndWait(frame::dispose);
                    out.println("[studylink-client-ui] STEP=AFTER_CLOSE windowDisposed=true");
                    out.flush();
                }
            }
        } catch (Exception e) {
            out.println("[studylink-client-ui] DEMO_ERROR=" + e.getMessage());
            out.flush();
            SwingUtilities.invokeLater(frame::dispose);
        }
    }

    private static void logUiSnapshot(RoomLifecyclePanel panel, String stepLabel, PrintStream out)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            out.printf(
                    "[studylink-client-ui] STEP=%s | connectionState=\"%s\" | session=\"%s\" | "
                            + "activeRoom=\"%s\" | roomsCount=%d | statusBanner=\"%s\" | "
                            + "ioOffEdt=%s | uiOnEdt=%s%n",
                    stepLabel,
                    panel.connectionStateText(),
                    panel.sessionSummaryText(),
                    panel.activeRoomText(),
                    panel.listedRooms().size(),
                    panel.statusBannerText(),
                    panel.lastNetworkIoRanOffEdt(),
                    panel.lastUiUpdateRanOnEdt()
            );
            out.flush();
        });
    }

    private static Integer parseAutoCloseMs() {
        String raw = System.getProperty("studylink.autoCloseMs");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
