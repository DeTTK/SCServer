package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pinned four-method clock boundary for stripped server-side native classes. Installed files stay read only. */
final class NativeSceneClock {
    private static final String BRIDGE="szserver/client/SceneCodecClock",CLOCK="szserver/bridge/SceneClock";
    private final Constructor<?> constructor;
    private final Field seconds;
    private final Method enter,exit;
    private final long started=System.nanoTime();
    NativeSceneClock(ClientSceneCodec.Loader loader,Path runtime,Path manifest)throws Exception{
        if(RuntimeFiles.size(manifest)>64*1024)throw new IOException("scene_clock_manifest_limit");
        JsonObject spec=JsonParser.parseString(RuntimeFiles.readString(manifest)).getAsJsonObject();if(spec.get("version").getAsInt()!=1)throw new IOException("scene_clock_manifest_version");
        Map<String,byte[]> classes=new HashMap<>();
        for(var entry:spec.getAsJsonObject("classesSha256").entrySet()){
            String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IOException("scene_clock_class_name");byte[] raw=RuntimeFiles.read(runtime.resolve("classes/"+name+".class"));
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(entry.getValue().getAsString()))throw new IOException("scene_clock_native_release_changed:"+name);classes.put(name,raw);
        }
        for(String required:List.of("805719214","1231017137","1553688601","1046176253","963945232"))if(!classes.containsKey(required))throw new IOException("scene_clock_unpinned_class:"+required);
        try(InputStream input=SceneCodecClock.class.getResourceAsStream("SceneCodecClock.class")){if(input==null)throw new IOException("scene_clock_bridge_missing");loader.define(BRIDGE.replace('/','.'),input.readAllBytes());}
        loader.define("805719214",patch(classes.get("805719214"),List.of(new Replacement("if","()L1231017137;","clock","()Ljava/lang/Object;",0,1,0,true))));
        loader.define("1553688601",patch(classes.get("1553688601"),List.of(new Replacement("byte","()J","millis","()J",0,2,0,false))));
        loader.define("1046176253",patch(classes.get("1046176253"),List.of(new Replacement("while","(J)D","decode","(J)D",0x1e,2,2,false),new Replacement("long","(D)J","encode","(D)J",0x26,2,2,false))));
        Class<?> clock=loader.define(CLOCK.replace('/','.'),subclass());constructor=clock.getConstructor();seconds=clock.getField("char");Class<?> bridge=Class.forName(BRIDGE.replace('/','.'),true,loader);enter=bridge.getMethod("enter",Object.class,long.class,double.class);exit=bridge.getMethod("exit");
    }
    void enter()throws Exception{enter(System.currentTimeMillis(),(System.nanoTime()-started)/1_000_000/1000d);}
    void enter(long millis,double elapsed)throws Exception{Object clock=constructor.newInstance();seconds.setDouble(clock,elapsed);enter.invoke(null,clock,millis,elapsed);}
    void exit()throws Exception{exit.invoke(null);}
    private record Replacement(String name,String descriptor,String target,String targetDescriptor,int load,int stack,int locals,boolean cast){}
    private record Patch(int start,int end,byte[] bytes){}
    /** Preserve every native member/attribute except the selected Code attributes; append isolated bridge references. */
    private static byte[] patch(byte[] raw,List<Replacement> replacements)throws IOException{
        ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt()!=0xcafebabe)throw new IOException("scene_clock_classfile");in.position(8);int count=in.getShort()&65535;Map<Integer,String> utf=new HashMap<>();
        for(int i=1;i<count;i++){int tag=in.get()&255;switch(tag){case 1->{int size=in.getShort()&65535;byte[] text=new byte[size];in.get(text);utf.put(i,new String(text,java.nio.charset.StandardCharsets.UTF_8));}case 3,4->in.position(in.position()+4);case 5,6->{in.position(in.position()+8);i++;}case 7,8,16,19,20->in.position(in.position()+2);case 9,10,11,12,17,18->in.position(in.position()+4);case 15->in.position(in.position()+3);default->throw new IOException("scene_clock_constant_pool:"+tag);}}
        int poolEnd=in.position();ByteArrayOutputStream added=new ByteArrayOutputStream();DataOutputStream constants=new DataOutputStream(added);List<byte[]> codes=new ArrayList<>();int next=count;
        for(var r:replacements){
            int bridgeName=next++,bridgeClass=next++,target=next++,signature=next++,pair=next++,method=next++;
            utf(constants,BRIDGE);clazz(constants,bridgeName);utf(constants,r.target());utf(constants,r.targetDescriptor());pair(constants,12,target,signature);pair(constants,10,bridgeClass,pair);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream code=new DataOutputStream(bytes);if(r.load()!=0)code.writeByte(r.load());code.writeByte(0xb8);code.writeShort(method);
            if(r.cast()){int typeName=next++,type=next++;utf(constants,"1231017137");clazz(constants,typeName);code.writeByte(0xc0);code.writeShort(type);}
            code.writeByte(r.descriptor().endsWith("J")?0xad:r.descriptor().endsWith("D")?0xaf:0xb0);codes.add(bytes.toByteArray());
        }
        in.position(poolEnd+6);int interfaces=in.getShort()&65535;in.position(in.position()+interfaces*2);int fields=in.getShort()&65535;for(int f=0;f<fields;f++){in.position(in.position()+6);skipAttributes(in);}
        int methods=in.getShort()&65535;List<Patch> patches=new ArrayList<>();Set<Integer> found=new HashSet<>();
        for(int m=0;m<methods;m++){int flags=in.getShort()&65535;String name=utf.get(in.getShort()&65535),desc=utf.get(in.getShort()&65535);int attrs=in.getShort()&65535;
            for(int a=0;a<attrs;a++){int at=in.position(),attribute=in.getShort()&65535,size=in.getInt();for(int r=0;r<replacements.size();r++){var replacement=replacements.get(r);if(name.equals(replacement.name())&&desc.equals(replacement.descriptor())&&"Code".equals(utf.get(attribute))){if((flags&8)==0||!found.add(r))throw new IOException("scene_clock_duplicate_boundary");byte[] code=codes.get(r);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeShort(attribute);out.writeInt(12+code.length);out.writeShort(replacement.stack());out.writeShort(replacement.locals());out.writeInt(code.length);out.write(code);out.writeShort(0);out.writeShort(0);patches.add(new Patch(at,at+6+size,bytes.toByteArray()));}}in.position(at+6+size);}
        }
        if(found.size()!=replacements.size())throw new IOException("scene_clock_missing_boundary");ByteArrayOutputStream result=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(result);out.write(raw,0,8);out.writeShort(next);out.write(raw,10,poolEnd-10);out.write(added.toByteArray());int cursor=poolEnd;patches.sort(Comparator.comparingInt(Patch::start));for(var p:patches){out.write(raw,cursor,p.start()-cursor);out.write(p.bytes());cursor=p.end();}out.write(raw,cursor,raw.length-cursor);return result.toByteArray();
    }
    private static void skipAttributes(ByteBuffer in){int count=in.getShort()&65535;for(int i=0;i<count;i++){in.getShort();int size=in.getInt();in.position(in.position()+size);}}
    private static void utf(DataOutputStream out,String s)throws IOException{out.writeByte(1);out.writeUTF(s);}
    private static void clazz(DataOutputStream out,int n)throws IOException{out.writeByte(7);out.writeShort(n);}
    private static void pair(DataOutputStream out,int tag,int a,int b)throws IOException{out.writeByte(tag);out.writeShort(a);out.writeShort(b);}
    private static byte[] subclass()throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(52);out.writeShort(10);utf(out,CLOCK);clazz(out,1);utf(out,"1231017137");clazz(out,3);utf(out,"<init>");utf(out,"()V");pair(out,12,5,6);pair(out,10,4,7);utf(out,"Code");out.writeShort(0x21);out.writeShort(2);out.writeShort(4);out.writeShort(0);out.writeShort(0);out.writeShort(1);out.writeShort(1);out.writeShort(5);out.writeShort(6);out.writeShort(1);out.writeShort(9);out.writeInt(17);out.writeShort(1);out.writeShort(1);out.writeInt(5);out.write(new byte[]{0x2a,(byte)0xb7,0,8,(byte)0xb1});out.writeShort(0);out.writeShort(0);out.writeShort(0);return bytes.toByteArray();
    }
}
