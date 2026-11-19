package lk.studylink.shared.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Parses command-line options shared by both {@code studylink-server.jar} and {@code studylink-client.jar}.
 * Supports {@code --help} (or {@code -h}), {@code --config <path>} (or {@code --config=<path>}),
 * and {@code --check-config} without requiring a desktop display.
 */
public record CliOptions(
        boolean helpRequested,
        boolean checkConfigRequested,
        Path configPath
) {

    /**
     * Parses raw CLI arguments.
     *
     * @param args command-line arguments passed to {@code main}
     * @return parsed {@link CliOptions}
     * @throws ConfigException if an unknown flag is passed or {@code --config} is missing its path argument
     */
    public static CliOptions parse(String[] args) throws ConfigException {
        Objects.requireNonNull(args, "args must not be null");
        boolean help = false;
        boolean checkConfig = false;
        Path configPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
            } else if ("--check-config".equals(arg)) {
                checkConfig = true;
            } else if ("--config".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) {
                    throw new ConfigException("Missing configuration file path after --config");
                }
                configPath = parsePath(args[++i]);
            } else if (arg.startsWith("--config=")) {
                String value = arg.substring("--config=".length()).trim();
                if (value.isEmpty()) {
                    throw new ConfigException("Missing configuration file path in " + arg);
                }
                configPath = parsePath(value);
            } else {
                throw new ConfigException("Unknown command-line argument: '" + arg + "' (use --help for usage)");
            }
        }

        return new CliOptions(help, checkConfig, configPath);
    }

    /**
     * Resolves the configuration path required when {@code --check-config} is requested.
     * Uses the explicit {@code --config <path>} when given, or defaults to {@code config/studylink.properties}.
     */
    public Path effectiveCheckConfigPath() {
        return configPath != null ? configPath : ConfigLoader.DEFAULT_LOCAL_CONFIG_PATH;
    }

    /**
     * Formats standard CLI usage text for either the server or client executable JAR.
     */
    public static String formatUsage(String jarName, String mainClass, String description) {
        return """
                StudyLink (%s) - %s
                Main-Class: %s

                Usage:
                  java -jar %s --help
                  java -jar %s --config <path> --check-config
                  java -jar %s [--config <path>]

                Options:
                  -h, --help          Show this help message and exit (code 0, headless-safe)
                  --config <path>     Path to StudyLink .properties configuration file
                                      (default on normal launch: config/studylink.properties,
                                      falling back to config/studylink.example.properties)
                  --check-config      Validate the configuration file headlessly, print a JSON
                                      summary of effective non-secret settings via Gson, and exit
                                      (code 0 if valid, nonzero if missing or invalid)
                """.formatted(jarName, description, mainClass, jarName, jarName, jarName);
    }

    private static Path parsePath(String rawPath) throws ConfigException {
        try {
            return Paths.get(rawPath.trim());
        } catch (InvalidPathException e) {
            throw new ConfigException("Invalid path argument for --config: '" + rawPath + "'", e);
        }
    }
}
