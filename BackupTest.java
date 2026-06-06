import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class BackupTest {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:test_bak.db")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE t (id INT)");
                stmt.execute("INSERT INTO t VALUES (1)");
                stmt.executeUpdate("backup to test_bak_backup.db");
                System.out.println("Backup successful!");
            }
        }
    }
}
