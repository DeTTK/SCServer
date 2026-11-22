package szserver.client;
import java.io.*;import java.util.*;import szserver.net.*;
/** Native selection request used for both base sides and subfactions in the stock screen. */
public final class AlliancePackets {
    public static final String JOIN="1663926674";
    public static final int JOIN_ID=1232;
    private AlliancePackets(){}
    public static boolean request(int id){return id==JOIN_ID;}
    public static String faction(byte[] body)throws IOException{
        if(body.length>96)throw new IOException("alliance_request_size");DataInputStream in=Wire.input(body);String name=in.readUTF();if(in.available()!=0||name.isBlank()||name.length()>32)throw new IOException("alliance_request_payload");
        name=name.toLowerCase(Locale.ROOT);if(name.equals("stalkers"))name="stalker";else if(name.equals("bandits"))name="bandit";
        try{return Factions.normalize(name);}catch(IllegalArgumentException invalid){throw new IOException("alliance_request_faction",invalid);}
    }
}
