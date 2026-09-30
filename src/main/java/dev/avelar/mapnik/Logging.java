package dev.avelar.mapnik;

import java.nio.file.Path;

/**
 * Mapnik's own log, which it writes to the console (standard error) by default. This is process
 * wide. Turn the severity up when a style loads but draws nothing: Mapnik usually says why.
 */
public final class Logging {
    private static final NativeApi N = NativeApi.INSTANCE;

    /** How much Mapnik says. {@link #NONE} is silent. */
    public enum Severity { DEBUG, WARN, ERROR, NONE }

    private Logging() {}

    /** The global severity: messages below it are dropped. */
    public static Severity severity() {
        return Severity.values()[N.mapnik_log_get_severity()];
    }

    public static void setSeverity(Severity severity) {
        N.mapnik_log_set_severity(severity.ordinal());
    }

    /** The severity for one named part of Mapnik, such as {@code load_map}. Falls back to the global one. */
    public static Severity severity(String object) {
        return Severity.values()[N.mapnik_log_get_object_severity(object)];
    }

    public static void setSeverity(String object, Severity severity) {
        N.mapnik_log_set_object_severity(object, severity.ordinal());
    }

    /** Forget every per-object severity set with {@link #setSeverity(String, Severity)}. */
    public static void clearObjectSeverities() {
        N.mapnik_log_clear_object_severities();
    }

    /** The line format, which uses {@code %Y-%m-%d %H:%M:%S} style time codes. */
    public static String format() {
        return N.mapnik_log_get_format();
    }

    public static void setFormat(String format) {
        N.mapnik_log_set_format(format);
    }

    /** Write the log to a file instead of the console. */
    public static void toFile(Path file) {
        Mapnik.check(N.mapnik_log_use_file(file.toString()));
    }

    /** Write the log to the console again. Also closes the file from {@link #toFile}, so its text is complete. */
    public static void toConsole() {
        N.mapnik_log_use_console();
    }
}
