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
     * Exception class and message for logs. Hikari puts the raw URL into its messages
     * ("Failed to get driver instance for jdbcUrl=..."), so the URL is masked there as well.
     */
    public String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + mask(message.replace(url, safeUrl())));
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
