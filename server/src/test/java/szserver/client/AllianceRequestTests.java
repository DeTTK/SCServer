package szserver.client;
import szserver.net.*;import java.io.*;import java.util.*;
public final class AllianceRequestTests {
    public static void main(String[] args)throws Exception{
        int checks=0;for(String name:List.of("stalker","stalkers","bandit","bandits","duty","freedom","merc","covenant")){
            byte[] body=Wire.bytes(out->out.writeUTF(name));String expected=name.equals("stalkers")?"stalker":name.equals("bandits")?"bandit":name;
            if(!AlliancePackets.faction(body).equals(expected))throw new AssertionError(name);checks++;
        }
        for(byte[] body:List.of(new byte[0],new byte[]{0,10,1},new byte[97],Wire.bytes(out->out.writeUTF("nobody")),Wire.bytes(out->{out.writeUTF("duty");out.writeByte(1);})))try{AlliancePackets.faction(body);throw new AssertionError("invalid faction request accepted");}catch(IOException expected){checks++;}
        if(GameplayPacketAliases.registered(AlliancePackets.JOIN)!=1232||GameplayPacketAliases.registered("1546300517")!=0)throw new AssertionError("native dynamic aliases");checks++;
        if(!AlliancePackets.request(1232)||AlliancePackets.request(1560)||AlliancePackets.request(1231))throw new AssertionError("request dispatch");checks++;
        System.out.println("PASS "+checks+" native faction request validation and registered/dynamic dispatch checks");
    }
}
