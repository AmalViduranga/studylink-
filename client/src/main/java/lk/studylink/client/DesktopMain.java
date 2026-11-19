package lk.studylink.client;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lk.studylink.client.ui.StarterFrame;
import lk.studylink.shared.config.CliOptions;
import lk.studylink.shared.config.ConfigException;
import lk.studylink.shared.config.ConfigLoader;
import lk.studylink.shared.config.StudyLinkConfig;

/**
 * Entry point for the StudyLink desktop client executable ({@code client/target/studylink-client.jar}).
 * Processes {@code --help} and {@code --check-config} headlessly before initializing AWT/Swing.
 * On normal launch with a desktop display, applies FlatLaf Light and opens {@link StarterFrame}
 * on Swing's Event Dispatch Thread.
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
     * Executes the client CLI workflow or launches the Swing starter window.
     *
     * @param args           command-line arguments
     * @param out            standard output stream
     * @param err            standard error stream
     * @param launchGuiIfSet whether to open the Swing starter window on normal launch
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
                    "Swing desktop client with FlatLaf Light (Phase-0 starter baseline)"
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
            err.println("[studylink-client] Cannot open Swing starter window: no desktop display is available "
                    + "(GraphicsEnvironment.isHeadless() == true). Use --check-config or --help in headless environments.");
            return 1;
        }

        Integer autoCloseMs = parseAutoCloseMs();
        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            StarterFrame frame = new StarterFrame(loaded.config(), loaded.sourceDescription());
            frame.setVisible(true);
            out.println("[studylink-client] Opened StudyLink starter window on Swing EDT using FlatLaf Light "
                    + "(config: " + loaded.sourceDescription() + ").");
            if (autoCloseMs != null && autoCloseMs > 0) {
                Timer timer = new Timer(autoCloseMs, e -> {
                    out.println("[studylink-client] Auto-closing starter window after " + autoCloseMs + " ms.");
                    frame.dispose();
                });
                timer.setRepeats(false);
                timer.start();
            }
        });

        return 0;
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
