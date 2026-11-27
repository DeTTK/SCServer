package szserver.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static szserver.net.ChatPackets.Channel.*;

public final class ChatPacketsTests {
    public static void main(String[] args) throws Exception {
        check(ChatPackets.request(nativeRequest("Привет", null, false)).channel()==GLOBAL);
        check(ChatPackets.request(nativeRequest("Привет", "RANGED", false)).channel()==GLOBAL);
        check(ChatPackets.request(nativeRequest("Привет", "GLOBAL", true)).channel()==GLOBAL);
        check(ChatPackets.request(nativeRequest("+ Привет", null, false)).equals(new ChatPackets.Request(LOCAL,"Привет")));
        check(ChatPackets.request(nativeRequest("Привет", "PARTY", false)).channel()==PARTY);
        check(ChatPackets.request(nativeRequest("/help", null, false)).channel()==COMMAND);
        check(ChatPackets.request(nativeRequest("@Тест: сообщение", null, false)).channel()==PRIVATE);
        check(ChatPackets.request(nativeRequest("Тест сообщение", "PRIVATE", false)).channel()==PRIVATE);
        check(ChatPackets.privateMessage("@Тест: сообщение").equals(new ChatPackets.PrivateMessage("Тест","сообщение")));
        check(ChatPackets.privateMessage("Тест сообщение").equals(new ChatPackets.PrivateMessage("Тест","сообщение")));
        rejects(nativeRequest("secret", "GUILD", false));
        rejects(nativeRequest("secret", "PRIVATE_SENT", false));
        byte[] truncated=nativeRequest("text", "GLOBAL", true);
        rejects(Arrays.copyOf(truncated,7));
        byte[] legacy=Wire.bytes(o->o.writeUTF("Старый клиент"));
        check(ChatPackets.request(legacy).channel()==GLOBAL);
        for(var channel:new ChatPackets.Channel[]{GLOBAL,LOCAL,PARTY,PRIVATE,PRIVATE_SENT}){
            byte[] frame=ChatPackets.player(channel,"Отправитель","Сообщение",123L);
            var wire=ByteBuffer.wrap(frame);
            check((Wire.varint(wire)>>>1)==wire.remaining());
            check(Wire.varint(wire)==5);
            var in=Wire.input(Arrays.copyOfRange(frame,wire.position(),frame.length));
            check(in.readUTF().equals("1375503099"));
            check(in.readBoolean()&&in.readInt()==channel.wire);
            check(in.readUnsignedByte()==255&&in.readUTF().equals("Отправитель"));
            check(in.readUnsignedByte()==0&&in.readUnsignedByte()==255&&in.readUTF().equals("Сообщение"));
        }
        System.out.println("PASS native chat request framing, privacy, channels and single nickname");
    }

    private static byte[] nativeRequest(String text,String channel,boolean metadata){
        return Wire.bytes(o->{
            o.writeUTF(text);
            o.writeBoolean(metadata);
            if(metadata){o.writeByte(10);o.writeUTF("");o.writeByte(0);}
            o.writeBoolean(channel!=null);
            if(channel!=null)o.writeUTF(channel);
        });
    }

    private static void rejects(byte[] body)throws Exception{
        try{ChatPackets.request(body);throw new AssertionError("invalid chat accepted");}
        catch(IOException|IllegalArgumentException expected){}
    }

    private static void check(boolean result){if(!result)throw new AssertionError("chat protocol");}
}
