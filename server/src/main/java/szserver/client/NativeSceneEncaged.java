package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real crate serialization context, isolated from the full Encaged mode and its external services. */
final class NativeSceneEncaged {
    private static final String BRIDGE="szserver/bridge/SceneEncagedSettings";
    private final ClientSceneCodec.Loader loader;
    private final Map<?,?> settings;
    private final JsonObject loot;
    private final Map<String,Integer> names;
    private boolean prepared;
    NativeSceneEncaged(ClientSceneCodec.Loader loader,Path runtime,Path manifest)throws Exception{
        this.loader=loader;
        if(RuntimeFiles.size(manifest)>65536)throw new IOException("scene_encaged_manifest_limit");
        JsonObject spec=JsonParser.parseString(RuntimeFiles.readString(manifest)).getAsJsonObject();
        if(spec.get("version").getAsInt()!=1||!spec.get("parserMethod").getAsString().equals("public")||!spec.get("parserDescriptor").getAsString().equals("(Ljava/lang/String;)L94498622;")||spec.get("sliceStart").getAsInt()!=2854||spec.get("sliceEnd").getAsInt()!=3259)throw new IOException("scene_encaged_manifest_version");
        Map<String,byte[]> classes=new HashMap<>();
        for(var entry:spec.getAsJsonObject("classesSha256").entrySet()){
            String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IOException("scene_encaged_class_name");
            classes.put(name,pinned(runtime.resolve("classes/"+name+".class"),entry.getValue().getAsString(),4*1024*1024));
        }
        for(String name:List.of("1634824458","651500106","317956473","496016126","1865184146","1036856485","10442891","1189340399","489960719","1997612548","1518398188","1460119004","887772526","313873871"))
            if(!classes.containsKey(name))throw new IOException("scene_encaged_unpinned_class:"+name);
        JsonArray sources=spec.getAsJsonArray("sources");if(sources.size()!=2)throw new IOException("scene_encaged_sources");
        byte[][] bytes=new byte[2][];for(int i=0;i<2;i++){var row=sources.get(i).getAsJsonObject();bytes[i]=pinned(RuntimeFiles.reference(manifest,row.get("path").getAsString()),row.get("sha256").getAsString(),4*1024*1024);}
        Class<?> bridge=loader.define(BRIDGE.replace('/','.'),reader(classes.get("1634824458")));
        Object parsed=Class.forName("com.google.gson.JsonParser",true,loader).getMethod("parseString",String.class).invoke(null,new String(bytes[0],java.nio.charset.StandardCharsets.UTF_8));
        Object json=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
        settings=(Map<?,?>)bridge.getMethod("read",Class.forName("com.google.gson.JsonObject",true,loader)).invoke(null,json);
        if(settings.size()!=3)throw new IOException("scene_encaged_crate_types");
        Map<Object,Map<Object,Object>> immutable=new LinkedHashMap<>();
        for(var entry:settings.entrySet()){
            if(!(entry.getValue() instanceof Map<?,?> roles)||roles.size()!=4)throw new IOException("scene_encaged_roles");
            Map<Object,Object> copy=new LinkedHashMap<>();for(var role:roles.entrySet()){if(!(role.getValue() instanceof String name)||name.isBlank())throw new IOException("scene_encaged_preset_name");copy.put(role.getKey(),name);}immutable.put(entry.getKey(),Collections.unmodifiableMap(copy));
        }
        bridge.getField("settings").set(null,Collections.unmodifiableMap(immutable));
        // Only the two mode-map lookups change. Native constructor, NBT, attributes and behavior stay intact.
        loader.define("651500106",patch(classes.get("651500106")));
        loot=JsonParser.parseString(new String(bytes[1],java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String,Integer> checked=new TreeMap<>();JsonObject declared=spec.getAsJsonObject("names");if(declared.size()>128)throw new IOException("scene_encaged_names_limit");
        for(var entry:declared.entrySet()){int id=entry.getValue().getAsInt();if(id<1||id>65535||entry.getKey().isBlank()||entry.getKey().length()>256)throw new IOException("scene_encaged_item_name");checked.put(entry.getKey(),id);}names=Map.copyOf(checked);
    }
    /** Selected real native loot presets, parsed once on the constructor worker, with exact reviewed IDs. */
    void prepare()throws Exception{
        if(prepared)return;
        Set<String> required=new TreeSet<>();for(Object roles:settings.values())for(Object value:((Map<?,?>)roles).values())required.add((String)value);
        JsonObject selected=new JsonObject();for(String name:required){var row=loot.get(name);if(row==null||!row.isJsonArray())throw new IOException("scene_encaged_missing_preset:"+name);selected.add(name,row.deepCopy());}
        normalize(selected,0);
        Object gson=Class.forName("887772526",true,loader).getField("double").get(null),token=Class.forName("10442891",true,loader).getConstructor().newInstance();
        Object type=token.getClass().getMethod("getType").invoke(token);
        Map<?,?> presets=(Map<?,?>)gson.getClass().getMethod("fromJson",String.class,Type.class).invoke(gson,selected.toString(),type);
        Class<?> presetType=Class.forName("1460119004",true,loader);if(presets.size()!=required.size())throw new IOException("scene_encaged_preset_count");for(String name:required)if(!presetType.isInstance(presets.get(name)))throw new IOException("scene_encaged_preset_type:"+name);
        Class<?> nativeLoot=Class.forName("1036856485",true,loader);Field field=nativeLoot.getField("boolean");if(field.get(null)!=null)throw new IOException("scene_encaged_loot_conflict");
        field.set(null,Collections.unmodifiableMap(new LinkedHashMap<>(presets)));prepared=true;
    }
    private void normalize(JsonElement value,int depth)throws IOException{
        if(depth>64)throw new IOException("scene_encaged_loot_depth");
        if(value.isJsonArray()){for(var row:value.getAsJsonArray())normalize(row,depth+1);return;}if(!value.isJsonObject())return;
        JsonObject object=value.getAsJsonObject();if(object.has("stack")){
            JsonObject stack=object.getAsJsonObject("stack");var id=stack.get("id");if(id!=null&&id.isJsonPrimitive()&&id.getAsJsonPrimitive().isString()){
                String name=id.getAsString();Integer mapped=names.get(name);if(mapped==null)throw new IOException("scene_encaged_unknown_item:"+name);stack.addProperty("id",mapped);
            }
        }
        for(var entry:object.entrySet())normalize(entry.getValue(),depth+1);
    }
    private static byte[] pinned(Path path,String expected,int limit)throws Exception{
        if(RuntimeFiles.size(path)>limit)throw new IOException("scene_encaged_source_limit");byte[] bytes=RuntimeFiles.read(path);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("scene_encaged_release_changed:"+path.getFileName());return bytes;
    }
    private record MethodCode(String name,String descriptor,int start,int length,int stack,int locals){}
    private record ClassFile(int count,int poolEnd,List<MethodCode> methods){}
    private static void skipAttributes(ByteBuffer in){int count=in.getShort()&65535;for(int i=0;i<count;i++){in.getShort();int size=in.getInt();in.position(in.position()+size);}}
    private static ClassFile parse(byte[] raw)throws IOException{
        ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt()!=0xcafebabe)throw new IOException("scene_encaged_classfile");in.position(8);int count=in.getShort()&65535;Map<Integer,String> names=new HashMap<>();
        for(int i=1;i<count;i++){int tag=in.get()&255;switch(tag){case 1->{int size=in.getShort()&65535;byte[] bytes=new byte[size];in.get(bytes);names.put(i,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}case 3,4->in.position(in.position()+4);case 5,6->{in.position(in.position()+8);i++;}case 7,8->in.position(in.position()+2);case 9,10,11,12->in.position(in.position()+4);default->throw new IOException("scene_encaged_pool:"+tag);}}
        int end=in.position();in.position(end+6);int interfaces=in.getShort()&65535;in.position(in.position()+interfaces*2);int fields=in.getShort()&65535;for(int i=0;i<fields;i++){in.position(in.position()+6);skipAttributes(in);}
        List<MethodCode> methods=new ArrayList<>();int countMethods=in.getShort()&65535;
        for(int i=0;i<countMethods;i++){in.getShort();String name=names.get(in.getShort()&65535),desc=names.get(in.getShort()&65535);int attrs=in.getShort()&65535;
            for(int a=0;a<attrs;a++){String attr=names.get(in.getShort()&65535);int size=in.getInt(),at=in.position();if(attr.equals("Code")){int stack=in.getShort()&65535,locals=in.getShort()&65535,length=in.getInt();methods.add(new MethodCode(name,desc,in.position(),length,stack,locals));}in.position(at+size);}
        }return new ClassFile(count,end,List.copyOf(methods));
    }
    private static void utf(DataOutputStream out,String s)throws IOException{out.writeByte(1);out.writeUTF(s);}
    private static void clazz(DataOutputStream out,int n)throws IOException{out.writeByte(7);out.writeShort(n);}
    private static void pair(DataOutputStream out,int tag,int a,int b)throws IOException{out.writeByte(tag);out.writeShort(a);out.writeShort(b);}
    private static void code(DataOutputStream out,int name,int descriptor,int attribute,int stack,int locals,byte[] bytes)throws IOException{
        out.writeShort(9);out.writeShort(name);out.writeShort(descriptor);out.writeShort(1);out.writeShort(attribute);out.writeInt(12+bytes.length);out.writeShort(stack);out.writeShort(locals);out.writeInt(bytes.length);out.write(bytes);out.writeShort(0);out.writeShort(0);
    }
    /** Original native crate-map parser only, including native enums and all branches. */
    private static byte[] reader(byte[] raw)throws IOException{
        ClassFile parsed=parse(raw);MethodCode method=parsed.methods().stream().filter(m->m.name().equals("public")&&m.descriptor().equals("(Ljava/lang/String;)L94498622;")).findFirst().orElseThrow(()->new IOException("scene_encaged_parser_missing"));
        if(method.length()<3259)throw new IOException("scene_encaged_slice_bounds");byte[] slice=Arrays.copyOfRange(raw,method.start()+2854,method.start()+3259);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);int n=parsed.count();
        out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(49);out.writeShort(n+13);out.write(raw,10,parsed.poolEnd()-10);
        utf(out,BRIDGE);clazz(out,n);utf(out,"java/lang/Object");clazz(out,n+2);utf(out,"read");utf(out,"(Lcom/google/gson/JsonObject;)Ljava/util/Map;");utf(out,"Code");utf(out,"settings");utf(out,"Ljava/util/Map;");pair(out,12,n+7,n+8);pair(out,9,n+1,n+9);utf(out,"get");utf(out,"()Ljava/util/Map;");
        out.writeShort(0x31);out.writeShort(n+1);out.writeShort(n+3);out.writeShort(0);out.writeShort(1);out.writeShort(0x49);out.writeShort(n+7);out.writeShort(n+8);out.writeShort(0);out.writeShort(2);
        byte[] body=new byte[slice.length+6];body[0]=0x2a;body[1]=0x3a;body[2]=6;System.arraycopy(slice,0,body,3,slice.length);body[body.length-3]=0x19;body[body.length-2]=26;body[body.length-1]=(byte)0xb0;
        code(out,n+4,n+5,n+6,method.stack(),method.locals(),body);code(out,n+11,n+12,n+6,1,0,new byte[]{(byte)0xb2,(byte)((n+10)>>8),(byte)(n+10),(byte)0xb0});out.writeShort(0);return bytes.toByteArray();
    }
    /** Preserve native offsets, stack maps and every member; replace exactly two six-byte mode-map lookups. */
    private static byte[] patch(byte[] raw)throws IOException{
        ClassFile parsed=parse(raw);int n=parsed.count();byte[] changed=raw.clone();int found=0;
        for(var method:parsed.methods()){
            int offset=method.name().equals("implements")&&method.descriptor().equals("(L317956473;L496016126;)V")?7:method.name().equals("<init>")&&method.descriptor().equals("(L1886271463;)V")?206:-1;
            if(offset<0)continue;int at=method.start()+offset;
            if(method.length()<offset+6||!Arrays.equals(Arrays.copyOfRange(raw,at,at+6),new byte[]{(byte)0xb8,0,(byte)0xa0,(byte)0xb6,0,(byte)0xa4}))throw new IOException("scene_encaged_boundary_changed");
            changed[at]=(byte)0xb8;changed[at+1]=(byte)((n+5)>>8);changed[at+2]=(byte)(n+5);Arrays.fill(changed,at+3,at+6,(byte)0);found++;
        }
        if(found!=2)throw new IOException("scene_encaged_boundary_count");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write(raw,0,8);out.writeShort(n+6);out.write(raw,10,parsed.poolEnd()-10);
        utf(out,BRIDGE);clazz(out,n);utf(out,"get");utf(out,"()Ljava/util/Map;");pair(out,12,n+2,n+3);pair(out,10,n+1,n+4);out.write(changed,parsed.poolEnd(),changed.length-parsed.poolEnd());return bytes.toByteArray();
    }
}
