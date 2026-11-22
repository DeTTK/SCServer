package szserver.client;
import java.io.*;import java.nio.file.*;import java.net.*;import java.lang.reflect.*;import java.util.*;import java.security.*;

public final class NativePlatformTests {
    static int checks;static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    static byte[] resource()throws IOException{try(var in=NativePlatformTests.class.getClassLoader().getResourceAsStream(NativePlatform.resource())){if(in==null)throw new IOException("required_native_missing:"+NativePlatform.resource());return in.readAllBytes();}}
    public static void main(String[] args)throws Exception{
        String os=System.getProperty("os.name"),arch=System.getProperty("os.arch");
        try{
            for(String host:List.of("Windows 11","Linux"))for(String cpu:List.of("amd64","x86_64")){
                System.setProperty("os.name",host);System.setProperty("os.arch",cpu);byte[] raw=resource();
                check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(NativePlatform.sha256()),"pinned native hash "+host+" "+cpu);
                check(host.equals("Linux")?raw[0]==127&&raw[1]=='E'&&raw[2]=='L'&&raw[3]=='F':raw[0]=='M'&&raw[1]=='Z',"native executable format");
            }
            System.setProperty("os.arch","aarch64");try{NativePlatform.resource();throw new AssertionError("unsupported CPU accepted");}catch(IllegalStateException expected){checks++;}
            System.setProperty("os.arch","amd64");System.setProperty("os.name","Mac OS X");try{NativePlatform.resource();throw new AssertionError("unsupported OS accepted");}catch(IllegalStateException expected){checks++;}
        }finally{System.setProperty("os.name",os);System.setProperty("os.arch",arch);}
        URL tests=NativePlatformTests.class.getProtectionDomain().getCodeSource().getLocation(),dependency=Class.forName("com.github.luben.zstd.Zstd",false,NativePlatformTests.class.getClassLoader()).getProtectionDomain().getCodeSource().getLocation();
        for(String owner:List.of("ClientSceneCodec","NativeActorMotion")){
            Class<?> type=Class.forName("szserver.client."+owner+"$Loader");Constructor<?> constructor=type.getDeclaredConstructor(URL[].class);constructor.setAccessible(true);
            try(var loader=(URLClassLoader)constructor.newInstance((Object)new URL[]{tests,dependency})){
                check((boolean)Class.forName("szserver.client.NativeLibraryProbe",true,loader).getMethod("roundTrip").invoke(null),"actual JNI load and compression: "+owner);
            }
            Path root=Files.createTempDirectory("jni-retry-");
            try(var loader=(URLClassLoader)constructor.newInstance((Object)new URL[]{root.toUri().toURL()})){
                Method find=type.getDeclaredMethod("findLibrary",String.class);find.setAccessible(true);
                Path file=root.resolve(NativePlatform.resource());Files.createDirectories(file.getParent());
                for(boolean corrupt:new boolean[]{false,true}){
                    if(corrupt)Files.write(file,new byte[]{1,2,3});
                    try{find.invoke(loader,"zstd-jni-1.5.0-5");throw new AssertionError("invalid JNI accepted");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"missing/corrupt JNI is rejected on each retry");}
                }
                Files.write(file,resource());Path extracted=Path.of((String)find.invoke(loader,"zstd-jni-1.5.0-5"));
                try{check(Arrays.equals(Files.readAllBytes(extracted),resource()),"failed extraction is not cached: "+owner);}finally{Files.delete(extracted);}
            }finally{try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        }
        System.out.println("PASS "+checks+" native platform, hashes, actual JNI roundtrip and extraction retry checks");
    }
}
