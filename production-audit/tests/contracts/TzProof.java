import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Runtime proof for finding F-039: the PostgreSQL JDBC driver sends the JVM
 * default timezone as the 'TimeZone' startup parameter
 * (ConnectionFactoryImpl.createPostgresTimeZone()), and PostgreSQL validates
 * that parameter against pg_timezone_names, which does NOT contain
 * backward-compatibility links such as 'Asia/Saigon'.
 *
 * Usage: java TzProof <url> <user> <password>
 */
public class TzProof {
    public static void main(String[] args) {
        String url = args[0];
        String user = args[1];
        String pass = args[2];
        System.out.println("JVM default TimeZone.getDefault().getID() = "
                + java.util.TimeZone.getDefault().getID());
        System.out.println("Connecting to " + url);
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", pass);
        try (Connection c = DriverManager.getConnection(url, p)) {
            System.out.println("RESULT: CONNECTED");
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SHOW TimeZone")) {
                rs.next();
                System.out.println("server TimeZone = " + rs.getString(1));
            }
        } catch (Exception e) {
            System.out.println("RESULT: FAILED -> " + e.getMessage());
        }
    }
}
