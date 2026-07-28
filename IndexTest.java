import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class IndexTest {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:test_idx.db")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE elements (id TEXT, type TEXT, json TEXT)");
            }
            String sql = "CREATE INDEX IF NOT EXISTS idx_test ON elements(json_extract(json, '$.' || ?)) WHERE type = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, "myfield");
                pstmt.setString(2, "mytype");
                pstmt.execute();
                System.out.println("Success");
            }
        }
    }
}
