import com.cookie.caskara.Caskara;
import com.cookie.caskara.annotations.Index;
import java.io.File;

@Index("name")
class IndexedEntity {
    String id;
    String name;
}

public class IndexTestFile {
    public static void main(String[] args) {
        try {
            Caskara.init(new File("test_idx_dir"));
            Caskara.core(IndexedEntity.class);
            System.out.println("INDEX SUCCESS");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
