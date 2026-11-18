package szserver.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native chat has separate channel, rich sender label and sender identity. */
public final class ChatPackets {
    public static final int MESSAGE_BYTES=512,COMMAND_BYTES=16*1024,SYSTEM_BYTES=32*1024;
    private static int utf8(String text){
        for(int i=0;i<text.length();i++)if(Character.isSurrogate(text.charAt(i))){
            if(!Character.isHighSurrogate(text.charAt(i))||i+1==text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("invalid_chat_unicode");
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
    public static String request(byte[] body)throws IOException{
        var in=Wire.input(body);String text=in.readUTF();byte[] tail=in.readAllBytes();
        if(!(tail.length==0||tail.length==1&&(tail[0]==0||tail[0]==1)||tail.length==2&&tail[0]==0&&tail[1]==0))throw new IOException("invalid_chat_message_tail");
        int limit=text.toLowerCase(Locale.ROOT).startsWith("/gif ")||AccountIdentity.strip(text).startsWith("/{\"")?COMMAND_BYTES:MESSAGE_BYTES;
        if(text.isEmpty()||utf8(text)>limit)throw new IllegalArgumentException("invalid_chat_message_length");
        if(text.codePoints().anyMatch(c->c<32&&c!='\t'))throw new IllegalArgumentException("invalid_chat_message_characters");
        return text;
    }
    private static byte[] frame(String text,String sender,long timestamp){
        return Wire.dynamic("1375503099",Wire.bytes(o->{
            o.writeBoolean(true);o.writeInt(sender==null?0:12);
            if(sender==null)o.writeByte(255);else{o.writeByte(0);o.writeByte(255);o.writeUTF(sender);}
            o.writeUTF(sender==null?"":sender);o.writeByte(0);o.writeByte(255);o.writeUTF(text);
            o.writeLong(timestamp);o.writeUTF("szjava");o.writeByte(0);o.writeInt(0);
        }));
    }
    public static byte[] global(String sender,String text,long timestamp){
        if(sender==null||sender.isEmpty()||utf8(sender)>128||sender.codePoints().anyMatch(c->c<32))throw new IllegalArgumentException("invalid_server_chat_sender");
        if(text.isEmpty()||utf8(text)>MESSAGE_BYTES||text.codePoints().anyMatch(c->c<32&&c!='\t'&&c!='\n'))throw new IllegalArgumentException("invalid_server_chat_message");
        return frame(text,sender,timestamp);
    }
    /** A complete bounded reply stream is admitted atomically by the reliable lane. */
    public static byte[] system(String text,long timestamp){
        Objects.requireNonNull(text);utf8(text);List<byte[]> frames=new ArrayList<>();StringBuilder part=new StringBuilder();int bytes=0,total=0;
        for(int at=0;at<text.length();){int cp=text.codePointAt(at);at+=Character.charCount(cp);if(cp<32&&cp!='\t'&&cp!='\n')cp=' ';
            int n=cp<=127?1:cp<=2047?2:cp<=65535?3:4;
            if(total+n>SYSTEM_BYTES-3){if(bytes+3>MESSAGE_BYTES){frames.add(frame(part.toString(),null,timestamp));part.setLength(0);}part.append('…');break;}
            if(bytes+n>MESSAGE_BYTES){frames.add(frame(part.toString(),null,timestamp));part.setLength(0);bytes=0;}
            part.appendCodePoint(cp);bytes+=n;total+=n;
        }
        if(!part.isEmpty())frames.add(frame(part.toString(),null,timestamp));return Wire.concat(frames.toArray(byte[][]::new));
    }
    public static String unknown(String verb){return "Неизвестная команда: "+verb+". Используйте /help.";}
    private ChatPackets(){}
}
