package szmods.runtime;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public record NearbyPlayers(List<String> online, List<Marker> markers) {
    record Marker(String name, int x, int z, short yaw, boolean teammate) {}
    private record View(Object connection, NearbyPlayers data, List<Object> arrows) {}
    private static final Map<Object,List<Object>> renderedMap = new WeakHashMap<>();
    private static volatile View view;
    private static boolean mapError, onlineError;

    public static NearbyPlayers read(DataInput input) throws IOException {
        if (input.readUnsignedByte() != 1) throw new IOException("Unknown nearby players packet version");
        int radius = input.readUnsignedShort();
        count(input); // Reserved in v1 for clients that drew the old label.
        if (radius < 1 || radius > 512) throw new IOException("Invalid nearby players radius");
        int onlineCount = count(input);
        List<String> online = new ArrayList<>(onlineCount);
        for (int i = 0; i < onlineCount; i++) online.add(input.readUTF());
        int markerCount = count(input);
        List<Marker> markers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            markers.add(new Marker(input.readUTF(), input.readInt(), input.readInt(), input.readShort(), input.readBoolean()));
        }
        return new NearbyPlayers(List.copyOf(online), List.copyOf(markers));
    }

    private static int count(DataInput input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 32) throw new IOException("Invalid player count");
        return value;
    }

    public void play(Object connection) {
        var previous = view;
        List<Object> arrows = List.of();
        try {
            if (!onlineError && (previous == null || !previous.data.online.equals(online))) publishOnline(online);
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
        view = new View(connection, this, arrows);
    }

    static void connected(Object connection) {
        var current = view;
        if (current != null && current.connection() != connection) view = null;
    }

    public static boolean localOnline() {
        return view != null && !onlineError;
    }

    public static boolean mapChanged(boolean nativeChanged,Object provider,Object renderer) {
        return nativeChanged || renderedMap.get(provider) != mapPlayers(renderer);
    }

    @SuppressWarnings("unchecked")
    public static void appendMapPlayers(Object nativeMarkers,Object provider,Object renderer) {
        var players = mapPlayers(renderer);
        ((List<Object>)nativeMarkers).addAll(players);
        renderedMap.put(provider,players);
    }

    private static List<Object> mapPlayers(Object renderer) {
        var current = view;
        if(current == null || mapError) return List.of();
        try {
            Object page = NativeMap.PAGE.get(renderer);
            return page != null && "full".equals(NativeMap.PAGE_ID.get(page)) ? current.arrows : List.of();
        } catch (ReflectiveOperationException failure) {
            mapError = true;
            System.err.println("[Map players] Native layer unavailable: " + failure);
            return List.of();
        }
    }

    private static void publishOnline(List<String> online) throws ReflectiveOperationException {
        var rows = new ArrayList<>(online.size());
        for (String name : online) rows.add(NativeRows.ROW.newInstance(name,name,null,NativeRows.STATUS,new byte[0]));
        Object response = NativeRows.RESPONSE.newInstance();
        NativeRows.ENTRIES.set(response,rows);
        Object game=NativeRows.GAME.invoke(null);
        String player=(String)NativeRows.NAME.invoke(NativeRows.ACCOUNT.invoke(game));
        NativeRows.APPLY.invoke(NativeRows.CONTROLLER.get(null),player,response);
    }

    private List<Object> nativeMarkers() throws ReflectiveOperationException {
        var arrows = new ArrayList<Object>(markers.size());
        for (Marker marker : markers) {
            if (marker.teammate) continue;
            Object player = NativeMap.PLAYER.newInstance(marker.name);
            NativeMap.HEADING.setFloat(player, marker.yaw);
            NativeMap.PLAYER_LOCATION.set(player,NativeMap.CURRENT_LOCATION.get(null));
            Object position=NativeMap.POSITION.get(player);
            NativeMap.X.setFloat(position,marker.x);
            NativeMap.Z.setFloat(position,marker.z);
            Object arrow = NativeMap.MARKER.newInstance(player, false);
            // The stock party arrow supplies positioning, drawing, clipping and hover text.
            // Broaden visibility for the all-players map without changing the local party roster.
            arrows.add(Proxy.newProxyInstance(NativeMap.MARKER_TYPE.getClassLoader(),new Class<?>[]{NativeMap.MARKER_TYPE},
                (proxy,method,args) -> {
                    if (method.equals(NativeMap.DISPLAY)) return (Float)NativeMap.ZOOM.invoke(args[0]) <= 8f;
                    if (method.getName().equals("equals")) return proxy==args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return method.invoke(arrow,args);
                }));
        }
        return List.copyOf(arrows);
    }

    private static final class NativeMap {
        static final Constructor<?> PLAYER, MARKER;
        static final Field PAGE, PAGE_ID, HEADING, POSITION, X, Z, PLAYER_LOCATION, CURRENT_LOCATION;
        static final Method DISPLAY, ZOOM;
        static final Class<?> MARKER_TYPE;
        static {
            try {
                Class<?> player = Class.forName("1581880750");
                Class<?> marker = Class.forName("168252715");
                Class<?> vector = Class.forName("921590923");
                Class<?> renderer = Class.forName("1743927504");
                PLAYER = player.getConstructor(String.class);
                MARKER = marker.getConstructor(player, boolean.class);
                X=vector.getField("import");
                Z=vector.getField("try");
                MARKER_TYPE = Class.forName("1713154782");
                DISPLAY = MARKER_TYPE.getMethod("package",renderer);
                ZOOM = FunnelImpact.nativeMethod(renderer,"try",float.class);
                PAGE = renderer.getField("extends");
                PAGE_ID = PAGE.getType().getField("goto");
                HEADING = player.getField("float");
                POSITION = player.getField("final");
                PLAYER_LOCATION = player.getField("boolean");
                CURRENT_LOCATION = Class.forName("1033775694").getField("extends");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static final class NativeRows {
        static final Constructor<?> ROW, RESPONSE;
        static final Object STATUS;
        static final Field ENTRIES, CONTROLLER;
        static final Method GAME, ACCOUNT, NAME, APPLY;
        static {
            try {
                Class<?> row = Class.forName("1870823086");
                Class<?> status = Class.forName("1503540580");
                ROW = row.getConstructor(String.class, String.class, Instant.class, status, byte[].class);
                STATUS = status.getField("FRIEND").get(null);
                Class<?> response = Class.forName("889098810");
                RESPONSE = response.getConstructor();
                ENTRIES = response.getField("interface");
                Class<?> controller = Class.forName("960191912");
                CONTROLLER = controller.getField("implements");
                APPLY = controller.getMethod("static",String.class,response);
                Class<?> game = Class.forName("1402131154");
                GAME = FunnelImpact.nativeMethod(game,"try",game);
                Class<?> account=Class.forName("961798934");
                ACCOUNT = FunnelImpact.nativeMethod(game,"throws",account);
                NAME = FunnelImpact.nativeMethod(account,"finally",String.class);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }
}
