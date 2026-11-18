package szserver.client;

import java.util.Map;

/** Installed factory registrations, including resource waters and the developer water block.
 * 1621282299 delegates to the water constructor; 683052146.assert passes the water singleton.
 * IDs/classes are checked against the captured registry by ActorFluidTests. */
final class NativeActorFluids {
    static final Map<Integer,String> CUSTOM_WATER=Map.of(4019,"1621282299",4020,"1621282299",
            4021,"1621282299",4027,"1621282299",4095,"820692324");
    static boolean water(int id){return id==9||CUSTOM_WATER.containsKey(id);}
    static boolean water(int id,String type){return type.equals(CUSTOM_WATER.get(id));}
    private NativeActorFluids(){}
}
