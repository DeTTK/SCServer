package szserver.util;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
public final class XeonArchiveTests {
    private static byte[] archive(String name,byte[] body)throws Exception {
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();int n=body.length;compressed.write(Math.min(n,15)<<4);if(n>=15){int extra=n-15;while(extra>=255){compressed.write(255);extra-=255;}compressed.write(extra);}compressed.write(body);
        byte[] key=name.getBytes(java.nio.charset.StandardCharsets.UTF_8),packed=compressed.toByteArray();ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        out.writeInt(8+2+key.length+12);out.writeInt(1);out.writeShort(key.length);out.write(key);out.writeInt(body.length);out.writeInt(packed.length);out.writeInt(0);out.write(packed);
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(HexFormat.of().parseHex("1d6fd3d1f32b78d71e4758fd10c764ac"),"AES"),new IvParameterSpec(HexFormat.of().parseHex("6d57c656e19a02e9c86d02e4fdb1975d")));return cipher.doFinal(buffer.toByteArray());
    }
    public static void main(String[] args)throws Exception {
        Path tmp=Files.createTempDirectory("xeon-reader-test-");
        try {
            byte[] body="{\"native\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);Path file=tmp.resolve("configs.xeon");Files.write(file,archive("assets/test.json",body));
            XeonArchive source=new XeonArchive(file);if(!Arrays.equals(source.read("assets/test.json"),body))throw new AssertionError("archive bytes");
            Path logical=tmp.resolve("not-extracted"),overrides=tmp.resolve("overrides");GameResources.mount(logical,file,overrides);
            if(Files.exists(logical))throw new AssertionError("resource tree extracted");
            if(!GameResources.readString(logical.resolve("assets/test.json")).contains("native"))throw new AssertionError("logical read");
            try(URLClassLoader loader=new URLClassLoader(new URL[]{GameResources.url(logical)},ClassLoader.getPlatformClassLoader())){
                try(var in=loader.getResourceAsStream("assets/test.json")){if(in==null||!Arrays.equals(in.readAllBytes(),body))throw new AssertionError("native classloader resource");}
            }
            Files.createDirectories(overrides.resolve("assets"));Files.writeString(overrides.resolve("assets/test.json"),"override");if(!GameResources.readString(logical.resolve("assets/test.json")).equals("override"))throw new AssertionError("override priority");
            if(GameResources.walk(logical).count()!=1)throw new AssertionError("virtual listing");
            for(String bad:List.of("../escape","assets/../escape","/absolute","X:/escape","assets\\escape"))try{XeonArchive.safe(bad);throw new AssertionError("unsafe path");}catch(IOException expected){}
            Files.write(file,new byte[16]);try{new XeonArchive(file);throw new AssertionError("corrupt archive");}catch(IOException expected){}
            System.out.println("XeonArchiveTests PASS: direct archive, native resource URL, overrides, bounds; no extraction");
        }finally{GameResources.clear();try(var paths=Files.walk(tmp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
