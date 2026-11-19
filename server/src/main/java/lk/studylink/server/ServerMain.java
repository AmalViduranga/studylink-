package lk.studylink.server;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.stream.Collectors;
import lk.studylink.shared.config.CliOptions;
import lk.studylink.shared.config.ConfigException;
import lk.studylink.shared.config.ConfigLoader;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.FeatureServiceSpec;
import lk.studylink.shared.model.RoomDefinition;

/**
 * Entry point for the StudyLink host server executable ({@code server/target/studylink-server.jar}).
 * In this phase-0 starter baseline, supports {@code --help}, headless {@code --check-config} JSON
 * validation, and a clean starter launch that reports planned service listeners without binding sockets.
 */
public final class ServerMain {

    private static final String COMPONENT_NAME = "studylink-server";
    private static final String JAR_PATH = "server/target/studylink-server.jar";

    private ServerMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes the server CLI workflow and returns an exit code (0 on success, nonzero on error).
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        final CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (ConfigException e) {
            err.println("[studylink-server] Command-line error: " + e.getMessage());
            return 2;
        }

        if (options.helpRequested()) {
            out.print(CliOptions.formatUsage(
                    JAR_PATH,
                    ServerMain.class.getName(),
                    "Classroom LAN host server (Phase-0 starter baseline)"
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
                err.println("[studylink-server] Configuration check failed: " + e.getMessage());
                return 1;
            }
        }

        final ConfigLoader.LoadedConfig loaded;
        try {
            loaded = ConfigLoader.resolveForNormalLaunch(options.configPath());
        } catch (ConfigException e) {
            err.println("[studylink-server] Startup aborted due to invalid configuration: " + e.getMessage());
            return 1;
        }

        StudyLinkConfig config = loaded.config();
        String roomsSummary = config.rooms().stream()
                .map(r -> r.displayName() + " [" + r.id() + "]")
                .collect(Collectors.joining(", "));

        out.println("========================================================================");
        out.println(" StudyLink Host Server - Phase-0 Starter Baseline");
        out.println("========================================================================");
        out.println("Configuration source : " + loaded.sourceDescription());
        out.println("Server ID / Host     : " + config.serverId() + " @ " + config.serverHost()
                + " (interface: " + config.networkInterface() + ")");
        out.println("Predefined rooms     : " + roomsSummary);
        out.println("Durable data dir     : " + config.dataDir());
        out.println("TLS server store     : " + config.tlsServerStore() + " (" + config.tlsProtocol() + ")");
        out.println("------------------------------------------------------------------------");
        out.println("Planned Network Service Listeners (reserved for feature owners):");
        for (FeatureServiceSpec service : FeatureServiceSpec.plannedServices(config)) {
            out.printf("  %-3s %-10s | port %-5d | %-39s | %s%n",
                    service.ownerCode(),
                    service.featureName(),
                    service.port(),
                    service.transport(),
                    service.status());
        }
        out.println("------------------------------------------------------------------------");
        out.println("Status: Configuration is valid. Network listeners are planned for M1-M5");
        out.println("and are not bound in this starter build. Exiting cleanly.");
        return 0;
    }
}
