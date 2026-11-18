package szserver.client;

import java.io.*;
import java.util.*;

/** Tiny JVM bridges: supply the world data to the untouched native movement solver. */
final class NativeActorBridge {
    /** Host lifecycle hooks for the missing SERVER component; native idle state and constructor are inherited. */
    static byte[] creatureLogic()throws IOException {
        var b=new NativeActorBridge("szserver/bridge/CreatureServerLogic","1070955899");
        int init=b.ref(10,"1070955899","<init>","(L1422788721;)V");
        b.method("<init>","(L1422788721;)V",2,2,42,43,183,init>>8,init,177);
        int tick=b.field("hostTick","Ljava/lang/Runnable;"),dispose=b.field("hostDispose","Ljava/lang/Runnable;"),run=b.ref(11,"java/lang/Runnable","run","()V");
        b.method("enum","()V",1,1,42,180,tick>>8,tick,185,run>>8,run,1,0,177);
        b.method("extends","()V",1,1,42,180,dispose>>8,dispose,185,run>>8,run,1,0,177);
        return b.finish();
    }
    /** Read-only feet point and admission flags. No controller, movement tick or velocity exists. */
    static byte[] mineContact()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ActorMineContact","351062380");
        int point=b.field("point","L153304200;"),alive=b.field("alive","Z"),invulnerable=b.field("invulnerable","Z");
        b.method("if","()L153304200;",1,1,42,180,point>>8,point,176);
        b.method("super","()Z",1,1,42,180,alive>>8,alive,172);
        b.method("new","()Z",1,1,42,180,invulnerable>>8,invulnerable,172);return b.finish();
    }
    /** Only the global-to-host seed translation changes; admission and contact remain inherited. */
    static byte[] mineBlock()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ActorMineBlock","806673724");
        int offsets=b.field("offsets","Ljava/util/function/Function;"),apply=b.ref(11,"java/util/function/Function","apply","(Ljava/lang/Object;)Ljava/lang/Object;"),floats=b.clazz("[F");
        int constructor=b.ref(10,"806673724","<init>","(ILjava/lang/String;I)V");
        b.method("<init>","(ILjava/lang/String;I)V",4,4,42,27,44,29,183,constructor>>8,constructor,177);
        int bounds=b.ref(10,"806673724","import","(IIIFF)L242639986;");
        b.method("import","(IIIFF)L242639986;",7,7,
            42,180,offsets>>8,offsets,6,188,10,89,3,27,79,89,4,28,79,89,5,29,79,
            185,apply>>8,apply,2,0,192,floats>>8,floats,58,6,
            42,27,28,29,25,6,3,48,25,6,4,48,183,bounds>>8,bounds,176);
        return b.finish();
    }
    private record Entry(byte[] bytes){}
    private record Member(int access,int name,int descriptor,byte[] code,int stack,int locals){}
    private final List<Entry> pool=new ArrayList<>();private final List<Member> fields=new ArrayList<>(),methods=new ArrayList<>();private final String name;private final int self,parent,code;
    private NativeActorBridge(String name,String parent)throws IOException{this.name=name;self=clazz(name);this.parent=clazz(parent);code=utf("Code");}
    private int add(int tag,Object... values)throws IOException{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(tag);for(Object v:values)if(v instanceof String s)out.writeUTF(s);else out.writeShort((Integer)v);pool.add(new Entry(bytes.toByteArray()));return pool.size();}
    private int utf(String s)throws IOException{return add(1,s);}private int clazz(String s)throws IOException{return add(7,utf(s));}
    private int ref(int tag,String owner,String name,String desc)throws IOException{return add(tag,clazz(owner),add(12,utf(name),utf(desc)));}
    private int field(String name,String desc)throws IOException{fields.add(new Member(1,utf(name),utf(desc),null,0,0));return ref(9,this.name,name,desc);}
    private void method(String name,String desc,int stack,int locals,int... opcodes)throws IOException{byte[] body=new byte[opcodes.length];for(int n=0;n<body.length;n++)body[n]=(byte)opcodes[n];methods.add(new Member(1,utf(name),utf(desc),body,stack,locals));}
    private byte[] finish()throws IOException{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(49);out.writeShort(pool.size()+1);for(var e:pool)out.write(e.bytes());out.writeShort(0x21);out.writeShort(self);out.writeShort(parent);out.writeShort(0);out.writeShort(fields.size());for(var f:fields){out.writeShort(f.access());out.writeShort(f.name());out.writeShort(f.descriptor());out.writeShort(0);}out.writeShort(methods.size());for(var m:methods){out.writeShort(m.access());out.writeShort(m.name());out.writeShort(m.descriptor());out.writeShort(1);out.writeShort(code);out.writeInt(12+m.code().length);out.writeShort(m.stack());out.writeShort(m.locals());out.writeInt(m.code().length);out.write(m.code());out.writeShort(0);out.writeShort(0);}out.writeShort(0);return bytes.toByteArray();}
    static byte[] world()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ActorWorld","1500514786");int forest=b.field("forest","L493563411;"),voxels=b.field("voxels","Ljava/util/function/BiConsumer;"),accept=b.ref(11,"java/util/function/BiConsumer","accept","(Ljava/lang/Object;Ljava/lang/Object;)V");
        b.method("switch","()Z",1,1,3,172);b.method("do","()L493563411;",1,1,42,180,forest>>8,forest,176);b.method("package","()L67634208;",1,1,1,176);
        b.method("const","(L1217489680;L242639986;Ljava/util/List;)V",3,4,42,180,voxels>>8,voxels,44,45,185,accept>>8,accept,3,0,177);
        int bodies=b.field("bodies","Ljava/util/function/Function;"),bodyApply=b.ref(11,"java/util/function/Function","apply","(Ljava/lang/Object;)Ljava/lang/Object;"),bodyList=b.clazz("java/util/List");
        // Inherit the original package(): it asks each living entity for its nullable collider.
        b.method("super","(L1217489680;L242639986;)Ljava/util/List;",2,3,42,180,bodies>>8,bodies,44,185,bodyApply>>8,bodyApply,2,0,192,bodyList>>8,bodyList,176);
        int query=b.field("query","Ljava/util/function/Function;"),apply=b.ref(11,"java/util/function/Function","apply","(Ljava/lang/Object;)Ljava/lang/Object;"),list=b.clazz("java/util/List");
        b.method("strictfp","(L1217489680;L242639986;)Ljava/util/List;",2,3,42,180,query>>8,query,44,185,apply>>8,apply,2,0,192,list>>8,list,176);return b.finish();
    }
    /** Resident chunk access for the complete dispatcher. Block collision collection stays native. */
    static byte[] dispatcherWorld()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/DispatcherWorld","1500514786");
        int random=b.field("contactRandom","Ljava/util/Random;");b.method("extends","()Ljava/util/Random;",1,1,42,180,random>>8,random,176);
        int forest=b.field("forest","L493563411;");b.method("do","()L493563411;",1,1,42,180,forest>>8,forest,176);
        b.method("switch","()Z",1,1,3,172);b.method("package","()L67634208;",1,1,1,176);
        b.grid("strictfp","(III)I","ids",true);b.grid("interface","(III)I","metas",true);b.grid("abstract","(III)L1123841866;","tiles",false);
        b.pair("instanceof","(II)Z","resident",true);b.pair("else","(II)L812508934;","chunks",false);
        int suspended=b.field("suspended","Ljava/util/function/LongPredicate;"),test=b.ref(11,"java/util/function/LongPredicate","test","(J)Z");b.method("new","(J)Z",3,3,42,180,suspended>>8,suspended,31,185,test>>8,test,3,0,172);
        int bodies=b.field("bodies","Ljava/util/function/Function;"),apply=b.ref(11,"java/util/function/Function","apply","(Ljava/lang/Object;)Ljava/lang/Object;"),list=b.clazz("java/util/List");
        b.method("super","(L1217489680;L242639986;)Ljava/util/List;",2,3,42,180,bodies>>8,bodies,44,185,apply>>8,apply,2,0,192,list>>8,list,176);
        int query=b.field("query","Ljava/util/function/BiFunction;"),both=b.ref(11,"java/util/function/BiFunction","apply","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        b.method("strictfp","(L1217489680;L242639986;)Ljava/util/List;",3,3,42,180,query>>8,query,43,44,185,both>>8,both,3,0,192,list>>8,list,176);
        int faces=b.field("faces","Ljava/util/function/ToIntFunction;"),face=b.ref(11,"java/util/function/ToIntFunction","applyAsInt","(Ljava/lang/Object;)I"),ordinal=b.ref(10,"434349477","ordinal","()I");
        b.method("long","(IIIL434349477;Z)Z",4,7,8,188,10,58,6,25,6,3,27,79,25,6,4,28,79,25,6,5,29,79,25,6,6,25,4,182,ordinal>>8,ordinal,79,25,6,7,21,5,79,42,180,faces>>8,faces,25,6,185,face>>8,face,2,0,172);
        return b.finish();
    }
    static byte[] dispatcherChunk()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/DispatcherChunk","812508934");b.grid("default","(III)I","ids",true);b.grid("float","(III)I","metas",true);return b.finish();
    }
    /** A stable realm context. Its callbacks point at published resident game chunks. */
    static byte[] residentWorld()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ResidentWorld","1500514786");
        b.grid("strictfp","(III)I","ids",true);b.grid("interface","(III)I","metas",true);b.grid("abstract","(III)L1123841866;","tiles",false);
        b.pair("instanceof","(II)Z","resident",true);b.pair("else","(II)L812508934;","chunks",false);
        b.method("switch","()Z",1,1,3,172);b.method("package","()L67634208;",1,1,1,176);
        return b.finish();
    }
    static byte[] dispatcherTree()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/DispatcherTree","1581944406");int query=b.field("query","Ljava/util/function/BiConsumer;"),accept=b.ref(11,"java/util/function/BiConsumer","accept","(Ljava/lang/Object;Ljava/lang/Object;)V");
        var code=new ArrayList<Integer>(List.of(16,6,188,7,58,14));
        for(int n=0;n<6;n++)code.addAll(List.of(25,14,n+3,24,2+n*2,82));
        code.addAll(List.of(42,180,query>>8,query,25,14,43,185,accept>>8,accept,3,0,177));b.method("try","(Ljava/util/Collection;DDDDDD)V",4,15,code.stream().mapToInt(Integer::intValue).toArray());
        b.method("catch","(Ljava/util/Collection;L242639986;)V",3,3,42,180,query>>8,query,44,43,185,accept>>8,accept,3,0,177);return b.finish();
    }
    private void pair(String name,String descriptor,String fieldName,boolean integer)throws IOException{
        String function=integer?"java/util/function/ToIntFunction":"java/util/function/Function";int callback=field(fieldName,"L"+function+";"),apply=ref(11,function,integer?"applyAsInt":"apply",integer?"(Ljava/lang/Object;)I":"(Ljava/lang/Object;)Ljava/lang/Object;");
        int[] prefix={5,188,10,78,45,3,27,79,45,4,28,79,42,180,callback>>8,callback,45,185,apply>>8,apply,2,0};
        int[] body=Arrays.copyOf(prefix,prefix.length+(integer?1:4));if(integer)body[prefix.length]=172;else{int type=clazz("812508934");body[prefix.length]=192;body[prefix.length+1]=type>>8;body[prefix.length+2]=type;body[prefix.length+3]=176;}method(name,descriptor,4,4,body);
    }
    static byte[] dynamicBody(boolean creature)throws IOException{
        var b=new NativeActorBridge(creature?"szserver/bridge/ActorCreatureBody":"szserver/bridge/ActorHumanoidBody",creature?"1422788721":"820497227");
        int bounds=b.field("body","L242639986;");b.method("static","()L242639986;",1,1,42,180,bounds>>8,bounds,176);
        if(creature){int inactive=b.field("inactive","Z"),suppressed=b.field("suppressed","Z");b.method("j","()Z",1,1,42,180,inactive>>8,inactive,172);b.method("h","()Z",1,1,42,180,suppressed>>8,suppressed,172);}
        return b.finish();
    }
    static byte[] forest()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorForest","493563411");int tree=b.field("tree","L1581944406;");b.method("synchronized","()L1581944406;",1,1,42,180,tree>>8,tree,176);b.method("long","(L1217489680;L242639986;Ljava/util/List;)V",0,4,177);return b.finish();}
    static byte[] tileWorld()throws IOException{var b=new NativeActorBridge("szserver/bridge/TileWorld","1500514786");int tile=b.field("tile","L1123841866;"),id=b.field("blockId","I"),metadata=b.field("metadata","I");b.method("abstract","(III)L1123841866;",1,4,42,180,tile>>8,tile,176);b.method("strictfp","(III)I",1,4,42,180,id>>8,id,172);b.method("interface","(III)I",1,4,42,180,metadata>>8,metadata,172);b.method("switch","()Z",1,1,3,172);b.method("package","()L67634208;",1,1,1,176);return b.finish();}
    /** Probe-only neighbor lookup. Coordinates are fresh primitive arrays, not fake shared tiles. */
    static byte[] neighborWorld()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/NeighborWorld","1500514786");b.field("tile","L1123841866;");b.field("blockId","I");b.field("metadata","I");
        b.grid("abstract","(III)L1123841866;","tiles",false);b.grid("strictfp","(III)I","ids",true);b.grid("interface","(III)I","metas",true);
        // Probe world has resident synthetic chunks: delegate native side support to the prototype.
        int callback=b.field("faces","Ljava/util/function/ToIntFunction;"),apply=b.ref(11,"java/util/function/ToIntFunction","applyAsInt","(Ljava/lang/Object;)I"),ordinal=b.ref(10,"434349477","ordinal","()I");
        b.method("long","(IIIL434349477;Z)Z",4,7,7,188,10,58,6,25,6,3,27,79,25,6,4,28,79,25,6,5,29,79,25,6,6,25,4,182,ordinal>>8,ordinal,79,42,180,callback>>8,callback,25,6,185,apply>>8,apply,2,0,172);
        b.method("switch","()Z",1,1,3,172);b.method("package","()L67634208;",1,1,1,176);return b.finish();
    }
    private void grid(String name,String descriptor,String fieldName,boolean integer)throws IOException{
        String function=integer?"java/util/function/ToIntFunction":"java/util/function/Function";int callback=field(fieldName,"L"+function+";"),apply=ref(11,function,integer?"applyAsInt":"apply",integer?"(Ljava/lang/Object;)I":"(Ljava/lang/Object;)Ljava/lang/Object;");
        int[] prefix={6,188,10,58,4,25,4,3,27,79,25,4,4,28,79,25,4,5,29,79,42,180,callback>>8,callback,25,4,185,apply>>8,apply,2,0};
        int[] code=Arrays.copyOf(prefix,prefix.length+(integer?1:4));if(integer)code[prefix.length]=172;else{int cell=clazz("1123841866");code[prefix.length]=192;code[prefix.length+1]=cell>>8;code[prefix.length+2]=cell;code[prefix.length+3]=176;}method(name,descriptor,4,5,code);
    }
    static byte[] tree()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorTree","1581944406");int cells=b.field("cells","Ljava/util/List;"),add=b.ref(11,"java/util/Collection","addAll","(Ljava/util/Collection;)Z");b.method("try","(Ljava/util/Collection;DDDDDD)V",2,14,43,42,180,cells>>8,cells,185,add>>8,add,2,0,87,177);return b.finish();}
    static byte[] cell()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorCell","334744794");int type=b.field("collisionMode","L1384438816;");b.method("strictfp","(Ljava/lang/Object;)L1384438816;",1,2,42,180,type>>8,type,176);b.method("assert","(L334744794;)I",1,2,3,172);return b.finish();}
    /** Minimal actor data provider for the untouched native speed function. */
    static byte[] speedActor()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorSpeedProfile","200241649");int context=b.field("components","L358035993;"),held=b.field("heldItem","L211097523;");b.method("byte","()L358035993;",1,1,42,180,context>>8,context,176);b.method("case","()L211097523;",1,1,42,180,held>>8,held,176);return b.finish();}
    /** Original living tick requests damage through the host; world owns death/despawn. */
    static byte[] livingActor()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ActorLivingProfile","szserver/bridge/ActorSpeedProfile");
        int sink=b.field("livingDamage","Ljava/util/function/BiPredicate;"),box=b.ref(10,"java/lang/Float","valueOf","(F)Ljava/lang/Float;"),apply=b.ref(11,"java/util/function/BiPredicate","test","(Ljava/lang/Object;Ljava/lang/Object;)Z");
        b.field("livingRemainder","D");b.field("livingSubmerged","Z");b.field("livingProperties","[Ljava/lang/Object;");
        b.field("equipmentStats","Ljava/util/Map;");
        b.field("buffState","Ljava/lang/Object;");
        b.field("recoveryState","Ljava/lang/Object;");b.field("healthRules","Ljava/lang/Object;");
        b.field("recoveryFraction","D");
        b.method("if","(L49549491;F)Z",3,3,42,180,sink>>8,sink,43,36,184,box>>8,box,185,apply>>8,apply,3,0,172);
        // Native entity removal is a service boundary: the server retains dead NPCs.
        b.method("default","()V",0,1,177);return b.finish();
    }
    /** NPC owns its controller: deliver the original anomaly force directly instead of a local-player event. */
    static byte[] carousel()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ActorCarousel","1952056351");
        int init=b.ref(10,"1952056351","<init>","(L1886271463;)V"),force=b.ref(11,"1711115376","enum","(IL200241649;)V"),player=b.clazz("200241649");
        b.method("<init>","(L1886271463;)V",2,2,42,43,183,init>>8,init,177);
        b.method("this","(L351062380;I)V",3,3,42,28,43,192,player>>8,player,185,force>>8,force,3,0,177);return b.finish();
    }
    /** Resident contact flags are provided by the server; no world IO from the native kernel. */
    static byte[] velocityContact()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorVelocityContact","448718092");b.method("new","(L1943200621;)V",0,2,177);return b.finish();}
    /** The native kernel calls the server's already reviewed native collision resolver here. */
    static byte[] velocityMotion()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorVelocityMotion","58127775");int callback=b.field("move","Ljava/lang/Runnable;"),run=b.ref(11,"java/lang/Runnable","run","()V");b.method("else","(L1943200621;D)V",1,4,42,180,callback>>8,callback,185,run>>8,run,1,0,177);return b.finish();}
    /** Client-only sound notifications do not belong on an NPC world owner. */
    static byte[] velocityEvents()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorVelocityEvents","1429083604");b.method("class","(L1943200621;L200241649;FFF)V",0,6,177);b.method("static","(L1943200621;L200241649;)V",0,3,177);return b.finish();}
    /** Native water scanner reads only already resident voxel IDs. */
    static byte[] waterWorld()throws IOException{
        var b=new NativeActorBridge("szserver/bridge/ActorWaterWorld","1500514786");b.grid("strictfp","(III)I","ids",true);
        int resident=b.field("resident","Ljava/util/function/ToIntFunction;"),apply=b.ref(11,"java/util/function/ToIntFunction","applyAsInt","(Ljava/lang/Object;)I");var ops=new ArrayList<Integer>(List.of(16,6,188,10,58,7));
        for(int n=0;n<6;n++){ops.addAll(List.of(25,7,n+3,21,n+1,79));}
        ops.addAll(List.of(42,180,resident>>8,resident,25,7,185,apply>>8,apply,2,0,172));b.method("abstract","(IIIIII)Z",4,8,ops.stream().mapToInt(Integer::intValue).toArray());return b.finish();
    }
    static byte[] waterActor()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorWaterActor","351062380");int world=b.field("world","L1500514786;");b.method("double","()L1500514786;",1,1,42,180,world>>8,world,176);b.method("transient","()L1500514786;",1,1,42,180,world>>8,world,176);return b.finish();}
    /** Neutral NPC health-cost profile; native admission, repetition, debit and recovery are untouched. */
    static byte[] stamina()throws IOException{var b=new NativeActorBridge("szserver/bridge/ActorStamina","1960601713");b.method("return","()F",1,1,12,174);return b.finish();}
}
