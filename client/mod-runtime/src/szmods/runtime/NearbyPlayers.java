package szmods.runtime;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record NearbyPlayers(int radius, int count, List<String> online, List<Marker> markers) {
    record Marker(String name, int x, int z, short yaw, boolean teammate) {}
    private record View(Object connection, String label, NearbyPlayers data, List<?> rows, List<NativeMarker> arrows) {}
    private record NativeMarker(Object arrow, Object vector) {}
    private static volatile View view;
    private static boolean hudError, mapError, onlineError;
    private static volatile int onlineRevision = Integer.MIN_VALUE;

    public static NearbyPlayers read(DataInput input) throws IOException {
        if (input.readUnsignedByte() != 1) throw new IOException("Unknown nearby players packet version");
        int radius = input.readUnsignedShort();
        int nearby = count(input);
        if (radius < 1 || radius > 512) throw new IOException("Invalid nearby players radius");
        int onlineCount = count(input);
        List<String> online = new ArrayList<>(onlineCount);
        for (int i = 0; i < onlineCount; i++) online.add(input.readUTF());
        int markerCount = count(input);
        List<Marker> markers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            markers.add(new Marker(input.readUTF(), input.readInt(), input.readInt(), input.readShort(), input.readBoolean()));
        }
        return new NearbyPlayers(radius, nearby, List.copyOf(online), List.copyOf(markers));
    }

    private static int count(DataInput input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 32) throw new IOException("Invalid player count");
        return value;
    }

    public void play(Object connection) {
        var previous = view;
        List<?> rows = List.of();
        List<NativeMarker> arrows = List.of();
        try {
            rows = previous != null && previous.data.online.equals(online) ? previous.rows : nativeRows();
        } catch (ReflectiveOperationException | LinkageError error) {
            if (!onlineError) System.err.println("[Online players] List unavailable: " + error);
            onlineError = true;
        }
        try {
            arrows = previous != null && previous.data.markers.equals(markers) ? previous.arrows : nativeMarkers();
        } catch (ReflectiveOperationException | LinkageError error) {
            if (!mapError) System.err.println("[Map players] Markers unavailable: " + error);
            mapError = true;
        }
        if (previous == null || !previous.data.online.equals(online)) onlineRevision++;
        view = new View(connection, "Игроков рядом: " + count + " · " + radius + " м", this, rows, arrows);
    }

    static void connected(Object connection) {
        var current = view;
        if (current != null && current.connection() != connection) view = null;
    }

    public static List<?> online(List<?> original, Object category) {
        var current = view;
        if (current == null || onlineError) return original;
        return category instanceof Enum<?> kind
            && (kind.name().equals("ONLINE_FRIENDS") || kind.name().equals("ALL_FRIENDS")) ? current.rows : List.of();
    }

    public static boolean onlineAvailable(Object nativeFriends) {
        return nativeFriends != null || view != null && !onlineError;
    }

    public static List<?> onlinePanel(Object nativeFriends,Object state) {
        var current = view;
        if (current != null && !onlineError) return current.rows;
        try {
            return (List<?>) NativeRows.FRIENDS.invoke(nativeFriends,state);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Cannot read native online list",error);
        }
    }

    public static Object onlineRevision(Object original) {
        var current = view;
        return current == null || onlineError ? original : Integer.valueOf(onlineRevision);
    }

    public static Object profileButton(Object screen, String name, int mode, boolean variant) {
        try {
            var current = view;
            if (current == null || onlineError || !current.data.online.contains(name))
                return NativeRows.ACTIONS.invoke(null,screen,name,mode,variant);
            Object button = NativeRows.BUTTON.invoke(null,screen,NativeRows.PROFILE_ICON,"Открыть профиль");
            return NativeRows.CLICK.invoke(button,(java.util.function.Consumer<Object>)ignored -> PlayerProfile.request(name));
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Cannot create player profile button",error);
        }
    }

    public static void render(Object font, int x, int y) {
        var current = view;
        if (current == null || hudError) return;
        try {
            NativeFont.DRAW.invoke(font, current.label(), x, y, 0xffe8edf2);
        } catch (ReflectiveOperationException | LinkageError error) {
            hudError = true;
            System.err.println("[Nearby players] HUD unavailable: " + error);
        }
    }

    public static void renderMap(Object renderer) {
        var current = view;
        if (current == null || current.arrows.isEmpty() || mapError) return;
        try {
            Object page = NativeMap.PAGE.get(renderer);
            if (page == null || !"full".equals(NativeMap.PAGE_ID.get(page))) return;
            Object projected = NativeMap.SCREEN_POINT;
            for (var marker : current.arrows) {
                NativeMap.PROJECT.invoke(renderer, marker.vector(), projected);
                float x = NativeMap.X.getFloat(projected), z = NativeMap.Z.getFloat(projected);
                if (Boolean.TRUE.equals(NativeMap.VISIBLE.invoke(renderer,x,z,6f,6f))) {
                    NativeMap.DRAW.invoke(marker.arrow(),renderer,projected,true);
                }
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            mapError = true;
            System.err.println("[Map players] Drawing unavailable: " + error);
        }
    }

    private List<?> nativeRows() throws ReflectiveOperationException {
        var rows = new ArrayList<>(online.size());
        for (String name : online) {
            Object row = NativeRows.ROW.newInstance(name, name, null, NativeRows.STATUS, new byte[0]);
            NativeRows.AVATAR.set(row, NativeRows.DEFAULT_AVATAR.invoke(null));
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<NativeMarker> nativeMarkers() throws ReflectiveOperationException {
        var arrows = new ArrayList<NativeMarker>(markers.size());
        for (Marker marker : markers) {
            Object player = NativeMap.PLAYER.newInstance(marker.name);
            NativeMap.HEADING.setFloat(player, marker.yaw);
            NativeMap.COLOR.setInt(player, marker.teammate ? 1 : 0);
            Object arrow = NativeMap.MARKER.newInstance(player, false);
            Object vector = NativeMap.VECTOR.newInstance((float)marker.x, (float)marker.z);
            arrows.add(new NativeMarker(arrow, vector));
        }
        return List.copyOf(arrows);
    }

    private static final class NativeFont {
        static final Method DRAW;
        static {
            try {
                DRAW = FunnelImpact.nativeMethod(Class.forName("186969209"), "static", double.class,
                    CharSequence.class, int.class, int.class, int.class);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static final class NativeMap {
        static final Constructor<?> PLAYER, MARKER, VECTOR;
        static final Field PAGE, PAGE_ID, HEADING, COLOR, X, Z;
        static final Method DRAW, VISIBLE, PROJECT;
        static final Object SCREEN_POINT;
        static {
            try {
                Class<?> player = Class.forName("1581880750");
                Class<?> marker = Class.forName("168252715");
                Class<?> vector = Class.forName("921590923");
                Class<?> renderer = Class.forName("1743927504");
                PLAYER = player.getConstructor(String.class);
                MARKER = marker.getConstructor(player, boolean.class);
                VECTOR = vector.getConstructor(float.class, float.class);
                SCREEN_POINT = VECTOR.newInstance(0f,0f);
                X = vector.getField("import");
                Z = vector.getField("try");
                PROJECT = FunnelImpact.nativeMethod(renderer,"finally",vector,vector,vector);
                PAGE = renderer.getField("extends");
                PAGE_ID = PAGE.getType().getField("goto");
                HEADING = player.getField("float");
                COLOR = player.getField("finally");
                DRAW = FunnelImpact.nativeMethod(marker, "static", void.class, renderer, vector, boolean.class);
                VISIBLE = FunnelImpact.nativeMethod(renderer, "default", boolean.class, float.class, float.class, float.class, float.class);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static final class NativeRows {
        static final Constructor<?> ROW;
        static final Object STATUS;
        static final Field AVATAR;
        static final Method DEFAULT_AVATAR, ACTIONS, BUTTON, CLICK, FRIENDS;
        static final Object PROFILE_ICON;
        static {
            try {
                Class<?> row = Class.forName("1870823086");
                Class<?> status = Class.forName("1503540580");
                FRIENDS = FunnelImpact.nativeMethod(Class.forName("87898397"),"package",List.class,status);
                ROW = row.getConstructor(String.class, String.class, Instant.class, status, byte[].class);
                STATUS = status.getField("FRIEND").get(null);
                AVATAR = row.getField("int");
                DEFAULT_AVATAR = FunnelImpact.nativeMethod(AVATAR.getType(), "long", AVATAR.getType());
                Class<?> actions = Class.forName("1705320572"), parent = Class.forName("1897181654");
                Class<?> button = Class.forName("1224329780"), icon = Class.forName("779455578");
                ACTIONS = FunnelImpact.nativeMethod(actions,"throws",Class.forName("272165572"),parent,String.class,int.class,boolean.class);
                BUTTON = FunnelImpact.nativeMethod(actions,"else",button,parent,icon,String.class);
                CLICK = FunnelImpact.nativeMethod(button,"package",button,java.util.function.Consumer.class);
                PROFILE_ICON = Class.forName("1322246406").getField("abstract").get(null);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }
}
