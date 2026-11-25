package gamecompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

public final class WorldMapView {
    private static final String LOCAL_WORLD = "java-arena";
    private static final String FULL = "full";
    private static final int TILE_SIZE = 512;
    private static final ClassLoader LOADER = WorldMapView.class.getClassLoader();
    private static volatile String locationName;
    private static final Method WORLD_KEY = method(nativeClass("1033775694"), "static", String.class, true);

    private WorldMapView() {}

    public static boolean suppressMap(boolean nativeSuppression) {
        return nativeSuppression && !localWorld();
    }

    public static Object title(Object indexed, Object plain) {
        if (!localWorld()) return indexed;
        locationName = plain == null ? null : (String) invoke(TitleComponent.TEXT, plain);
        return plain;
    }

    public static void presence(Object builder,Object state) {
        String title = locationName;
        if (title != null && state != null && !"112793557".equals(state.getClass().getName()) && localWorld())
            invoke(Presence.SET_STATE,builder,title);
    }

    private static final class TitleComponent {
        private static final Method TEXT = method(nativeClass("595713561"),"throws",String.class,false);
    }

    private static final class Presence {
        private static final Class<?> BUILDER = nativeClass("com.jagrosh.discordipc.entities.RichPresence$Builder");
        private static final Method SET_STATE = method(BUILDER,"setState",BUILDER,false,String.class);
    }

    public static Object page(Object pages, float x, float z) {
        return localWorld() ? fullPage(pages) : invoke(MapPages.AT_POSITION, pages, x, z);
    }

    public static boolean selectRadarPage(Object renderer, Object pages) {
        if (!localWorld()) return false;
        Object page = fullPage(pages);
        // The radar normally retains a valid regional page even after its selection rules change.
        if (value(Radar.PAGE, renderer) != page) invoke(Radar.SET_PAGE, renderer, page, false);
        return true;
    }

    private static Object fullPage(Object pages) {
        var available = (Map<?, ?>) value(MapPages.PAGES, pages);
        Object configured = available.get(FULL);
        return configured == null ? MapPages.FULL_PAGE : configured;
    }

    private static final class Radar {
        private static final Class<?> RENDERER = nativeClass("1743927504");
        private static final Field PAGE = field(RENDERER, "extends");
        private static final Method SET_PAGE = method(RENDERER, "instanceof", void.class, false,
            MapPages.PAGE_TYPE, boolean.class);
    }

    private static boolean localWorld() {
        return LOCAL_WORLD.equals(invoke(WORLD_KEY, null));
    }

    private static final class MapPages {
        private static final Class<?> PAGE_TYPE = nativeClass("1226758656");
        private static final Method AT_POSITION = method(nativeClass("508758966"), "throws", PAGE_TYPE, false, float.class, float.class);
        private static final Field PAGES = field(nativeClass("508758966"), "synchronized");
        private static final Object FULL_PAGE = fullPage();

        private static Object fullPage() {
            Class<?> point = nativeClass("921590923");
            try {
                Constructor<?> vector = point.getConstructor(float.class, float.class);
                // Bounds of the distributed pda:map tiles. A plain page uses that native tile source.
                Object start = vector.newInstance(-86f * TILE_SIZE, -38f * TILE_SIZE);
                Object finish = vector.newInstance(133f * TILE_SIZE, 63f * TILE_SIZE);
                return PAGE_TYPE.getConstructor(String.class, int.class, point, point).newInstance(FULL, 128, start, finish);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot construct native full map", error);
            }
        }
    }

    private static Class<?> nativeClass(String name) {
        try {
            return Class.forName(name, false, LOADER);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Method method(Class<?> type, String name, Class<?> result, boolean staticMethod, Class<?>... arguments) {
        return Arrays.stream(type.getMethods()).filter(candidate -> candidate.getName().equals(name)
            && candidate.getReturnType() == result && Modifier.isStatic(candidate.getModifiers()) == staticMethod
            && Arrays.equals(candidate.getParameterTypes(), arguments)).findFirst().orElseThrow();
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Object value(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
