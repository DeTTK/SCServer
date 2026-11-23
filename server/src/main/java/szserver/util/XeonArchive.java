package szserver.util;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Indexed original game archive. Only requested entries are decompressed; no file extraction. */
public final class XeonArchive {
    private record Entry(int size,int packed,int offset){}
    private final byte[] data;private final Map<String,Entry> entries;
    public XeonArchive(Path path)throws IOException {
        long length=Files.size(path);if(length<16||length>256L*1024*1024||length%16!=0)throw new IOException("xeon_archive_size");
        try {
            Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
            // Format constants used by the original game; not an account or signing credential.
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(HexFormat.of().parseHex("1d6fd3d1f32b78d71e4758fd10c764ac"),"AES"),new IvParameterSpec(HexFormat.of().parseHex("6d57c656e19a02e9c86d02e4fdb1975d")));
            data=cipher.doFinal(Files.readAllBytes(path));
        }catch(java.security.GeneralSecurityException error){throw new IOException("xeon_decryption",error);}
        try {
            ByteBuffer in=ByteBuffer.wrap(data);int start=in.getInt(),count=in.getInt();
            if(start<8||start>data.length||count<0||count>100000)throw new IOException("xeon_index_limit");
            Map<String,Entry> index=new LinkedHashMap<>();
            for(int i=0;i<count;i++){
                int size=Short.toUnsignedInt(in.getShort());if(size<1||size>4096||size>in.remaining())throw new IOException("xeon_name_size");byte[] name=new byte[size];in.get(name);
                String key=safe(new String(name,StandardCharsets.UTF_8));int decoded=in.getInt(),packed=in.getInt(),offset=in.getInt();long absolute=(long)start+offset;
                if(decoded<0||decoded>64*1024*1024||packed<0||offset<0||absolute>data.length-packed)throw new IOException("xeon_entry_bounds");
                if(index.put(key,new Entry(decoded,packed,(int)absolute))!=null)throw new IOException("xeon_duplicate_entry");
            }
            if(in.position()>start)throw new IOException("xeon_index_overlap");entries=Collections.unmodifiableMap(index);
        }catch(BufferUnderflowException error){throw new IOException("xeon_truncated_index",error);}
    }
    public static String safe(String name)throws IOException {
        if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.indexOf('\0')>=0)throw new IOException("xeon_entry_path");
        for(String p:name.split("/",-1))if(p.isEmpty()||p.equals(".")||p.equals(".."))throw new IOException("xeon_entry_path");return name;
    }
    public Set<String> names(){return entries.keySet();}
    public boolean contains(String name){return entries.containsKey(name);}
    public int size(String name)throws IOException{Entry e=entries.get(name);if(e==null)throw new NoSuchFileException(name);return e.size;}
    public byte[] read(String name)throws IOException{Entry e=entries.get(name);if(e==null)throw new NoSuchFileException(name);return decompress(data,e.offset,e.packed,e.size);}
    static byte[] decompress(byte[] source,int offset,int size,int expected)throws IOException {
        byte[] result=new byte[expected];int at=offset,end=offset+size,out=0;
        while(at<end){int token=source[at++]&255,literal=token>>>4;
            if(literal==15){int add;do{if(at>=end)throw new IOException("xeon_lz4_literal");add=source[at++]&255;if(literal>expected-add)throw new IOException("xeon_lz4_limit");literal+=add;}while(add==255);}
            if(literal>end-at||literal>expected-out)throw new IOException("xeon_lz4_literal_bounds");System.arraycopy(source,at,result,out,literal);at+=literal;out+=literal;
            if(at==end)break;if(at>end-2)throw new IOException("xeon_lz4_offset");int distance=(source[at]&255)|((source[at+1]&255)<<8);at+=2;
            if(distance==0||distance>out)throw new IOException("xeon_lz4_backref");int match=(token&15)+4;
            if((token&15)==15){int add;do{if(at>=end)throw new IOException("xeon_lz4_match");add=source[at++]&255;if(match>expected-add)throw new IOException("xeon_lz4_limit");match+=add;}while(add==255);}
            if(match>expected-out)throw new IOException("xeon_lz4_match_bounds");for(int i=0;i<match;i++){result[out]=result[out-distance];out++;}
        }
        if(out!=expected||at!=end)throw new IOException("xeon_lz4_size");return result;
    }
}
