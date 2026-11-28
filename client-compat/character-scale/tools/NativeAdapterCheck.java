package playerscale;

import java.nio.file.*;
import java.net.*;
import java.util.*;

/** Verifies actual canonical adapters and links transformed classes without starting the game. */
public final class NativeAdapterCheck {
    public static void main(String[] args)throws Exception {
        Path runtime=Path.of(args[0]),jar=Path.of(args[1]);List<URL> urls=new ArrayList<>();urls.add(jar.toUri().toURL());
        for(String dir:List.of("classes","bootstrap","libs","imgui-compat"))urls.add(runtime.resolve(dir).toUri().toURL());
        try(var files=Files.list(runtime.resolve("dependencies"))){for(Path p:files.filter(p->p.toString().endsWith(".jar")).toList())urls.add(p.toUri().toURL());}
        Map<String,byte[]> patched=new HashMap<>();var hashes=new Properties();
        try(var input=ScaleAgent.class.getResourceAsStream("/playerscale-native.properties")){hashes.load(input);}
        for(String name:hashes.stringPropertyNames())patched.put(name,ScaleAgent.patch(name,Files.readAllBytes(runtime.resolve("classes/"+name+".class"))));
        // Our own loader's stable frame callback runs even when the external mods directory is empty.
        patched.put("szmods.runtime.ModLoader",ScaleAgent.patch("szmods/runtime/ModLoader",Files.readAllBytes(runtime.resolve("bootstrap/szmods/runtime/ModLoader.class"))));
        try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader()){
            protected Class<?> findClass(String name)throws ClassNotFoundException {byte[] raw=patched.get(name);return raw==null?super.findClass(name):defineClass(name,raw,0,raw.length);}
        }){
            for(String name:patched.keySet()){Class<?> type=Class.forName(name,false,loader);type.getDeclaredMethods();System.out.println("Native adapter linked: "+name);}
            Class<?> posture=Class.forName("430277821",true,loader),state=Class.forName("1943200621",false,loader);
            for(Object pose:posture.getEnumConstants()){
                Object height=state.getMethod("const",posture).invoke(null,pose),eye=state.getMethod("do",posture).invoke(null,pose);
                int ordinal=((Enum<?>)pose).ordinal();if(((Number)height).floatValue()!=NativeDimensions.height(ordinal)||((Number)eye).floatValue()!=NativeDimensions.eye(ordinal))throw new AssertionError("native dimension profile changed");
                System.out.println("Native dimensions: "+((Enum<?>)pose).ordinal()+" "+pose+" height="+height+" eye="+eye);
            }
            Class<?> input=Class.forName("playerscale.bridge.ScaleInput",true,loader);Object request=input.getConstructor(byte[].class).newInstance((Object)ScaleProtocol.hello());
            var bytes=new java.io.ByteArrayOutputStream();input.getMethod("switch",java.io.DataOutput.class).invoke(request,new java.io.DataOutputStream(bytes));
            if(!Arrays.equals(bytes.toByteArray(),ScaleProtocol.hello()))throw new AssertionError("native input bridge");
            Class<?> snapshot=Class.forName("playerscale.bridge.ScaleSnapshot",true,loader);Object reply=snapshot.getConstructor().newInstance();byte[] payload=new ScaleProtocol.Snapshot(1,0,5,new ScaleValue(.5f)).encode();
            snapshot.getMethod("extends",java.io.DataInput.class).invoke(reply,new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload)));
            if(!Arrays.equals((byte[])snapshot.getField("payload").get(reply),payload))throw new AssertionError("native snapshot bridge");
        }
        System.out.println("Native adapters: "+hashes.size()+" game classes, own frame callback and 2 dynamic bridges PASS; no gameplay or rendering executed");
    }
}
