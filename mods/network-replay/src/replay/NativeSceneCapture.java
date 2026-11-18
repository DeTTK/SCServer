package replay;

import java.nio.file.*;
import java.util.*;

/** Preparation probe exports real native presentation arrays; it never claims unimplemented domains. */
final class NativeSceneCapture {
    final Path output;final DemoFile demo;final SceneTrackBuilder builder=new SceneTrackBuilder();
    final IdentityHashMap<Object,SceneTrackBuilder.ObjectRow> active=new IdentityHashMap<>();
    final Map<Object,String> skeletonIds=new WeakHashMap<>();final Map<String,SceneTracks.Bytes> skeletonDefinitions=new HashMap<>();
    final Map<Object,SceneTracks.Bytes> spawnTemplates=new IdentityHashMap<>();
    final Set<String> unsupportedAttributes=new TreeSet<>();long attributeSamples,spawnSamples;
    final Map<String,String> unsupportedTypes=new TreeMap<>();
    final Map<Object,Long> graphWaits=new WeakHashMap<>();long graphWaitChecks,forcedPoses;
    Object world;int session;SceneTrackBuilder.ObjectRow environment;long previous=-1,samples,poses,transforms,maximumGap;
    final SceneBakeClock clock=new SceneBakeClock();
    final NativeSceneMapCapture mapCapture;
    boolean finished,aborted,written;String failureReason="";
    NativeSceneCapture(Path output,DemoFile demo){this.output=output;this.demo=demo;mapCapture=output!=null&&Boolean.getBoolean("sz.replay.prepare-scene.map")?new NativeSceneMapCapture(builder):null;}
    /** Bake a fixed 60-Hz time grid; preparation CPU/IO delays must not skip recorded poses. */
    long drive(Timeline timeline,NativePackets.World replay)throws Exception {
        if(finished)return -1;
        return clock.elapsed(timeline,replay.bootstrapped,previous==timeline.position,finished);
    }
    void abort(Throwable failure){
        if(finished)return;aborted=finished=true;failureReason=failure.toString();if(mapCapture!=null)mapCapture.close();
        try{var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));Files.writeString(output.resolveSibling(output.getFileName()+".failure.txt"),text.toString());}catch(Exception diagnostic){failure.addSuppressed(diagnostic);}
    }
    void sample(NativePackets.World replay,long time)throws Exception {
        try{sampleFrame(replay,time);}catch(Exception failure){abort(failure);throw failure;}
    }
    void sampleFrame(NativePackets.World replay,long time)throws Exception {
        if(finished||!replay.bootstrapped||time==previous)return;
        if(previous>time)throw new IllegalStateException("Scene preparation requires one forward pass");
        Object next=Native.call(replay.connection,"instanceof","1054393445");if(next==null)return;
        if(world!=next){
            if(world!=null){for(var row:active.values())builder.died(row,time);builder.died(environment,time);session++;}
            active.clear();world=next;environment=builder.born(session,Integer.MIN_VALUE,"world-environment",time);
        }
        if(mapCapture!=null&&!mapCapture.prepare(world,time))return;
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        float alpha=FreeCamera.renderAlpha();
        for(Object entity:entities(world)){
            if(!Native.type("1217489680").isInstance(entity))continue;
            seen.add(entity);int id=((Number)Native.call(entity,"for","int")).intValue();
            if(mapCapture!=null&&id<=-2_000_000_000)throw new java.io.IOException("Recorded entity ID occupies the reserved map metadata range: "+id);
            SceneTrackBuilder.ObjectRow row=active.get(entity);if(row==null){row=builder.born(session,id,entity.getClass().getName(),time);active.put(entity,row);
                if(SceneNativeTemplate.supports(row.type))row.put("native-template",SceneTracks.Domain.LIFECYCLE,SceneTracks.Blend.HOLD,time,SceneNativeTemplate.capture(entity),false);
            }
            SceneTracks.Bytes spawn=spawnTemplates.get(entity);if(spawn!=null)row.put("spawn-event",SceneTracks.Domain.LIFECYCLE,SceneTracks.Blend.HOLD,time,spawn,false);
            if(entity.getClass().getName().equals("961798934"))row.put("recorder-template",SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,SceneRecorderTemplate.capture(entity),false);
            if(SceneRemains.is(entity)){
                row.put("body-hidden",SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,new SceneTracks.Numbers((boolean)Native.call(entity,"i","boolean")?1:0),false);
                row.put("body-mode",SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,SceneRemains.captureMode(entity),false);
            }
            Object p=Native.call(Native.type("576005245"),"char","153304200",entity,alpha);
            row.put("position",SceneTracks.Domain.TRANSFORMS,SceneTracks.Blend.LINEAR,time,new SceneTracks.Numbers(number(p,"case"),number(p,"void"),number(p,"import")),false);transforms++;
            if(Native.type("820497227").isInstance(entity)){
                double yaw=FreeCamera.interpolateYaw(((Number)Native.call(entity,"throws","float")).floatValue(),((Number)Native.call(entity,"catch","float")).floatValue(),alpha);
                float before=((Number)Native.call(entity,"class","float")).floatValue(),after=((Number)Native.call(entity,"volatile","float")).floatValue();
                row.put("look",SceneTracks.Domain.TRANSFORMS,SceneTracks.Blend.ANGLES,time,new SceneTracks.Numbers(yaw,before+(after-before)*alpha),false);
            }
            forcePose(entity,replay);
            Object controller=SceneControllerItem.controller(entity);
            if(controller!=null)row.put("controller-held-item",SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,SceneControllerItem.encode(Native.call(controller,"throws","211097523")),false);
            Object hands=SceneSecondaryRigs.hands(entity);
            if(hands!=null)row.put("hands-held-item",SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,SceneControllerItem.encode(Native.field(hands,"try")),false);
            Object equipment=SceneSecondaryRigs.equipment(entity);
            if(equipment!=null)row.put("equipment-held-item",SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,SceneControllerItem.encode(Native.call(equipment,"const","211097523")),false);
            RigPose rigPose=characterPose(entity);
            if(rigPose!=null){
                SceneTracks.Bones value=bones(rigPose.pose());String skeleton=skeletonId(rigPose.skeleton());
                replay.bodyPoses.observed(entity,value);
                SceneRigTracks.put(row,"",time,skeleton,skeletonDefinitions.get(skeleton),value);poses++;
            }
            Map<String,Object> secondary=SceneSecondaryRigs.graphs(entity,NativeScenePresenter.graphIdentity(entity));
            for(String role:SceneSecondaryRigs.ROLES){
                String prefix="attachment/"+role+"/";Object graph=secondary.get(role);
                // Evaluate the renderer's pose at this clock even outside the camera.
                Object pose=graph==null?null:evaluateAttachment(graph);
                Object definition=graph==null?null:Native.call(graph,"strictfp","1612003613");
                if(pose!=null&&definition!=null){String skeleton=skeletonId(definition);SceneRigTracks.put(row,prefix,time,skeleton,skeletonDefinitions.get(skeleton),bones(pose));}
                else SceneRigTracks.put(row,prefix,time,null,null,null);
            }
            attributes(entity,row,time);
            if(Native.type("200241649").isInstance(entity)){var movement=SceneFpMovement.capture(entity);if(movement!=null)row.put("fp-movement",SceneTracks.Domain.TRANSFORMS,SceneTracks.Blend.HOLD,time,movement,false);}
        }
        for(var iterator=active.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!seen.contains(entry.getKey())){builder.died(entry.getValue(),time);spawnTemplates.remove(entry.getKey());iterator.remove();}}
        long ticks=((Number)Native.call(world,"if","long")).longValue();
        environment.put("day-ticks",SceneTracks.Domain.ENVIRONMENT,SceneTracks.Blend.LINEAR,time,new SceneTracks.Numbers(ticks),false);
        ReplayClock.ClockOrigins origins=ReplayClock.captureClocks();
        environment.put("clocks",SceneTracks.Domain.ENVIRONMENT,SceneTracks.Blend.HOLD,time,SceneClockCodec.encode(new SceneClockCodec.Clock(origins.visualOriginNs(),origins.environmentOriginNs(),origins.epochMillis(),origins.originSeconds(),origins.rate())),false);
        Object parameters=Native.call(Native.type("569545768"),"final","648055939");
        for(var e:SceneEnvironmentCodec.read(parameters).entrySet())environment.put("shader/"+e.getKey(),SceneTracks.Domain.ENVIRONMENT,e.getValue().blend(),time,e.getValue().value(),false);
        if(mapCapture!=null)mapCapture.sample(world,environment,time);
        if(previous>=0)maximumGap=Math.max(maximumGap,time-previous);previous=time;samples++;
        if(mapCapture!=null&&samples%60==0)Files.writeString(output.resolveSibling(output.getFileName()+".map-progress.json"),new com.google.gson.Gson().toJson(mapCapture.diagnostic()));
        if(time==demo.durationNs){
            // These are real native arrays, but map, effects, audio and appearance
            // export are not yet complete. The production player rejects this file.
            SceneTracks scene=builder.finish(demo,Set.of());ScenePagedArchive.write(output,scene);written=finished=true;
            if(mapCapture!=null)Files.writeString(output.resolveSibling(output.getFileName()+".map.json"),new com.google.gson.Gson().toJson(mapCapture.diagnostic()));
            if(mapCapture!=null)mapCapture.close();
        }
    }
    static Object evaluateAttachment(Object graph)throws Exception {
        // Wear graphs deliberately disable implicit evaluation (case=false).
        // Their getter returns the last buffer; the normal head renderer calls
        // do() first. Preparation must do that even outside the camera.
        Native.call(graph,"do","void");
        return Native.call(graph,"extends","1118769848");
    }
    static double number(Object value,String field)throws Exception {return ((Number)Native.field(value,field)).doubleValue();}
    /** Preparation may inspect a character later from any camera; graph availability cannot depend on culling. */
    boolean ready(NativePackets.World replay)throws Exception {
        if(finished||!replay.bootstrapped)return true;Object nativeWorld=Native.call(replay.connection,"instanceof","1054393445");boolean ready=true;
        for(Object entity:entities(nativeWorld))if(Native.type("200241649").isInstance(entity)){
            Object component=Native.call(Native.type("1478690128"),"new","1579323378",entity);if(component==null){ready=false;continue;}
            Object controller=Native.call(component,"static","1805188833");if((boolean)Native.field(controller,"throws"))throw new java.io.IOException("Scene preparation character graph failed: "+Native.call(entity,"for","int"));
            if(Native.field(controller,"short")==null)Native.call(component,"break","void");
            if(!(boolean)Native.call(controller,"private","boolean")){
                Object future=Native.field(Native.field(controller,"default"),"boolean");
                if(future instanceof java.util.concurrent.CompletableFuture<?> task&&task.isDone()){
                    try{task.join();}catch(RuntimeException failure){throw new java.io.IOException("Scene preparation graph compilation failed",failure);}
                    Native.call(Native.call(Native.type("1852960784"),"for","1852960784"),"case","void");
                }
            }
            if(!(boolean)Native.call(controller,"private","boolean")){
                ready=false;graphWaitChecks++;long began=graphWaits.computeIfAbsent(controller,key->System.nanoTime());
                if(System.nanoTime()-began>30_000_000_000L)throw new java.io.IOException("Scene preparation graph loading timed out");
            }else graphWaits.remove(controller);
        }
        return ready;
    }
    static List<Object> entities(Object world)throws Exception {
        List<Object> result=new ArrayList<>();Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object entity:(Collection<?>)Native.call(world,"volatile","java.util.Collection"))if(seen.add(entity))result.add(entity);
        for(Object entity:(List<?>)Native.call(world,"const","java.util.List"))if(seen.add(entity))result.add(entity);
        return result;
    }
    void forcePose(Object entity,NativePackets.World replay)throws Exception {
        if(Native.type("200241649").isInstance(entity)){
            Object component=Native.call(Native.type("1478690128"),"new","1579323378",entity);if(component==null)return;
            Object controller=Native.call(component,"static","1805188833");if(!(boolean)Native.call(controller,"private","boolean"))return;
            if((boolean)Native.call(component,"szFp_assert","boolean",true,null))forcedPoses++;
        }else if(Native.type("1848416318").isInstance(entity)){
            Object rig=Native.field(entity,"native");if(rig!=null&&Native.type("859153379").isInstance(rig))replay.actorTimes.withGraphUpdates(()->{
                if((boolean)Native.call(rig,"package","boolean",true,null))forcedPoses++;
                Object controller=Native.call(rig,"short","1805188833");
                if((boolean)Native.call(controller,"private","boolean"))Native.call(Native.call(controller,"super","387875964"),"do","void");
            });
        }
    }
    /** Index one object's creation recipe; selecting time never has to replay the event prefix. */
    void spawned(DemoFile.Event event,Object packet,Object nativeWorld)throws Exception {
        if(finished||!Native.type("1570813014").isInstance(packet))return;
        var field=Native.type("1570813014").getDeclaredField("do");field.setAccessible(true);
        int id=((Number)Native.call(Native.type("1570813014"),"final","int",field.getInt(packet))).intValue();
        Object entity=Native.call(nativeWorld,"continue","1217489680",id);
        if(entity==null)for(Object pending:(Set<?>)Native.call(nativeWorld,"throws","java.util.Set"))if(((Number)Native.call(pending,"for","int")).intValue()==id){entity=pending;break;}
        if(entity==null)return;
        spawnTemplates.put(entity,SceneSpawnRecipe.encode(event));spawnSamples++;
    }
    void attributes(Object entity,SceneTrackBuilder.ObjectRow row,long time)throws Exception {
        Object attributes=Native.call(entity,"float","1536302723");
        // The network writer filters properties by recipient. Presentation needs
        // every registered property whose canonical value codec supports export.
        for(Object attribute:(Iterable<?>)Native.call(Native.field(attributes,"this"),"while","1018845839")){
            int id=((Number)Native.field(attribute,"throw")).intValue();String codec=attribute.getClass().getName()+":"+id;if(unsupportedAttributes.contains(codec))continue;
            SceneAttributeCodec.Encoded encoded;
            try{
                encoded=SceneAttributeCodec.encode(attribute);
                if(!encoded.codec().equals("native-v1"))row.put("attribute-codec/"+id,SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,new SceneTracks.Bytes(encoded.codec().getBytes(java.nio.charset.StandardCharsets.US_ASCII)),false);
            }
            catch(Exception failure){unsupportedAttributes.add(codec);unsupportedTypes.put(codec,((Class<?>)Native.field(attribute,"return")).getName());continue;}
            row.put("attribute/"+id,SceneTracks.Domain.APPEARANCE,SceneTracks.Blend.HOLD,time,new SceneTracks.Bytes(encoded.bytes()),false);attributeSamples++;
        }
    }
    record RigPose(Object pose,Object skeleton){}
    static RigPose characterPose(Object entity)throws Exception {
        if(Native.type("200241649").isInstance(entity)){
            Object component=Native.call(Native.type("1478690128"),"new","1579323378",entity);
            if(component==null)return null;Object controller=Native.call(component,"static","1805188833");
            if(!(boolean)Native.call(controller,"private","boolean"))return null;
            Object graph=Native.call(controller,"super","387875964"),pose=Native.call(graph,"final","1118769848");
            return pose==null?null:new RigPose(pose,Native.call(graph,"strictfp","1612003613"));
        }
        if(Native.type("1848416318").isInstance(entity)){
            Object rig=Native.field(entity,"native");if(rig!=null&&Native.type("859153379").isInstance(rig)){
                Object controller=Native.call(rig,"short","1805188833");if(!(boolean)Native.call(controller,"private","boolean"))return null;
                Object pose=Native.call(rig,"boolean","1118769848"),graph=Native.call(Native.call(rig,"short","1805188833"),"super","387875964");
                return pose==null?null:new RigPose(pose,Native.call(graph,"strictfp","1612003613"));
            }
        }
        if(SceneRemains.is(entity)){
            Object renderer=SceneRemains.renderer(entity);
            if(renderer!=null){
                SceneTracks.Bones displayed=SceneRemains.displayed(renderer);Object pose=Native.type("1118769848").getConstructor(int.class,int.class).newInstance(displayed.boneCount(),displayed.curveCount());ScenePoseCodec.write(pose,displayed);Object model=Native.call(renderer,"do","111523829");
                if(model==null||pose==null)return null;Object skeleton=Native.call(model,"strictfp","1612003613");
                return skeleton==null?null:new RigPose(pose,skeleton);
            }
        }
        return null;
    }
    String skeletonId(Object skeleton)throws Exception {
        String id=skeletonIds.get(skeleton);if(id!=null)return id;
        SceneTracks.Bytes definition=skeletonDefinition(skeleton);id=NativeScenePresenter.sha(definition);skeletonIds.put(skeleton,id);skeletonDefinitions.putIfAbsent(id,definition);return id;
    }
    static SceneTracks.Bytes skeletonDefinition(Object skeleton)throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);Object[] nodes=(Object[])Native.field(skeleton,"case");out.writeInt(nodes.length);
        for(Object node:nodes){out.writeUTF((String)Native.field(node,"this"));out.writeInt(((Number)Native.field(node,"const")).intValue());}
        return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static SceneTracks.Bones bones(Object pose)throws Exception {
        return ScenePoseCodec.read(pose);
    }
    Map<String,Object> diagnostic(){Map<String,Object> result=new TreeMap<>();result.put("finished",finished);result.put("aborted",aborted);result.put("archive_written",written);result.put("failure",failureReason);result.put("output",output.toString());result.put("sample_rate",60);result.put("samples",samples);result.put("native_poses",poses);result.put("forced_character_pose_evaluations",forcedPoses);result.put("character_graph_loading_waits",graphWaitChecks);result.put("native_transforms",transforms);result.put("native_attribute_samples",attributeSamples);result.put("spawn_recipes",spawnSamples);result.put("unsupported_attribute_codecs",List.copyOf(unsupportedAttributes));result.put("unsupported_attribute_value_types",Map.copyOf(unsupportedTypes));result.put("objects",builder.rows.size());result.put("maximum_sample_gap_ns",maximumGap);result.put("full_scene_ready",false);if(mapCapture!=null)result.put("map",mapCapture.diagnostic());return result;}
}


