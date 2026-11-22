package areaexport;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import szmods.api.*;

public final class AreaExportMod implements ClientMod {
    private Object game;
    private int[] first, second;
    private boolean wasFirst, wasSecond, wasMenu;
    private Process menu;
    private ModContext context;

    public void onLoad(ModContext context) { this.context = context; }
    public void onFrame(ClientFrame frame) { game = frame.game(); }
    public void registerKeybinds(KeybindRegistry keys) {
        keys.register("area.first", "Первый угол области", 296);
        keys.register("area.second", "Второй угол области", 297);
        keys.register("area.menu", "Экспорт области в Axiom", 298);
    }
    public void onInput(InputSnapshot input) {
        boolean a = input.keyDown(296), b = input.keyDown(297), open = input.keyDown(298);
        try {
            if (a && !wasFirst) first = position();
            if (b && !wasSecond) second = position();
            if (open && !wasMenu) openMenu();
        } catch (Exception error) {
            message("Не удалось выполнить действие. " + error.getMessage());
        } finally {
            wasFirst = a; wasSecond = b; wasMenu = open;
        }
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> t = target.getClass(); t != null; t = t.getSuperclass()) {
            try {
                Field f = t.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private int[] position() throws ReflectiveOperationException {
        if (game == null) throw new IllegalStateException("Сначала войдите в мир.");
        Object player = field(game, "e");
        if (player == null) throw new IllegalStateException("Сначала войдите в мир.");
        Class<?> api = Class.forName("576005245", false, game.getClass().getClassLoader());
        for (Method m : api.getMethods()) {
            Class<?>[] args = m.getParameterTypes();
            if (m.getName().equals("char") && m.getReturnType().getName().equals("153304200")
                && args.length == 2 && args[0].isInstance(player) && args[1] == float.class) {
                m.setAccessible(true);
                Object v = m.invoke(null, player, 1f);
                int[] p = new int[3];
                String[] axes = {"case", "void", "import"};
                for (int i=0; i<3; i++) p[i]=(int)Math.floor(((Number)field(v,axes[i])).doubleValue());
                context.log("Угол выделения " + Arrays.toString(p));
                return p;
            }
        }
        throw new IllegalStateException("Эта версия клиента не сообщает координаты. Введите их в меню F9.");
    }
    private void openMenu() throws Exception {
        if (menu != null && menu.isAlive()) return;
        Path mods = Path.of(System.getProperty("sz.mods.dir", "mods")).toAbsolutePath();
        var exe = mods.resolve("area-export/AreaExport.exe");
        if (!Files.isRegularFile(exe)) {
            var source = getClass().getProtectionDomain().getCodeSource();
            if (source != null) exe = Path.of(source.getLocation().toURI()).getParent().resolve("area-export/AreaExport.exe");
        }
        if (!Files.isRegularFile(exe)) throw new IllegalStateException("Распакуйте папку area-export рядом с JAR мода.");
        var command = new ArrayList<String>();
        command.add(exe.toString());
        for (int[] p : new int[][]{first, second}) {
            for (int n : p == null ? new int[]{0,64,0} : p) command.add(Integer.toString(n));
        }
        menu = new ProcessBuilder(command).start();
    }
    private void message(String why) {
        context.log(why);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,why,"Экспорт области",JOptionPane.WARNING_MESSAGE));
    }
}
