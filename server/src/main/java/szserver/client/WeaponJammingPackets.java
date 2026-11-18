package szserver.client;
import java.io.IOException;
/** Installed client request: native1940852589/numeric1306 has an empty payload. */
public final class WeaponJammingPackets {
    public static final int ID=1306;public static final String REQUEST="1940852589";
    public static void read(byte[] body)throws IOException{if(body.length!=0)throw new IOException("unjamming_request_size");}
    private WeaponJammingPackets(){}
}
