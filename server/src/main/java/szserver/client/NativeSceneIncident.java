package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Supplies only the real spider serialization settings, without starting the incident game mode. */
final class NativeSceneIncident {
    private static final String READER="szserver/bridge/SceneSpiderSettings",TYPE="szserver/bridge/SceneSpider";
    private final ClientSceneCodec.Loader loader;
    private final Class<?> type;
    private final String weapon;
    private boolean prepared;
    NativeSceneIncident(ClientSceneCodec.Loader loader,Path runtime,Path manifest)throws Exception{
        this.loader=loader;
        if(RuntimeFiles.size(manifest)>65536)throw new IOException("scene_incident_manifest_limit");
        JsonObject spec=JsonParser.parseString(RuntimeFiles.readString(manifest)).getAsJsonObject();
        if(spec.get("version").getAsInt()!=1||!spec.get("parserMethod").getAsString().equals("implements")||!spec.get("parserDescriptor").getAsString().equals("()L1198776129;")||spec.get("sliceStart").getAsInt()!=2581||spec.get("sliceEnd").getAsInt()!=3254)throw new IOException("scene_incident_manifest_version");
        Map<String,byte[]> classes=new HashMap<>();
        for(var entry:spec.getAsJsonObject("classesSha256").entrySet()){
            String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IOException("scene_incident_class_name");
            classes.put(name,pinned(runtime.resolve("classes/"+name+".class"),entry.getValue().getAsString(),4*1024*1024));
        }
        for(String name:List.of("348115930","913250395","1788962927","1183683657","1994198637","313873871","1745367406","211097523","1660905315","1205255287"))
            if(!classes.containsKey(name))throw new IOException("scene_incident_unpinned_class:"+name);
        JsonArray sources=spec.getAsJsonArray("sources");if(sources.size()!=2)throw new IOException("scene_incident_sources");
        byte[][] bytes=new byte[2][];for(int i=0;i<2;i++){JsonObject row=sources.get(i).getAsJsonObject();bytes[i]=pinned(RuntimeFiles.reference(manifest,row.get("path").getAsString()),row.get("sha256").getAsString(),4*1024*1024);}
        // This is the unmodified native settings-construction block. It has no mode/Region API calls.
        Class<?> reader=loader.define(READER.replace('/','.'),reader(classes.get("348115930")));
        Object parsed=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,new String(bytes[0],java.nio.charset.StandardCharsets.UTF_8));
        Object json=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
        Object settings=reader.getMethod("read",Object.class,Class.forName("com.google.gson.JsonObject",true,loader)).invoke(null,null,json);
        type=loader.define(TYPE.replace('/','.'),subclass());type.getField("settings").set(null,settings);
        JsonObject definitions=JsonParser.parseString(new String(bytes[1],java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject row=definitions.getAsJsonObject("20175");if(row==null||!row.get("unlocalized_name").getAsString().equals("wpn.pkp"))throw new IOException("scene_incident_weapon_source");
        weapon=row.toString();
    }
    Class<?> type(){return type;}
    void prepare()throws Exception{
        if(prepared)return;
        Class.forName("964863275",true,loader);Class.forName("1924679062",true,loader);
        Class<?> factory=Class.forName("1366800141",true,loader);Object[] registered=(Object[])factory.getField("import").get(null);
        if(registered[20175]==null){
            Object parsed=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,weapon);
            Object properties=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
            Class<?> descriptor=Class.forName("1422486348",true,loader);
            Object resource=descriptor.getConstructor(String.class,Class.forName("com.google.gson.JsonObject",true,loader),int.class,String.class,Integer.class,String.class).newInstance("wpn.pkp",properties,20175,null,null,null);
            Class.forName("1745367406",true,loader).getConstructor(descriptor).newInstance(resource);
        }else if(!registered[20175].getClass().getName().equals("1745367406"))throw new IOException("scene_incident_weapon_conflict");
        prepared=true;
    }
    private static byte[] pinned(Path path,String expected,int limit)throws Exception{
        if(RuntimeFiles.size(path)>limit)throw new IOException("scene_incident_source_limit");byte[] bytes=RuntimeFiles.read(path);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("scene_incident_release_changed:"+path.getFileName());return bytes;
    }
    private static void utf(DataOutputStream out,String s)throws IOException{out.writeByte(1);out.writeUTF(s);}
    private static void clazz(DataOutputStream out,int n)throws IOException{out.writeByte(7);out.writeShort(n);}
    private static void pair(DataOutputStream out,int tag,int a,int b)throws IOException{out.writeByte(tag);out.writeShort(a);out.writeShort(b);}
    private static void code(DataOutputStream out,int attribute,int stack,int locals,byte[] bytes)throws IOException{
        out.writeShort(1);out.writeShort(attribute);out.writeInt(12+bytes.length);out.writeShort(stack);out.writeShort(locals);out.writeInt(bytes.length);out.write(bytes);out.writeShort(0);out.writeShort(0);
    }
    private static void skipAttributes(ByteBuffer in){int n=in.getShort()&65535;for(int i=0;i<n;i++){in.getShort();int size=in.getInt();in.position(in.position()+size);}}
    /** Copy the release-pinned 2581..3253 settings block, retaining its own branches and real constructors. */
    private static byte[] reader(byte[] raw)throws IOException{
        ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt()!=0xcafebabe)throw new IOException("scene_incident_classfile");in.position(8);int count=in.getShort()&65535;Map<Integer,String> names=new HashMap<>();
        for(int i=1;i<count;i++){int tag=in.get()&255;switch(tag){case 1->{int size=in.getShort()&65535;byte[] b=new byte[size];in.get(b);names.put(i,new String(b,java.nio.charset.StandardCharsets.UTF_8));}case 3,4->in.position(in.position()+4);case 5,6->{in.position(in.position()+8);i++;}case 7,8->in.position(in.position()+2);case 9,10,11,12->in.position(in.position()+4);default->throw new IOException("scene_incident_pool:"+tag);}}
        int end=in.position();in.position(end+6);int interfaces=in.getShort()&65535;in.position(in.position()+interfaces*2);int fields=in.getShort()&65535;for(int i=0;i<fields;i++){in.position(in.position()+6);skipAttributes(in);}
        int methods=in.getShort()&65535;byte[] slice=null;int stack=0,locals=0;
        for(int i=0;i<methods;i++){in.getShort();String name=names.get(in.getShort()&65535),desc=names.get(in.getShort()&65535);int attrs=in.getShort()&65535;
            for(int a=0;a<attrs;a++){String attr=names.get(in.getShort()&65535);int size=in.getInt(),at=in.position();
                if(name.equals("implements")&&desc.equals("()L1198776129;")&&attr.equals("Code")){
                    stack=in.getShort()&65535;locals=in.getShort()&65535;int length=in.getInt();if(length<3254)throw new IOException("scene_incident_slice_bounds");byte[] nativeCode=new byte[length];in.get(nativeCode);
                    slice=Arrays.copyOfRange(nativeCode,2581,3254);
                }in.position(at+size);
            }
        }
        if(slice==null)throw new IOException("scene_incident_parser_missing");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(49);out.writeShort(count+7);out.write(raw,10,end-10);
        utf(out,READER);clazz(out,count);utf(out,"java/lang/Object");clazz(out,count+2);utf(out,"read");utf(out,"(Ljava/lang/Object;Lcom/google/gson/JsonObject;)L1183683657;");utf(out,"Code");
        out.writeShort(0x31);out.writeShort(count+1);out.writeShort(count+3);out.writeShort(0);out.writeShort(0);out.writeShort(1);
        out.writeShort(9);out.writeShort(count+4);out.writeShort(count+5);byte[] body=Arrays.copyOf(slice,slice.length+3);body[slice.length]=0x19;body[slice.length+1]=11;body[slice.length+2]=(byte)0xb0;
        code(out,count+6,stack,locals,body);out.writeShort(0);return bytes.toByteArray();
    }
    /** Inherit all stock behavior and codecs; override only the mode-dependent settings accessor. */
    private static byte[] subclass()throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(49);out.writeShort(17);
        utf(out,TYPE);clazz(out,1);utf(out,"913250395");clazz(out,3);utf(out,"<init>");utf(out,"(L1886271463;)V");pair(out,12,5,6);pair(out,10,4,7);utf(out,"Code");utf(out,"case");utf(out,"()L1183683657;");utf(out,"settings");utf(out,"L1183683657;");pair(out,12,12,13);pair(out,9,2,14);utf(out,"SourceFile");
        out.writeShort(0x31);out.writeShort(2);out.writeShort(4);out.writeShort(0);
        out.writeShort(1);out.writeShort(0x49);out.writeShort(12);out.writeShort(13);out.writeShort(0);
        out.writeShort(2);out.writeShort(1);out.writeShort(5);out.writeShort(6);code(out,9,2,2,new byte[]{0x2a,0x2b,(byte)0xb7,0,8,(byte)0xb1});
        out.writeShort(1);out.writeShort(10);out.writeShort(11);code(out,9,1,1,new byte[]{(byte)0xb2,0,15,(byte)0xb0});out.writeShort(0);return bytes.toByteArray();
    }
}
