package scvehicles;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import szmods.api.ClientMod;

public final class PackageTest {
    public static void main(String[] args) throws Exception {
        Properties descriptor = new Properties();
        try (InputStream input = VehicleMod.class.getResourceAsStream("/stalcraft-mod.properties")) {
            descriptor.load(input);
        }
        Class.forName(descriptor.getProperty("main")).asSubclass(ClientMod.class).getConstructor().newInstance();
        if (VehicleFleet.ALL.length != 26 || VehicleMotionClips.ALL.isEmpty()) {
            throw new AssertionError("Missing vehicle profiles or animations");
        }
        for (String name : List.of("SCVehicleTerrainSolver", "scvehicles.NativeVehicleCollider",
                "scvehicles.NativePlayerCoordinates", "scvehicles.NativeRampBounds")) {
            Class.forName(name, false, VehicleMod.class.getClassLoader());
        }
        for (String path : List.of("audio/fleet/tsu-1.wav", "player-bind-rig.json", "seating.json",
                "vehicle-lit.vert", "vehicle-lit.frag")) {
            if (VehicleMod.class.getResource("/scvehicles/" + path) == null) {
                throw new AssertionError("Missing packaged resource " + path);
            }
        }
    }
}
