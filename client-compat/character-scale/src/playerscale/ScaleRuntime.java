package playerscale;

import java.io.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import static playerscale.Native.*;

/** All native access stays on the game/render thread. Network readers only enqueue immutable values. */
public final class ScaleRuntime {
    private record Incoming(Object connection,ScaleProtocol.Snapshot packet){}
    private static final ArrayBlockingQueue<Incoming> incoming=new ArrayBlockingQueue<>(256);
    private static final ScaleReplica replica=new ScaleReplica();
    private static final Map<Object,Float> bodies=new WeakHashMap<>();
    private static Object connection,world,player;private static boolean hello,acknowledged;private static long lastHello;
    public static volatile String lastError="";public static long received,rendered,bodyUpdates,hitUpdates,cameraOffsets,firstPersonBodies;
    private ScaleRuntime(){}
    public static long graphInputs;public static float graphSpeed,graphCoefficient;
    /** This writer receives fresh physical velocity; network graph packets are normalized separately on the server. */
    public static float animationSpeed(float speed,Object entity){graphInputs++;return graphSpeed=speed/factor(entity);}
    /** Only the local controller receives the server's scaled movement configuration. */
    public static float animationCoefficient(float value,Object entity){if(entity!=player)return value;return graphCoefficient=value/factor(entity);}
    public static byte[] read(DataInput in)throws IOException{return ScaleProtocol.read(in);}
    public static void receive(Object source,byte[] payload){
        ScaleProtocol.Snapshot packet=ScaleProtocol.decode(payload);
        if(!incoming.offer(new Incoming(source,packet)))lastError="character_scale_queue_full";
    }
    public static void reset(){incoming.clear();replica.connection(null);connection=null;world=null;player=null;hello=false;acknowledged=false;bodies.clear();}
    public static void frame(Object game){
        if(!ScaleAgent.installed||!ScaleAgent.healthy)return;
        try{
            Object next=call(game,"transient","1219970978"),nextPlayer=get(game,"e"),nextWorld=get(game,"g");
            if(next!=connection){replica.connection(next);connection=next;hello=false;acknowledged=false;bodies.clear();}
            if(nextWorld!=world||nextPlayer!=player){world=nextWorld;player=nextPlayer;hello=false;acknowledged=false;bodies.clear();}
            if(connection==null||player==null||world==null)return;
            if(!hello||!acknowledged&&System.nanoTime()-lastHello>1_000_000_000L){Object request=Class.forName(ScaleProtocol.INPUT).getConstructor(byte[].class).newInstance((Object)ScaleProtocol.hello());
                Object envelope=Class.forName("385693344").getConstructor(Class.forName("499058674")).newInstance(request);call(connection,"else","void",envelope);hello=true;lastHello=System.nanoTime();}
            Incoming entry;while((entry=incoming.poll())!=null)if(replica.receive(entry.connection(),entry.packet())){
                received++;if(entry.packet().entity()==0)acknowledged=true;Object entity=entry.packet().entity()==0?player:call(world,"continue","1217489680",entry.packet().entity());if(entity!=null)bindEntity(entity);
            }
            bindEntity(player);
        }catch(Exception failure){error(failure);}
    }
    public static float factor(Object entity){
        if(entity==null||connection==null||!ScaleAgent.healthy)return 1;
        try{if(entity==player)return replica.scale(0).factor();int id=((Number)call(entity,"for","int")).intValue();return id<2?1:replica.scale(id).factor();}
        catch(Exception failure){error(failure);return 1;}
    }
    private static void bindEntity(Object entity)throws Exception {
        Object controller=call(Class.forName("1704556748"),"return","1704556748",entity),state=call(controller,"if","1943200621");
        float scale=factor(entity);Float previous=bodies.put(state,scale);body(state);
        if(previous==null||previous.floatValue()!=scale)call(state,"long","void");
    }
    public static void bind(Object state,Object entity){try{bodies.put(state,factor(entity));body(state);}catch(Exception failure){error(failure);}}
    private static float bodyFactor(Object state)throws Exception {
        Float known=bodies.get(state);if(known!=null)return known;
        // Native prediction snapshots already carry the width, so copies retain scale.
        float inferred=((Number)get(state,"bc")).floatValue()/.6f;
        return Float.isFinite(inferred)&&inferred>=ScaleValue.MIN&&inferred<=ScaleValue.MAX?inferred:1;
    }
    public static void body(Object state){
        if(connection==null)return;
        try{float scale=bodyFactor(state);Object posture=call(state,"throw","430277821");if(posture==null)return;
            float height=((Number)call(Class.forName("1943200621"),"const","float",posture)).floatValue();
            set(state,"bc",.6f*scale);set(state,"else",height*scale);bodyUpdates++;
        }catch(Exception failure){error(failure);}
    }
    public static float eye(float original,Object state){try{return original*bodyFactor(state);}catch(Exception failure){error(failure);return original;}}
    public static float camera(float original,Object camera){try{float scale=factor(get(camera,"native"));if(scale!=1)cameraOffsets++;return original*scale;}catch(Exception failure){error(failure);return original;}}
    /** Same native matrix-stack scale used by 368424395 for mobs. No GL hooks or asset edits. */
    public static boolean renderBegin(Object entity){
        try{bindEntity(entity);}catch(Exception failure){error(failure);}
        float scale=factor(entity);if(scale==1)return false;
        try{Class<?> matrix=Class.forName("889335306");call(matrix,"if","void");try{call(matrix,"else","void",scale,scale,scale);}catch(Throwable failure){call(matrix,"default","void");throw failure;}rendered++;return true;}
        catch(Exception failure){error(failure);return false;}
    }
    public static void renderEnd(boolean changed){if(changed)try{call(Class.forName("889335306"),"default","void");}catch(Exception failure){throw new IllegalStateException("character_scale_matrix_restore",failure);}}
    /** Native FP body bypasses the third-person renderer. Its feet transform is already applied. */
    public static void firstPersonBody(Object entity){
        float scale=factor(entity);if(scale==1)return;
        try{call(Class.forName("889335306"),"else","void",scale,scale,scale);firstPersonBodies++;}
        catch(Exception failure){throw new IllegalStateException("character_scale_first_person_body",failure);}
    }
    /** Reuses the native mob hitbox transform; vanilla players supplied a fixed factor of one. */
    public static void hit(Object geometry,Object transform,float original,float offset,Object entity){
        try{float scale=factor(entity);call(geometry,"super","void",transform,original*scale,offset*scale);if(scale!=1)hitUpdates++;}
        catch(Exception failure){throw new IllegalStateException("character_scale_hit_geometry",failure);}
    }
    private static void error(Exception failure){String text=failure.toString();if(!text.equals(lastError))System.err.println("[CharacterScale] "+text);lastError=text;}
}
