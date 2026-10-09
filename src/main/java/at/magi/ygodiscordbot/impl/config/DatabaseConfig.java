package at.magi.ygodiscordbot.impl.config;

/**
 * Connection settings for the decklist database.
 *
 * @param url      JDBC URL, e.g. {@code jdbc:mysql://host:3306/database}
 * @param user     null if the URL carries the credentials
 * @param password null if the URL carries the credentials
 */
public record DatabaseConfig(String url, String user, String password) {

    /** URL without credentials ({@code jdbc:mysql://user:password@host/...} is valid too), safe to log. */
    public String safeUrl() {
        return url.replaceFirst("//[^/@]*@", "//***@");
    }

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "DatabaseConfig[url=" + safeUrl() + ", user=" + user + "]";
    }
}
