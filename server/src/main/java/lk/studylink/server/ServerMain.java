package lk.studylink.server;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lk.studylink.server.rooms.RoomControlServer;
import lk.studylink.server.rooms.RoomSessionRegistry;
import lk.studylink.shared.config.CliOptions;
import lk.studylink.shared.config.ConfigException;
import lk.studylink.shared.config.ConfigLoader;
import lk.studylink.shared.config.StudyLinkConfig;
import lk.studylink.shared.model.FeatureServiceSpec;

/**
 * Entry point for the StudyLink host server executable ({@code server/target/studylink-server.jar}).
 *
 * <p>Supports headless {@code --help} and {@code --config <path> --check-config} validation, and on
 * normal launch initializes the shared {@link RoomSessionRegistry}, binds and starts the blocking
 * TCP {@link RoomControlServer} on {@code server.host:port.rooms}, reports the listening endpoint,
 * keeps the server process alive until shutdown, and releases socket and worker resources cleanly.
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
     * Executes the server CLI workflow and blocks while the TCP room-control service is running.
     *
     * @param args command-line arguments
     * @param out  standard output stream
     * @param err  error output stream
     * @return {@code 0} on clean help, config check, or normal shutdown; {@code 1} on configuration
     *         or socket bind failure; {@code 2} on malformed CLI flags
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, new CountDownLatch(1), null);
    }

    /**
     * Package-private overload allowing tests to observe the bound room-control address immediately
     * after startup and trigger deterministic shutdown via {@code stopSignal}.
     */
    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            CountDownLatch stopSignal,
            Consumer<InetSocketAddress> onListeningCallback
    ) {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(err, "err must not be null");
        CountDownLatch effectiveStopSignal = (stopSignal != null) ? stopSignal : new CountDownLatch(1);

        final CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (ConfigException e) {
            err.println("[studylink-server] Command-line error: " + e.getMessage());
            err.flush();
            return 2;
        }

        if (options.helpRequested()) {
            out.print(CliOptions.formatUsage(
                    JAR_PATH,
                    ServerMain.class.getName(),
                    "Classroom LAN host server"
            ));
            out.flush();
            return 0;
        }

        if (options.checkConfigRequested()) {
            Path checkPath = options.effectiveCheckConfigPath();
            try {
                StudyLinkConfig config = ConfigLoader.load(checkPath);
                out.println(config.toSummaryJson(COMPONENT_NAME, checkPath.toString()));
                out.flush();
                return 0;
            } catch (ConfigException e) {
                err.println("[studylink-server] Configuration check failed: " + e.getMessage());
                err.flush();
                return 1;
            }
        }

        final ConfigLoader.LoadedConfig loaded;
        try {
            loaded = ConfigLoader.resolveForNormalLaunch(options.configPath());
        } catch (ConfigException e) {
            err.println("[studylink-server] Startup aborted due to invalid configuration: " + e.getMessage());
            err.flush();
            return 1;
        }

        StudyLinkConfig config = loaded.config();
        RoomSessionRegistry registry = new RoomSessionRegistry(config);
        RoomControlServer roomControlServer = new RoomControlServer(config, registry);

        try {
            roomControlServer.start();
        } catch (IOException e) {
            roomControlServer.close();
            err.println("[studylink-server] Failed to bind TCP room-control service on "
                    + config.serverHost() + ":" + config.roomsPort() + ": " + e.getMessage());
            err.flush();
            return 1;
        }

        int boundPort = roomControlServer.boundPort();
        String listeningEndpoint = config.serverHost() + ":" + boundPort;

        Thread shutdownHook = new Thread(() -> {
            effectiveStopSignal.countDown();
            roomControlServer.close();
        }, "studylink-server-shutdown-hook");

        boolean hookRegistered = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM is already shutting down or security manager disallowed hook registration
        }

        try {
            printStartupBanner(out, loaded, config, listeningEndpoint);
            if (onListeningCallback != null) {
                onListeningCallback.accept(roomControlServer.boundAddress());
            }
            effectiveStopSignal.await();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            roomControlServer.close();
            if (hookRegistered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // Shutdown is already in progress
                }
            }
            out.println("[studylink-server] TCP room-control service stopped (" + listeningEndpoint + ").");
            out.flush();
        }
    }

    private static void printStartupBanner(
            PrintStream out,
            ConfigLoader.LoadedConfig loaded,
            StudyLinkConfig config,
            String listeningEndpoint
    ) {
        String roomsSummary = config.rooms().stream()
                .map(r -> r.displayName() + " [" + r.id() + "]")
                .collect(Collectors.joining(", "));

        out.println("========================================================================");
        out.println(" StudyLink Host Server");
        out.println("========================================================================");
        out.println("Configuration source : " + loaded.sourceDescription());
        out.println("Server ID / Host     : " + config.serverId() + " @ " + config.serverHost()
                + " (interface: " + config.networkInterface() + ")");
        out.println("Predefined rooms     : " + roomsSummary);
        out.println("Durable data dir     : " + config.dataDir());
        out.println("TLS server store     : " + config.tlsServerStore() + " (" + config.tlsProtocol() + ")");
        out.println("------------------------------------------------------------------------");
        out.println("Network Service Listeners:");
        for (FeatureServiceSpec service : FeatureServiceSpec.plannedServices(config)) {
            String status = "M1".equals(service.ownerCode())
                    ? "LISTENING on " + listeningEndpoint
                    : service.status();
            out.printf("  %-3s %-10s | port %-5d | %-39s | %s%n",
                    service.ownerCode(),
                    service.featureName(),
                    service.port(),
                    service.transport(),
                    status);
        }
        out.println("------------------------------------------------------------------------");
        out.println("[studylink-server] TCP room-control service listening on " + listeningEndpoint);
        out.println("Press Ctrl+C to stop the server.");
        out.flush();
    }
}
