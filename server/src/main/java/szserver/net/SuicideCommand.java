package szserver.net;

import java.util.*;

/** Local ascent command aliases. The pause-menu give-up button sends the same empty packet. */
public final class SuicideCommand {
    public static final int PACKET_ID=1746;public static final String REQUEST="907123902";
    private SuicideCommand(){}
    public static boolean matches(String text){return Set.of("/suicide","/kill").contains(CommandSupport.verb(text));}
    public static void parse(String text){if(!matches(text)||CommandSupport.split(text).size()!=1)throw new IllegalArgumentException("/suicide");}
}
