package at.magi.ygodiscordbot.impl.config;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class DatabaseConfigTest {

    @DataProvider
    public Object[][] urls() {
        return new Object[][]{
                {"jdbc:mysql://u:secret@host:3306/db", "jdbc:mysql://***@host:3306/db"},
                {"jdbc:mysql://host/db?user=x&password=secret", "jdbc:mysql://host/db?user=x&password=***"},
                {"jdbc:mysql://host/db?password=secret&useSSL=true", "jdbc:mysql://host/db?password=***&useSSL=true"},
                {"jdbc:sqlserver://host;user=x;password=secret;db=a", "jdbc:sqlserver://host;user=x;password=***;db=a"},
                {"jdbc:mysql://host/db?PassWord=secret", "jdbc:mysql://host/db?PassWord=***"},
                {"jdbc:mysql://host/db?pwd=secret", "jdbc:mysql://host/db?pwd=***"},
                {"jdbc:mysql://host:3306/db", "jdbc:mysql://host:3306/db"},
        };
    }

    @Test(dataProvider = "urls")
    public void safeUrlMasksSecrets(String url, String expected) {
        assertEquals(new DatabaseConfig(url, null, null).safeUrl(), expected);
    }

    @Test
    public void describeHidesRawUrlInExceptionMessage() {
        String url = "jdbc:mysql://host/db?user=x&password=secret";
        DatabaseConfig config = new DatabaseConfig(url, "x", "secret");
        String text = config.describe(new IllegalStateException("Failed to get driver instance for jdbcUrl=" + url));
        assertFalse(text.contains("secret"), text);
        assertTrue(text.contains("IllegalStateException"), text);
        assertTrue(text.contains("password=***"), text);
    }
}
