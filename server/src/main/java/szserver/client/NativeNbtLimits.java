package szserver.client;

import java.io.*;
import java.util.*;

/** Validate sizes before native codecs allocate lists or arrays. */
final class NativeNbtLimits {
    private final DataInputStream input;
    private int nodes;
    private NativeNbtLimits(byte[] raw){input=new DataInputStream(new ByteArrayInputStream(raw));}
    static byte[] expand(byte[] raw)throws IOException{
        if(raw.length<4||raw.length>65536)throw new IOException("nbt_compressed_size");byte[] decoded;
        if((raw[0]&255)==31&&(raw[1]&255)==139){try(var gzip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(raw))){decoded=gzip.readNBytes(65537);if(decoded.length>65536)throw new IOException("nbt_expansion_limit");}}
        else if((raw[0]&255)==40&&(raw[1]&255)==181&&(raw[2]&255)==47&&(raw[3]&255)==253){long size=com.github.luben.zstd.Zstd.decompressedSize(raw);if(size<1||size>65536)throw new IOException("nbt_expansion_limit");decoded=com.github.luben.zstd.Zstd.decompress(raw,(int)size);}
        else throw new IOException("nbt_compression");validate(decoded);return decoded;
    }
    static void validate(byte[] raw)throws IOException{
        if(raw.length>65536)throw new IOException("nbt_size");NativeNbtLimits reader=new NativeNbtLimits(raw);
        if(reader.input.readUnsignedByte()!=10)throw new IOException("nbt_root");reader.text();reader.payload(10,0);if(reader.input.available()!=0)throw new IOException("nbt_trailing");
    }
    private String text()throws IOException{int length=input.readUnsignedShort();if(length>4096||length>input.available())throw new IOException("nbt_string_size");byte[] raw=input.readNBytes(length);return new String(raw,java.nio.charset.StandardCharsets.ISO_8859_1);}
    private int count()throws IOException{int count=input.readInt();if(count<0||count>8192)throw new IOException("nbt_array_count");return count;}
    private void bytes(long count)throws IOException{if(count>input.available())throw new IOException("nbt_truncated");input.skipNBytes(count);}
    private void payload(int type,int depth)throws IOException{
        if(depth>8||++nodes>2048)throw new IOException("nbt_complexity");
        switch(type){
            case 1 -> bytes(1);case 2 -> bytes(2);case 3 -> bytes(4);case 4 -> bytes(8);
            case 5 -> {if(!Float.isFinite(input.readFloat()))throw new IOException("nbt_nonfinite");}
            case 6 -> {if(!Double.isFinite(input.readDouble()))throw new IOException("nbt_nonfinite");}
            case 7 -> bytes(count());case 8 -> text();case 11 -> bytes(4L*count());
            case 9 -> {int child=input.readUnsignedByte(),length=count();if(child==0&&length!=0)throw new IOException("nbt_list_type");for(int n=0;n<length;n++)payload(child,depth+1);}
            case 10 -> {Set<String> names=new HashSet<>();for(int child;(child=input.readUnsignedByte())!=0;){if(!names.add(text()))throw new IOException("nbt_duplicate_key");payload(child,depth+1);}}
            default -> throw new IOException("nbt_tag_type");
        }
    }
}
