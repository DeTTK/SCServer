package szserver.net;

import java.util.UUID;
import scvehicles.protocol.VehicleAction;
import scvehicles.protocol.VehicleSeats;
import scvehicles.protocol.VehicleTypes;
import scvehicles.protocol.VehicleWire;

public final class VehicleLifecycleTests {
    public static void main(String[] args) {
        var vehicles = new Vehicles<Object>();
        Object driver = new Object();
        Object passenger = new Object();
        VehicleWire request = new VehicleWire();
        request.model = VehicleTypes.COUNT - 1;
        request.x = 1000;
        double[] player = {10, 20, 30};
        for (int i = 0; i < 40; i++) {
            request.id = UUID.randomUUID();
            vehicles.spawn(driver, request, player, i);
        }
        var car = vehicles.get(request.id);
        check(vehicles.all().size() == 40, "spawn count");
        check(car.state.x == player[0] && car.state.z > player[2], "spawn uses the server position");
        check(vehicles.spawn(driver, request, player, 40) == car, "duplicate spawn");

        request.model = 0;
        request.id = UUID.randomUUID();
        car = vehicles.spawn(driver, request, player, 41);
        vehicles.enter(driver, request.id, 0, 42);
        check(VehicleSeats.valid(request.model, 1), "test model has a passenger seat");
        vehicles.enter(passenger, request.id, 1, 42);
        car.state.speed = 30;
        double[] feet = VehicleAction.fallbackExit(car.state, 1);
        check(Math.abs(feet[0] - car.state.x) > VehicleTypes.WIDTH[0] / 2, "fallback is outside the vehicle");
        vehicles.exit(car, passenger);
        check(vehicles.driving(passenger) == null && vehicles.driving(driver) == car, "moving passenger exit");
        vehicles.enter(passenger, request.id, 1, 43);
        vehicles.exit(car, driver);
        check(vehicles.driving(driver) == null && car.simulator == driver, "moving driver exit");
        vehicles.enter(driver, request.id, 0, 44);
        check(vehicles.remove(request.id).occupants().size() == 2, "remove returns all occupants for release");
        check(vehicles.driving(driver) == null && vehicles.driving(passenger) == null, "remove clears both seats");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
