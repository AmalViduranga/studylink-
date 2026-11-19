package lk.studylink.shared.config;

/**
 * Exception thrown when a StudyLink configuration file or command-line argument is missing or invalid.
 */
public final class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
