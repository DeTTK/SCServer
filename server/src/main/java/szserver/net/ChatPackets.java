package szserver.net;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import szserver.world.ScenePayload;

/** Native chat has separate channel, rich sender label and sender identity. */
public final class ChatPackets {
    public static final int MESSAGE_BYTES=512,COMMAND_BYTES=16*1024,SYSTEM_BYTES=32*1024;
    // The response writes the native enum ordinal; the request carries its name.
    public enum Channel {
        DEFAULT(0), COMMAND(1), RANGED(2), LOCAL(3), PARTY(5), PRIVATE(6), PRIVATE_SENT(7), GLOBAL(12);
        final int wire;
        Channel(int wire){this.wire=wire;}
    }
    public record Request(Channel channel,String text){}
    public record PrivateMessage(String recipient,String text){}
    private static int utf8(String text){
        for(int i=0;i<text.length();i++)if(Character.isSurrogate(text.charAt(i))){
            if(!Character.isHighSurrogate(text.charAt(i))||i+1==text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("invalid_chat_unicode");
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
    public static Request request(byte[] body)throws IOException{
        if(body.length>COMMAND_BYTES*2+128)throw new IOException("invalid_chat_packet_length");
        var in=Wire.input(body);
        String text=AccountIdentity.strip(in.readUTF());
        int limit=text.toLowerCase(Locale.ROOT).startsWith("/gif ")||text.startsWith("/{\"")?COMMAND_BYTES:MESSAGE_BYTES;
        if(text.isEmpty()||utf8(text)>limit)throw new IllegalArgumentException("invalid_chat_message_length");
        if(text.codePoints().anyMatch(c->c<32&&c!='\t'))throw new IllegalArgumentException("invalid_chat_message_characters");
        // Optional native NBT carries display/link data. Do not relay it as server-authored metadata.
        if(in.available()>0&&flag(in.readUnsignedByte())){
            int start=body.length-in.available();
            ByteBuffer payload=ByteBuffer.wrap(body);payload.position(start);
            try{
                if(!payload.hasRemaining()||payload.get(start)!=10)throw new IOException("invalid_chat_metadata");
                in.skipNBytes(ScenePayload.namedNbt(payload)-start);
            }catch(BufferUnderflowException|IllegalArgumentException invalid){throw new IOException("invalid_chat_metadata",invalid);}
        }
        Channel channel=Channel.DEFAULT;
        if(in.available()>0&&flag(in.readUnsignedByte())){
            try{channel=Channel.valueOf(in.readUTF());}
            catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Этот канал чата недоступен.");}
            if(channel==Channel.PRIVATE_SENT)throw new IllegalArgumentException("Этот канал чата недоступен.");
        }
        if(in.available()!=0)throw new IOException("invalid_chat_message_tail");
        if(text.startsWith("/"))return new Request(Channel.COMMAND,text);
        if(text.startsWith("@")||channel==Channel.PRIVATE)return new Request(Channel.PRIVATE,text);
        if(text.startsWith("+"))return new Request(Channel.LOCAL,message(text.substring(1)));
        if(text.startsWith("*"))return new Request(Channel.PARTY,message(text.substring(1)));
        if(text.startsWith("%")||text.startsWith("!")||channel==Channel.COMMAND)throw new IllegalArgumentException("Этот канал чата недоступен.");
        if(channel==Channel.DEFAULT||channel==Channel.RANGED)channel=Channel.GLOBAL;
        return new Request(channel,text);
    }
    private static boolean flag(int value)throws IOException{
        if(value>1)throw new IOException("invalid_chat_message_flag");
        return value==1;
    }
    private static String message(String text){
        String result=AccountIdentity.strip(text);
        if(result.isEmpty())throw new IllegalArgumentException("Сообщение пустое.");
        return result;
    }
    public static PrivateMessage privateMessage(String text){
        String value=AccountIdentity.strip(text.startsWith("@")?text.substring(1):text);
        int end=0;
        while(end<value.length()&&!Character.isWhitespace(value.charAt(end))&&value.charAt(end)!=':')end++;
        if(end==0||end==value.length())throw new IllegalArgumentException("Личное сообщение: @ник текст");
        int start=value.charAt(end)==':'?end+1:end;
        String content=AccountIdentity.strip(value.substring(start));
        if(content.isEmpty())throw new IllegalArgumentException("Личное сообщение: @ник текст");
        return new PrivateMessage(value.substring(0,end),content);
    }
    private static byte[] frame(Channel channel,String text,String sender,long timestamp){
        return Wire.dynamic("1375503099",Wire.bytes(o->{
            o.writeBoolean(true);o.writeInt(channel.wire);
            o.writeByte(255); // Optional rich prefix. The native renderer adds the identity itself.
            o.writeUTF(sender==null?"":sender);o.writeByte(0);o.writeByte(255);o.writeUTF(text);
            o.writeLong(timestamp);o.writeUTF("szjava");o.writeByte(0);o.writeInt(0);
        }));
    }
    public static byte[] player(Channel channel,String sender,String text,long timestamp){
        if(sender==null||sender.isEmpty()||utf8(sender)>128||sender.codePoints().anyMatch(c->c<32))throw new IllegalArgumentException("invalid_server_chat_sender");
        int limit=channel==Channel.PRIVATE_SENT?MESSAGE_BYTES+130:MESSAGE_BYTES;
        if(text.isEmpty()||utf8(text)>limit||text.codePoints().anyMatch(c->c<32&&c!='\t'&&c!='\n'))throw new IllegalArgumentException("invalid_server_chat_message");
        return frame(channel,text,sender,timestamp);
    }
    /** A complete bounded reply stream is admitted atomically by the reliable lane. */
    public static byte[] system(String text,long timestamp){
        Objects.requireNonNull(text);utf8(text);List<byte[]> frames=new ArrayList<>();StringBuilder part=new StringBuilder();int bytes=0,total=0;
        for(int at=0;at<text.length();){int cp=text.codePointAt(at);at+=Character.charCount(cp);if(cp<32&&cp!='\t'&&cp!='\n')cp=' ';
            int n=cp<=127?1:cp<=2047?2:cp<=65535?3:4;
            if(total+n>SYSTEM_BYTES-3){if(bytes+3>MESSAGE_BYTES){frames.add(frame(Channel.DEFAULT,part.toString(),null,timestamp));part.setLength(0);}part.append('…');break;}
            if(bytes+n>MESSAGE_BYTES){frames.add(frame(Channel.DEFAULT,part.toString(),null,timestamp));part.setLength(0);bytes=0;}
            part.appendCodePoint(cp);bytes+=n;total+=n;
        }
        if(!part.isEmpty())frames.add(frame(Channel.DEFAULT,part.toString(),null,timestamp));return Wire.concat(frames.toArray(byte[][]::new));
    }
    public static String unknown(String verb){return "Неизвестная команда: "+verb+". Используйте /help.";}
    private ChatPackets(){}
}
