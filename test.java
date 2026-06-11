public class test {
    public static void main(String[] args) {
        boolean isVisible = false;
        String id = "";
        String type = "";
        String size = "";
        String ttl = "";
        String ttlColor = "#BBBBBB";
        int i = 0;
        String rowMarkup = String.format(
                "Group { " +
                "  Visible: %b; Anchor: (Height: 45, Bottom: 8); Padding: (Left: 20, Right: 20); LayoutMode: Left; Background: #181818; " +
                "  Label { Text: \"%s\"; FlexWeight: 3; Style: (FontSize: 15, TextColor: #FFFFFF, RenderBold: true, VerticalAlignment: Center); } " +
                "  Label { Text: \"%s\"; FlexWeight: 3; Style: (FontSize: 14, TextColor: #BBBBBB, VerticalAlignment: Center); } " +
                "  Label { Text: \"%s\"; FlexWeight: 1; Style: (FontSize: 14, TextColor: #BBBBBB, VerticalAlignment: Center); } " +
                "  Label { Text: \"%s\"; FlexWeight: 1; Style: (FontSize: 14, TextColor: %s, VerticalAlignment: Center); } " +
                "  Group { LayoutMode: Right; FlexWeight: 2; " +
                "    Button #BtnDelete_%d { Anchor: (Height: 30, Right: 10); Padding: (Left: 12, Right: 12); Background: #331111; " +
                "      Label { Text: \"Delete\"; Style: (TextColor: #FF5555, HorizontalAlignment: Center, VerticalAlignment: Center); } " +
                "    } " +
                "  } " +
                "}", 
                isVisible, id, type, size, ttl, ttlColor, i);
        System.out.println(rowMarkup);
    }
}
