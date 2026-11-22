package szserver.client;

import java.util.Map;

/** Explicit current-client aliases accepted by the active old gameplay handlers.
 * A dynamic envelope enters the same registered handler, including its bounds,
 * permissions, inventory admission and durable acknowledgements. */
public final class GameplayPacketAliases {
    private static final Map<String,Integer> IDS=Map.ofEntries(
        Map.entry(WorldItemPackets.DROP,WorldItemPackets.DROP_ID),
        Map.entry(PortalPackets.INTERACT_CLASS,PortalPackets.INTERACT_ID),
        Map.entry(PortalPackets.STATUS_CLASS,PortalPackets.STATUS_ID),
        Map.entry("23110596",212),Map.entry("1932940545",1527),
        Map.entry("1859052360",1449),Map.entry("953021737",366),
        Map.entry("446599111",598),Map.entry("1950979903",1138),
        Map.entry("1995052022",271),Map.entry("1732415882",280),
        Map.entry("1603754623",928),Map.entry("1619370828",240),
        Map.entry("1308160046",1022),Map.entry("365897350",1435),
        Map.entry("1166767938",693),Map.entry("1980213750",1741),Map.entry("1615909539",695),Map.entry("1048773550",466),
        Map.entry("1879366252",2060),Map.entry("1945580517",562),Map.entry("1174074903",ScreenshotPackets.RESPONSE));
    public static int registered(String name){return IDS.getOrDefault(name,0);}
    public static Map<String,Integer> entries(){return IDS;}
    private GameplayPacketAliases(){}
}
