package szserver.client;

import szserver.inventory.Inventory;

/** Both native nullable markers still carry a normal equipped-slot selection. */
public final class HandPacketTests {
    public static void main(String[] args) throws Exception {
        for (int slot : new int[]{HandPackets.PRIMARY, HandPackets.SECONDARY, HandPackets.MELEE,
                HandPackets.GRENADE, HandPackets.BOLT}) {
            var expected = new HandPackets.Selection(slot, false);
            check(expected.equals(selection(new byte[]{0, (byte)slot, 0, 0})));
            check(expected.equals(selection(new byte[]{0, 0, (byte)slot, 0, 0})));
            check(expected.equals(selection(new byte[]{0, 1, 0, (byte)slot, 0, 0})));
        }

        for (byte[] body : new byte[][]{{1, 0, 0, 7}, {0, 1, 0, 0, 7}, {0, 1, 1, 0, 0, 7}}) {
            var address = HandPackets.readOverride(body);
            check(address.equals(new Inventory.Address(1, 0, 7)));
            check(HandPackets.equippedSelection(address) == null);
        }
        check(HandPackets.readOverride(new byte[]{1}) == null);
        check(selection(new byte[]{0, 1, 0, 1, 0, 0}) == null);
        System.out.println("PASS equipped and temporary hand selection across native address encodings");
    }

    private static HandPackets.Selection selection(byte[] body) throws Exception {
        return HandPackets.equippedSelection(HandPackets.readOverride(body));
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("native hand routing");
    }
}
