package szmods.runtime;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Server-selected poses are applied after native providers calculate the current frame. */
public final class AnimationHooks {
    private record Packet(Object connection, int entity, long revision, String pose) {}
    private record Pose(long revision, String name, long started) {}
    private static final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(256);
    private static final Map<Integer, Pose> poses = new HashMap<>();
    private static final Map<Object, Object[]> saved = new WeakHashMap<>();
    private static final Map<Object, Pose> drones = new WeakHashMap<>();
    private static final Map<Object, Pose> targets = new IdentityHashMap<>();
    private static Object connection, world, localPlayer;
    private static final String[] FIELDS = {"byte", "void", "switch", "return", "d", "private", "const", "long", "char", "enum"};
    public static long received, applied;
    public static String lastPose = "", lastError = "";

    public static byte[] read(DataInput in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int version = in.readUnsignedByte();
        if (version != 1) throw new IOException("Unsupported animation protocol");
        out.writeByte(version); out.writeInt(in.readInt()); out.writeLong(in.readLong());
        int length = in.readUnsignedByte();
        if (length > 63) throw new IOException("Animation name too long");
        byte[] name = new byte[length]; in.readFully(name);
        for(byte c : name) if(c < 32 || c > 126) throw new IOException("Invalid animation name");
        out.writeByte(length); out.write(name); return bytes.toByteArray();
    }

    public static void receive(Object source, byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if(in.readUnsignedByte()!=1) throw new IOException("Invalid version");
            int entity = in.readInt(); long revision = in.readLong();
            int n = in.readUnsignedByte(); String pose = new String(in.readNBytes(n), java.nio.charset.StandardCharsets.US_ASCII);
            if(!queue.offer(new Packet(source,entity,revision,pose))) throw new IllegalStateException("Animation queue overflow");
        } catch(IOException e) { throw new IllegalArgumentException(e); }
    }

    static synchronized void frame(Object game) {
        try {
            Object nextWorld = field(game,"g"), nextPlayer = field(game,"e");
            Object nextConnection = call(game,"transient","1219970978");
            if(nextWorld!=world || nextConnection!=connection || nextPlayer!=localPlayer) {
                for(Object graph : new ArrayList<>(saved.keySet())) before(null,graph);
                NamedAnimations.reset();
                if(nextConnection!=connection)poses.clear();
                drones.clear();targets.clear();
                world=nextWorld;connection=nextConnection;localPlayer=nextPlayer;
            }
            Packet p;
            while((p=queue.poll())!=null) {
                if(p.connection!=connection || world==null) continue;
                Pose old=poses.get(p.entity);
                if(old!=null && old.revision>=p.revision) continue;
                poses.put(p.entity,new Pose(p.revision,p.pose,System.nanoTime()));
                received++;lastPose=p.pose;
                System.out.println("[Animation] received entity="+p.entity+" revision="+p.revision+" pose="+p.pose);
            }
            targets.clear();
            if(world!=null)for(var entry:poses.entrySet()){
                Object entity=entry.getKey()==0?localPlayer:world.getClass().getMethod("continue",int.class).invoke(world,entry.getKey());
                if(entity!=null)targets.put(entity,entry.getValue());
            }
            Set<Object> active=Collections.newSetFromMap(new IdentityHashMap<>());
            active.addAll(targets.keySet());active.addAll(drones.keySet());
            NamedAnimations.retainEntities(active);
        } catch(Exception e) { lastError=e.toString(); }
    }

    public static synchronized void drone(Object entity, boolean enabled) {
        if(entity==null)return;
        if(enabled) drones.computeIfAbsent(entity,k->new Pose(0,"pda_sit_idle",System.nanoTime()));
        else drones.remove(entity);
    }

    public static synchronized void before(Object entity, Object graph) {
        Object[] values=saved.remove(graph); if(values==null)return;
        try { for(int i=0;i<FIELDS.length;i++) graph.getClass().getField(FIELDS[i]).set(graph,values[i]); }
        catch(Exception e){lastError=e.toString();throw new IllegalStateException(e);}
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    public static synchronized Object after(Object entity, Object graph) {
        try {
            Pose pose=drones.get(entity);
            // 351062380 is the entity's base class, not a shared provider context.
            // The local-id alias must never affect an observer or clone.
            if(pose==null) pose=targets.get(entity);
            if(pose==null || pose.name.isEmpty()) {NamedAnimations.apply(entity,null);return graph;}
            Object stance;
            boolean action;
            try{stance=Enum.valueOf((Class)Class.forName("430277821"),pose.name);action=false;}
            catch(IllegalArgumentException named){stance=Enum.valueOf((Class)Class.forName("430277821"),"STAND");action=true;}
            boolean playing=NamedAnimations.apply(entity,action?pose.name:null);
            if(action&&!playing)return graph;
            Object[] values=new Object[FIELDS.length];
            for(int i=0;i<FIELDS.length;i++) values[i]=graph.getClass().getField(FIELDS[i]).get(graph);
            saved.put(graph,values);
            set(graph,"byte",stance);set(graph,"void",stance);
            set(graph,"switch",Enum.valueOf((Class)Class.forName("496896492"),"NONE"));
            set(graph,"return",0f);set(graph,"d",0f);
            set(graph,"private","LB_1");
            set(graph,"const",(System.nanoTime()-pose.started)/1e9f);
            set(graph,"long",true);set(graph,"char",false);
            // A held weapon graph must not replace a full-body named pose.
            if(action)set(graph,"enum",null);
            applied++;return graph;
        } catch(Exception e){
            String error=e.toString();
            if(!error.equals(lastError))System.err.println("[Animation] override failed: "+error);
            lastError=error;
            before(entity,graph);
            return graph;
        }
    }
    private static void set(Object o,String n,Object v)throws Exception{o.getClass().getField(n).set(o,v);}
    private static Object field(Object o,String n)throws Exception {
        if(o==null)return null;
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(n);
    }
    private static Object call(Object o,String n,String r)throws Exception {
        for(Method m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==0&&m.getReturnType().getName().equals(r))return m.invoke(o);
        throw new NoSuchMethodException(n+":"+r);
    }
}
