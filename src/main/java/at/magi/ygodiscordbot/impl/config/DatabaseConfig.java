package at.magi.ygodiscordbot.impl.config;

/**
 * Connection settings for the decklist database.
 *
 * @param url      JDBC URL, e.g. {@code jdbc:mysql://host:3306/database}
 * @param user     null if the URL carries the credentials
 * @param password null if the URL carries the credentials
 */
public record DatabaseConfig(String url, String user, String password) {

    /**
     * URL without credentials, safe to log: {@code jdbc:mysql://user:password@host/...} and
     * {@code ?password=...} / {@code ;password=...} are valid too.
     */
    public String safeUrl() {
        return mask(url);
    }

    /**
     * Exception class and message for logs, followed by each cause ("A: msg ← B: msg"). Hikari puts the raw
     * URL into its messages ("Failed to get driver instance for jdbcUrl=..."), so every message is masked.
     */
    public String describe(Throwable e) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 10; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (depth > 0) {
                text.append(" ← ");
            }
            String message = t.getMessage();
            text.append(t.getClass().getSimpleName())
                    .append(message == null ? "" : ": " + mask(message.replace(url, safeUrl())));
        }
        return text.toString();
    }

    private static String mask(String text) {
        return text.replaceAll("//[^/@\\s]*@", "//***@")
                .replaceAll("(?i)(password|pwd)=[^&;\\s]*", "$1=***");
    }

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "DatabaseConfig[url=" + safeUrl() + ", user=" + user + "]";
    }
}
