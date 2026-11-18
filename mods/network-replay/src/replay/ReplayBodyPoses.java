package replay;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Native remains pose cache belongs to a reconstruction, not the menu's global module. */
final class ReplayBodyPoses implements ReplayRender.BodyPoses {
    interface Access {Object get()throws Exception;void set(Object cache)throws Exception;}
    final Access access;
    SourceAdapters decoder;
    Object saved;
    Map<Integer,Object> current;
    boolean bound;
    int generation;
    final List<Map<String,Object>> preparations=new ArrayList<>();
    final Map<Object,Map<String,Object>> preparing=new java.util.IdentityHashMap<>();
    long preparationCount;
    final Map<Integer,SceneTracks.Bones> retiredPoses=new HashMap<>();long recoveredOwnerPoses;
    final boolean bodyProbe=Boolean.getBoolean("sz.replay.qa.bodyprobe");
    final List<Map<String,Object>> physicsTrace=new ArrayList<>();
    record Physics(BodyPhysicsClock clock,java.lang.ref.WeakReference<Object> body,Map<String,Object> trace){}
    final Map<Object,Physics> physics=new java.util.WeakHashMap<>();
    Object recordedWorld;
    long physicsCalls,physicsWorlds,physicsBatches;
    double physicsElapsed;
    long physicsEpoch;
    ReplayBodyPoses(SourceAdapters adapters){this(new Access(){
        Object owner()throws Exception {
            for(String type:List.of("808445140","544082630","1100232767"))adapters.verify(type);
            // These native-only body implementation dependencies were added
            // after existing demos. Pin them in the mod as well as new archives;
            // no source packet or registry layout is inferred from this fallback.
            adapters.verifyDependency("241725195","a23a5968517c2f8f417e7cdf73571852caf69d3d2b928e076a50cdefa91bdb04");
            adapters.verifyDependency("1148853409","873d7e97f0fd1cfbab4b8f26b427ec7befb824b72026a3b552a66a66c33765c9");
            adapters.verifyDependency("295175521","51fbb508b3f6023c4ac3d126303a1ae2186007e206ac6bf1a223b8826e3e4545");
            return Native.field(Native.type("808445140"),"enum");
        }
        public Object get()throws Exception{return Native.field(owner(),"protected");}
        public void set(Object cache)throws Exception{SourceAdapters.put(owner(),"protected",cache);}
    });decoder=adapters;}
    ReplayBodyPoses(Access access){this.access=access;}
    synchronized void reset()throws Exception {
        if(!bound){saved=access.get();if(saved!=null&&!(saved instanceof Map))throw new IllegalStateException("Unexpected native remains pose cache");}
        Map<Integer,Object> fresh=new HashMap<>();access.set(fresh);current=fresh;bound=true;generation++;
        preparations.clear();preparing.clear();preparationCount=0;retiredPoses.clear();recoveredOwnerPoses=0;physics.clear();physicsTrace.clear();recordedWorld=null;physicsCalls=physicsWorlds=physicsBatches=0;physicsElapsed=0;physicsEpoch=ReplayClock.epoch;ReplayRender.bodyPoses=this;
    }
    public synchronized void admitted(Object actor)throws Exception {
        if(Boolean.getBoolean("sz.replay.qa.world-admission"))new Exception("Replay native world admission: "+actor).printStackTrace();
        if(ReplayRender.scenePresentation==null&&recordedWorld!=null&&Native.call(actor,"transient","1500514786")==recordedWorld)retiredPoses.remove(((Number)Native.call(actor,"for","int")).intValue());
    }
    public synchronized void retired(Object actor)throws Exception {
        if(ReplayRender.scenePresentation!=null||recordedWorld==null||!Native.type("351062380").isInstance(actor)||Native.call(actor,"transient","1500514786")!=recordedWorld)return;
        int id=((Number)Native.call(actor,"for","int")).intValue();
        if(ReplayRender.actorTimes instanceof ReplayActorTimes clocks)clocks.prepareNpcActor(actor,decoder);
        var value=NativeSceneCapture.characterPose(actor);if(value!=null){retiredPoses.put(id,ScenePoseCodec.read(value.pose()));if(retiredPoses.size()>4096)throw new java.io.IOException("Retired character pose limit exceeded");}
    }
    synchronized void observed(Object actor,SceneTracks.Bones pose)throws Exception {
        if(ReplayRender.scenePresentation!=null||recordedWorld==null||!Native.type("351062380").isInstance(actor)||Native.call(actor,"transient","1500514786")!=recordedWorld)return;
        retiredPoses.put(((Number)Native.call(actor,"for","int")).intValue(),pose);
    }
    public synchronized Object initial(Object remains,Object original)throws Exception {
        if(original!=null||ReplayRender.scenePresentation!=null||recordedWorld==null||Native.call(remains,"transient","1500514786")!=recordedWorld||(boolean)Native.field(remains,"double"))return original;
        // Re-admitted bodies clear copy-owner on the wire. An exact native body
        // cache wins; otherwise the last observed owner pose is still usable.
        int body=((Number)Native.call(remains,"for","int")).intValue();if(current!=null&&current.containsKey(body))return original;
        int owner=((Number)Native.field(remains,"case")).intValue();if(Native.call(recordedWorld,"continue","1217489680",owner)!=null)return original;
        var bones=retiredPoses.get(owner);if(bones==null)return original;
        Object pose=Native.type("1118769848").getConstructor(int.class,int.class).newInstance(bones.boneCount(),bones.curveCount());ScenePoseCodec.write(pose,bones);recoveredOwnerPoses++;return pose;
    }
    public synchronized void physicsCreated(Object body)throws Exception {
        if(!Native.type("1148853409").isInstance(body))return;
        Object component=Native.field(body,"public");Object remains=Native.call(component,"default","544082630");
        if(recordedWorld==null||Native.call(remains,"transient","1500514786")!=recordedWorld)return;
        Object world=Native.call(body,"class","295175521");
        if(world!=null&&!physics.containsKey(world)){
            Map<String,Object> trace=null;
            if(bodyProbe&&physicsTrace.size()<128){
                trace=new LinkedHashMap<>();trace.put("entity",Native.call(remains,"for","int"));trace.put("created_seconds",ReplayClock.seconds);
                trace.put("empty_world_native_batches",0L);trace.put("empty_pose_native_batches",0L);physicsTrace.add(trace);
            }
            Physics state=new Physics(new BodyPhysicsClock(ReplayClock.seconds),new java.lang.ref.WeakReference<>(remains),trace);
            physics.put(world,state);physicsWorlds++;if(trace!=null)observePhysics(world,state);
        }
    }
    synchronized void scope(Object world){recordedWorld=world;}
    boolean awaitReady()throws Exception {
        if(ready())return true;
        return ReplayPhysicsWake.await(Native.field(Native.type("126423472"),"switch"),this::ready,4_000_000L);
    }
    synchronized boolean ready()throws Exception {
        if(physicsEpoch!=ReplayClock.epoch)return true;
        for(var entry:physics.entrySet()){
            Physics value=entry.getValue();Object remains=value.body().get();if(remains==null)continue;
            if(value.trace()!=null)observePhysics(entry.getKey(),value);
            int id=((Number)Native.call(remains,"for","int")).intValue();
            if(recordedWorld==null||Native.call(recordedWorld,"continue","1217489680",id)!=remains)continue;
            // The native module queues excess ragdolls before admitting their
            // worlds (it limits active worlds to six). Waiting for queued worlds
            // to step prevents recorded time advancing and existing bodies from
            // retiring, so the queue can never drain.
            if(!(boolean)Native.call(entry.getKey(),"else","boolean"))continue;
            if((boolean)Native.call(entry.getKey(),"while","boolean"))continue;
            if(value.clock().wanted(ReplayClock.seconds)>value.clock().batches)return false;
        }
        return true;
    }
    public synchronized boolean physicsStep(Object world)throws Exception {
        Physics state=physics.get(world);if(state==null)return false;BodyPhysicsClock clock=state.clock();
        // A worker may finish while the game thread starts reconstruction.
        // Old worlds must not run against the new epoch before reset clears them.
        if(physicsEpoch!=ReplayClock.epoch)return true;
        // Indexed remains have recorded bone tracks. Their native worlds can
        // still be created by resource preparation, but must never integrate
        // the selected playhead as monotonically increasing simulation time.
        ReplayRender.ScenePresentation scene=ReplayRender.scenePresentation;
        if(scene!=null&&scene.simulationFrozen())return true;
        physicsCalls++;
        long before=clock.batches;double elapsed=clock.elapsed;
        int beforeBodies=state.trace()==null?-1:((Map<?,?>)Native.field(world,"byte")).size();
        boolean beforePose=state.trace()==null||observePhysics(world,state);
        if(state.trace()!=null&&beforePose&&clock.batches==0&&!state.trace().containsKey("initial_rigid_body_state"))
            state.trace().put("initial_rigid_body_state",rigidBodyState(world,state));
        long started=System.nanoTime();
        clock.advance(ReplayClock.seconds,((Number)Native.call(world,"do","int")).intValue(),
            delta->Native.call(world,"szreplay$original$while","void",delta),
            ()->!Boolean.getBoolean("sz.replay.qa.physicsretirement")||!(boolean)Native.call(world,"while","boolean"));
        physicsBatches+=clock.batches-before;physicsElapsed+=clock.elapsed-elapsed;
        if(state.trace()!=null){
            long stepped=clock.batches-before;Map<String,Object> row=state.trace();
            if(beforeBodies==0)row.put("empty_world_native_batches",((Number)row.get("empty_world_native_batches")).longValue()+stepped);
            if(!beforePose)row.put("empty_pose_native_batches",((Number)row.get("empty_pose_native_batches")).longValue()+stepped);
            double millis=(System.nanoTime()-started)/1e6;
            row.put("maximum_step_call_millis",Math.max(((Number)row.getOrDefault("maximum_step_call_millis",0d)).doubleValue(),millis));
            row.put("maximum_batches_per_call",Math.max(((Number)row.getOrDefault("maximum_batches_per_call",0L)).longValue(),stepped));
            observePhysics(world,state);
        }
        return true;
    }
    /** QA only: native solver inputs at the first step, before physics can amplify pose differences. */
    Map<String,Object> rigidBodyState(Object world,Physics state)throws Exception {
        Object remains=state.body().get();if(remains==null)return Map.of();
        int id=((Number)Native.call(remains,"for","int")).intValue();
        Object node=((Map<?,?>)Native.field(world,"byte")).get(id),pose=node==null?null:Native.field(node,"for");
        if(pose==null)return Map.of();
        Map<String,Object> rows=new java.util.TreeMap<>();
        for(var entry:((Map<?,?>)Native.field(pose,"import")).entrySet()){
            Object body=Native.call(entry.getValue(),"protected","com.bulletphysics.dynamics.RigidBody");
            Object vector=Native.type("javax.vecmath.Vector3f").getConstructor().newInstance();
            Object transform=Native.type("com.bulletphysics.linearmath.Transform").getConstructor().newInstance();
            Native.call(body,"getCenterOfMassTransform","com.bulletphysics.linearmath.Transform",transform);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("origin",rawFloatFields(Native.field(transform,"origin"),"x","y","z"));
            row.put("basis",rawFloatFields(Native.field(transform,"basis"),"m00","m01","m02","m10","m11","m12","m20","m21","m22"));
            row.put("linear_velocity",rawFloatFields(Native.call(body,"getLinearVelocity","javax.vecmath.Vector3f",vector),"x","y","z"));
            row.put("angular_velocity",rawFloatFields(Native.call(body,"getAngularVelocity","javax.vecmath.Vector3f",vector),"x","y","z"));
            row.put("inverse_mass_bits",Float.floatToRawIntBits(((Number)Native.call(body,"getInvMass","float")).floatValue()));
            rows.put(String.valueOf(entry.getKey()),row);
        }
        return Map.of("rigid_bodies",rows,"native_batches_before",state.clock().batches,"seconds",ReplayClock.seconds);
    }
    static List<Integer> rawFloatFields(Object object,String...fields)throws Exception {
        List<Integer> result=new ArrayList<>(fields.length);
        for(String field:fields)result.add(Float.floatToRawIntBits(((Number)Native.field(object,field)).floatValue()));
        return result;
    }
    /** Explicit QA probe reads native state; it never admits or finalizes a body. */
    boolean observePhysics(Object world,Physics state)throws Exception {
        Map<String,Object> row=state.trace();if(row==null)return true;
        Object remains=state.body().get();if(remains==null){row.put("entity_collected",true);return false;}
        int id=((Number)Native.call(remains,"for","int")).intValue();Map<?,?> bodies=(Map<?,?>)Native.field(world,"byte");Object node=bodies.get(id);
        boolean hasPose=node!=null&&Native.field(node,"for")!=null;
        row.put("last_observed_seconds",ReplayClock.seconds);row.put("native_batches",state.clock().batches);row.put("native_body_count",bodies.size());
        row.put("native_node_registered",node!=null);row.put("native_node_pose",hasPose);row.put("world_retired",Native.call(world,"while","boolean"));
        row.put("entity_registered",recordedWorld!=null&&Native.call(recordedWorld,"continue","1217489680",id)==remains);
        Object renderer=Native.field(remains,"long");row.put("renderer",renderer==null?"":renderer.getClass().getName());
        if(renderer!=null&&renderer.getClass().getName().equals("1326794892")){
            row.put("renderer_pending_pose",Native.field(renderer,"assert")!=null);row.put("renderer_physics_seen",Native.field(renderer,"for"));
        }
        if(node!=null){row.put("native_node_done",Native.call(node,"extends","boolean"));row.put("native_node_active",Native.call(node,"else","boolean"));}
        if(hasPose&&!row.containsKey("first_physical_pose_seconds")){row.put("first_physical_pose_seconds",ReplayClock.seconds);row.put("batches_before_physical_pose",state.clock().batches);}
        if(current!=null&&current.containsKey(id)&&!row.containsKey("cache_first_seen_seconds"))row.put("cache_first_seen_seconds",ReplayClock.seconds);
        row.put("cache_present",current!=null&&current.containsKey(id));
        row.put("native_module_active_worlds",((List<?>)Native.field(Native.type("666519350"),"this")).size());
        row.put("native_module_pending_nodes",((List<?>)Native.field(Native.type("666519350"),"try")).size());
        return hasPose;
    }
    synchronized Map<String,Object> physicsSnapshot()throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("recorded_worlds",physicsWorlds);result.put("live_worlds",physics.size());result.put("calls",physicsCalls);
        result.put("retired_character_poses",retiredPoses.size());result.put("recovered_owner_poses",recoveredOwnerPoses);
        result.put("native_batches",physicsBatches);result.put("recorded_elapsed_seconds",physicsElapsed);
        if(bodyProbe){
            for(var entry:physics.entrySet())observePhysics(entry.getKey(),entry.getValue());
            result.put("native_admission_trace",physicsTrace.stream().map(LinkedHashMap::new).toList());
            List<Map<String,Object>> poses=new ArrayList<>();
            if(current!=null)for(var entry:new java.util.TreeMap<>(current).entrySet()){
                Object pose=Native.call(entry.getValue(),"byte","1118769848");poses.add(poseDigest(entry.getKey(),pose));
            }
            result.put("cached_pose_digests",poses);
        }
        return result;
    }
    static Map<String,Object> poseDigest(int id,Object pose)throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
        Object[] positions=(Object[])Native.field(pose,"for"),rotations=(Object[])Native.field(pose,"double");float[] curves=(float[])Native.field(pose,"strictfp");
        out.writeInt(positions.length);out.writeInt(curves.length);
        for(int n=0;n<positions.length;n++){
            for(String field:List.of("assert","byte","catch"))out.writeInt(Float.floatToRawIntBits(((Number)Native.field(positions[n],field)).floatValue()));
            for(String field:List.of("public","implements","float","catch"))out.writeInt(Float.floatToRawIntBits(((Number)Native.field(rotations[n],field)).floatValue()));
        }
        for(float value:curves)out.writeInt(Float.floatToRawIntBits(value));
        byte[] raw=bytes.toByteArray();Map<String,Object> result=new LinkedHashMap<>();
        result.put("entity",id);result.put("bones",positions.length);result.put("curves",curves.length);
        result.put("pose_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
        if(Boolean.getBoolean("sz.replay.qa.posevalues"))result.put("pose_raw_float_bits_base64",java.util.Base64.getEncoder().encodeToString(raw));
        return result;
    }
    /** QA: record sampler state without exposing model arrays or modifying caches. */
    static Map<String,Object> samplerScalars(Object sampler)throws Exception {
        Map<String,Object> result=new java.util.TreeMap<>();if(sampler==null)return result;
        for(var field:sampler.getClass().getDeclaredFields()){
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            field.setAccessible(true);Object value=field.get(sampler);
            if(value==null||value instanceof String||value instanceof Boolean||value instanceof Number){
                if(value instanceof Double d&&!Double.isFinite(d)||value instanceof Float f&&!Float.isFinite(f))value=String.valueOf(value);
                result.put(field.getName(),value);
            }
        }
        return result;
    }
    static Map<String,Object> visualSamplerState(Object actor)throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        Map<?,?> tpStates=(Map<?,?>)Native.field(Native.type("szgmod.SourceTpWeaponLayer"),"states");
        Object tp=tpStates.get(actor);result.put("weapon_registered",tp!=null);result.put("weapon",samplerScalars(tp));
        if(tp!=null)result.put("weapon_transition",samplerScalars(Native.field(tp,"transition")));
        Map<?,?> bodies=(Map<?,?>)Native.field(Native.type("szgmod.SourcePoseBlend"),"bodies");
        Map<?,?> channels=(Map<?,?>)bodies.get(actor);Map<String,Object> clips=new java.util.TreeMap<>();
        if(channels!=null)for(var entry:channels.entrySet())clips.put((String)entry.getKey(),samplerScalars(entry.getValue()));
        result.put("body_channels",clips);result.put("clock_ns",ReplayClock.visualNanos());return result;
    }
    static Map<String,Object> graphScalars(Object state)throws Exception {
        Map<String,Object> result=new java.util.TreeMap<>();
        for(java.lang.reflect.Field field:state.getClass().getFields()){
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            Object value=field.get(state);
            if(value instanceof Float number&&!Float.isFinite(number))value=number.toString();
            if(value instanceof Double number&&!Double.isFinite(number))value=number.toString();
            if(value==null||value instanceof Number||value instanceof Boolean||value instanceof String||value instanceof java.util.UUID)
                result.put(field.getName()+":"+field.getType().getName(),value instanceof java.util.UUID?value.toString():value);
            else if(value instanceof Enum<?> enumeration)result.put(field.getName()+":"+field.getType().getName(),enumeration.name());
        }
        return result;
    }
    public synchronized void prepared(Object remains,Object suppliedPose,int stage)throws Exception {
        if(stage==0){
            if(suppliedPose==null&&ReplayRender.actorTimes instanceof ReplayActorTimes clocks)clocks.prepareNpcOwner(remains,decoder);
            preparationCount++;
            if(preparations.size()>=128)return;
            Map<String,Object> row=new LinkedHashMap<>();
            int id=((Number)Native.call(remains,"for","int")).intValue();
            int owner=((Number)Native.field(remains,"case")).intValue();
            Object world=Native.call(remains,"transient","1500514786");
            Object actor=Native.call(world,"continue","1217489680",owner);
            row.put("seconds",ReplayClock.seconds);row.put("entity",id);row.put("owner",owner);
            row.put("owner_registered",actor!=null);row.put("owner_class",actor==null?"":actor.getClass().getName());
            if(bodyProbe&&actor!=null&&Native.type("1848416318").isInstance(actor)){
                Object timer=Native.call(actor,"do","805719214");row.put("owner_timer_class",timer.getClass().getName());row.put("owner_timer_seconds",Native.call(timer,"do","double"));
                Object rig=Native.field(actor,"native"),animation=Native.call(Native.call(rig,"short","1805188833"),"super","387875964");
                Object animationTimer=Native.field(animation,"extends");row.put("animation_timer_class",animationTimer.getClass().getName());
                row.put("animation_timer_seconds",Native.call(animationTimer,"do","double"));row.put("animation_cached_seconds",Native.field(animation,"szFp_finally"));
                row.put("animation_origin_seconds",Native.field(animation,"szFp_extends"));
                Object controller=Native.call(rig,"short","1805188833"),provider=Native.call(rig,"for","1581759465");
                row.put("owner_graph_provider_class",provider.getClass().getName());
                row.put("owner_provider_state",graphScalars(Native.call(provider,"package","578131936")));
                row.put("owner_last_frame_state",graphScalars(Native.field(rig,"finally")));
                row.put("owner_next_frame_state",graphScalars(Native.field(rig,"this")));
                row.put("owner_parameters_seconds",Native.field(controller,"case"));
                row.put("owner_graph_loaded",Native.call(controller,"private","boolean"));
                Object tree=Native.call(animation,"static","1391506621");row.put("animation_tree_class",tree==null?"":tree.getClass().getName());
                row.put("animation_case",Native.field(animation,"case"));
                int actorId=((Number)Native.call(actor,"for","int")).intValue();
                row.put("owner_pending_tails",decoder==null?List.of():ReplayDecode.pendingTailDetails(decoder).stream().filter(value->((Number)value.get("entity")).intValue()==actorId).toList());
                row.put("owner_visual_samplers",visualSamplerState(actor));
                if(Boolean.getBoolean("sz.replay.qa.graphprobe")){
                    row.put("native_graph_context",graphScalars(animation));
                    row.put("native_graph_snapshot",ReplayGraphProbe.capture(tree,Native.field(Native.field(controller,"const"),"for")));
                }
            }
            row.put("discard_without_pose",Native.call(remains,"h","boolean"));
            row.put("copy_owner_pose",Native.field(remains,"transient"));row.put("gib",Native.field(remains,"double"));
            row.put("supplied_pose",suppliedPose!=null);row.put("cached_pose",current!=null&&current.containsKey(id));
            preparations.add(row);preparing.put(remains,row);
        }else{
            Map<String,Object> row=preparing.remove(remains);if(row==null)return;
            row.put("hidden",Native.call(remains,"i","boolean"));row.put("kind",String.valueOf(Native.field(remains,"import")));
            Object renderer=Native.field(remains,"long");row.put("pose_renderer",renderer==null?"":renderer.getClass().getName());
            row.put("copy_owner_pose_after",Native.field(remains,"transient"));
            if(bodyProbe){
                Object ownerPose=Native.field(remains,"throw");
                if(ownerPose!=null)row.put("initial_owner_pose_digest",poseDigest(((Number)row.get("entity")).intValue(),ownerPose));
                if(renderer!=null&&renderer.getClass().getName().equals("1326794892")){
                    Object pose=Native.call(Native.field(renderer,"double"),"try","1118769848");
                    row.put("initial_renderer_pose_digest",poseDigest(((Number)row.get("entity")).intValue(),pose));
                }
            }
        }
    }
    int entries(){return current==null?0:current.size();}
    synchronized void close()throws Exception {
        if(!bound)return;
        if(ReplayRender.bodyPoses==this)ReplayRender.bodyPoses=null;preparing.clear();retiredPoses.clear();physics.clear();recordedWorld=null;
        access.set(saved);saved=null;current=null;bound=false;
    }
}
