package gamecompat;
import java.util.List;
/** The map's optional zone panel is absent until its list has arrived. */
public final class UiState {
    private UiState() {}
    public static boolean nullOrEmpty(List<?> values) {return values==null||values.isEmpty();}
}
