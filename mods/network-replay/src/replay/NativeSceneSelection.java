package replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native actor-list publication for indexed scene time. Map, effects and sound
 * are separate domains; this component does not assert full-scene coverage. */
final class NativeSceneSelection implements AutoCloseable {
    static final class Actor {
        final Object entity;final boolean owned;
        final SceneResourceLease resources=new SceneResourceLease();
        final SceneSkeletonIdentity skeletonIdentity=new SceneSkeletonIdentity(8);
        NativeScenePresenter.Binding binding;
        SceneTracks.Value bindingLayout;
        SceneSecondaryRigs.Inputs inputs;
        SceneTracks.Value equipmentValue;Object equipmentProvider;
        boolean equipmentAlternateTried;
        Iterator<SceneTracks.Bytes> equipmentCandidates;
        long loadingSince;
        boolean modelsPrimed;
        Actor(Object entity,boolean owned){this.entity=entity;this.owned=owned;}
    }
    record Appearance(SceneTracks.ObjectId incarnation,List<SceneTracks.Value> rigs,SceneTracks.Value held,SceneTracks.Value hands,SceneTracks.Value recorder,SceneTracks.Value equipment){}
    final Object world,recorder;
    final Object recorderController,savedControllerItem,recorderHands,savedHandsItem;
    final NativeSceneEntityFactory factory;
    final NativeScenePresenter presenter=new NativeScenePresenter();
    final SceneObjectPool<Actor> pool;
    final SceneActorAssets assets=new SceneActorAssets();
    final double[] savedRecorder;
    final float savedYaw,savedPitch;

    final List<Object> originalEntities;
    final IdentityHashMap<Object,Integer> originalIds=new IdentityHashMap<>();
    long publications,pending;
    final boolean profilePhases=Boolean.getBoolean("sz.replay.qa.scene-native-phases");
    Map<String,Long> lastPhases=Map.of();
    String waitingActor="";
    void prefetch(SceneTracks.Frame frame,long deadline)throws Exception {
        long before=preparingTime;try{preparingTime=frame.timeNs();pool.prepareAhead(frame,deadline,2);}finally{preparingTime=before;}
    }
    SceneEquipmentVariants equipmentVariants;long verifiedEquipmentAlternates,preparingTime;
    boolean closed;Map<String,Object> finalDiagnostic;
    boolean catalogue(SceneActorCatalog.Prepared value)throws Exception {
        long before=preparingTime;try{preparingTime=value.recipe().time();if(!pool.catalogue(value.state()))return false;Actor actor=pool.objects.get(pool.resources.cacheKey(value.state()));assets.actor(actor);return true;}finally{preparingTime=before;}
    }
    NativeSceneSelection(NativePackets.World replay)throws Exception {
        world=Native.call(replay.connection,"instanceof","1054393445");recorder=replay.local.player;
        recorderController=SceneControllerItem.controller(recorder);savedControllerItem=recorderController==null?null:Native.call(recorderController,"throws","211097523");
        recorderHands=SceneSecondaryRigs.hands(recorder);savedHandsItem=recorderHands==null?null:Native.field(recorderHands,"try");
        originalEntities=SceneWorldMembership.snapshot((Collection<?>)Native.call(world,"volatile","java.util.Collection"),(Collection<?>)Native.call(world,"throws","java.util.Set"));for(Object entity:originalEntities)originalIds.put(entity,((Number)Native.call(entity,"for","int")).intValue());
        factory=new NativeSceneEntityFactory(replay.packets);

        Object p=Native.call(Native.type("576005245"),"char","153304200",recorder,1f);
        savedRecorder=new double[]{NativeSceneCapture.number(p,"case"),NativeSceneCapture.number(p,"void"),NativeSceneCapture.number(p,"import")};
        savedYaw=((Number)Native.call(recorder,"catch","float")).floatValue();savedPitch=((Number)Native.call(recorder,"volatile","float")).floatValue();
        pool=new SceneObjectPool<>(new SceneObjectPool.Resources<>(){
            public boolean prepareAhead(SceneTracks.ObjectState state)throws Exception {
                // A corpse constructor consumes its physical pose. Prepare it at
                // the selected time, never interpolate that pose merely to load resources.
                return !Native.type("544082630").isAssignableFrom(Native.type(state.type()));
            }
            public Object cacheKey(SceneTracks.ObjectState state){
                if(state.type().equals(recorder.getClass().getName())&&!state.channels().containsKey("recorder-template"))return state.id();
                List<SceneTracks.Value> rigs=new ArrayList<>();rigs.add(state.channels().get("rig-layout"));
                for(String role:SceneSecondaryRigs.ROLES)rigs.add(state.channels().get("attachment/"+role+"/rig-layout"));
                return new Appearance(state.id(),Collections.unmodifiableList(rigs),state.channels().get("controller-held-item"),state.channels().get("hands-held-item"),state.channels().get("recorder-template"),state.channels().get("equipment-held-item"));
            }
            public Actor create(SceneTracks.ObjectState state,int id)throws Exception {
                boolean local=state.type().equals(recorder.getClass().getName());
                if(local&&!state.channels().containsKey("recorder-template")&&state.id().networkId()==((Number)Native.call(recorder,"for","int")).intValue())return new Actor(recorder,false);
                Object entity;try{entity=local?SceneRecorderTemplate.create(replay.game,world,state,id):factory.create(world,state,id);}catch(Exception failure){throw new IOException("Cannot construct scene incarnation "+state.id()+" type="+state.type(),failure);}Actor actor=new Actor(entity,true);
                try{initializeAttributes(entity,state);initializeRemains(entity,state);return actor;}catch(Exception failure){try{dispose(actor);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
            }
            public boolean ready(Actor actor,SceneTracks.ObjectState state)throws Exception {
                if(SceneRemains.is(actor.entity)&&state.channels().containsKey("rig-layout")){
                    Object renderer=Native.field(actor.entity,"long");if(renderer==null||!renderer.getClass().getName().equals("1326794892"))initializeRemains(actor.entity,state);
                }
                // A newly born actor can precede its first recorded rig key.
                // Its native attachment providers still require an initialized context.
                if((state.channels().containsKey("rig-layout")||Native.type("200241649").isInstance(actor.entity)||Native.type("1848416318").isInstance(actor.entity))&&!graphReady(actor))return false;
                if(actor.inputs==null||!actor.inputs.matches(state)){actor.inputs=SceneSecondaryRigs.Inputs.decode(state);actor.modelsPrimed=false;}
                if(state.channels().get("equipment-held-item") instanceof SceneTracks.Bytes item&&!item.equals(actor.equipmentValue)){
                    actor.equipmentProvider=SceneSecondaryRigs.equipment(actor.entity);
                    equipment(actor,item);
                    actor.equipmentValue=item;actor.equipmentAlternateTried=false;actor.equipmentCandidates=null;
                }
                if(!actor.modelsPrimed)replay.actorTimes.withGraphUpdates(()->SceneSecondaryRigs.prepare(actor.entity,actor.inputs));
                actor.resources.retainModels(actor.entity);
                SceneTracks.Value layout=state.channels().get("rig-layout");
                if(actor.binding!=null&&Objects.equals(actor.bindingLayout,layout)&&actor.binding.graph()==NativeScenePresenter.graphIdentity(actor.entity)&&SceneSecondaryRigs.matches(actor.binding.secondary(),SceneSecondaryRigs.graphs(actor.entity,actor.binding.graph()))&&SceneSecondaryRigs.ready(state,actor.binding.secondary(),actor.skeletonIdentity)){actor.modelsPrimed=true;actor.loadingSince=0;return true;}
                actor.binding=NativeScenePresenter.bindExisting(actor.entity);if(actor.binding!=null){actor.bindingLayout=layout;if(!SceneSecondaryRigs.ready(state,actor.binding.secondary(),actor.skeletonIdentity)){
                    String reason=SceneSecondaryRigs.waiting(state,actor.binding.secondary());
                    if(reason.startsWith("skeleton equipment ")&&equipmentVariants!=null&&actor.equipmentProvider!=null
                            &&(boolean)Native.field(actor.equipmentProvider,"class")){
                        if(actor.equipmentCandidates==null)actor.equipmentCandidates=equipmentVariants.candidates(state,actor.equipmentValue,preparingTime).iterator();
                        var alternate=actor.equipmentCandidates.hasNext()?actor.equipmentCandidates.next():null;actor.equipmentAlternateTried=true;
                        if(alternate!=null){equipment(actor,alternate);actor.binding=null;actor.loadingSince=0;}
                    }
                    actor.modelsPrimed=false;waitingActor="attachments "+state.id()+" available="+(actor.binding==null?"loading recorded alternate":actor.binding.secondary().keySet())+" "+reason;return waitFor(actor);
                }if(actor.equipmentAlternateTried&&!actor.modelsPrimed)verifiedEquipmentAlternates++;actor.modelsPrimed=true;actor.loadingSince=0;return true;}return waitFor(actor);
            }
            public void dispose(Actor actor)throws Exception {
                try{if(actor.owned)Native.call(world,"default","void",List.of(actor.entity),new HashSet<>());}finally{try{actor.resources.close();}finally{ReplayRender.sceneBodyViews.remove(actor.entity);ReplayRender.sceneEquipmentStyles.remove(actor.entity);if(actor.equipmentProvider!=null)ReplayRender.sceneEquipmentItems.remove(actor.equipmentProvider);}}
            }
        });
    }
    static void equipment(Actor actor,SceneTracks.Bytes item)throws Exception {
        Object equipped=SceneControllerItem.decode(item);ReplayRender.sceneEquipmentItems.put(actor.equipmentProvider,equipped);
        if(Native.type("200241649").isInstance(actor.entity)){
            if(equipped!=null&&Native.type("1598274006").isInstance(Native.call(equipped,"interface","1366800141")))ReplayRender.sceneEquipmentStyles.put(actor.entity,Native.call(Native.type("916325480"),"abstract","916325480",equipped));
            else ReplayRender.sceneEquipmentStyles.remove(actor.entity);
        }
        actor.modelsPrimed=false;
    }
    static void initializeRemains(Object entity,SceneTracks.ObjectState state)throws Exception {
        if(!SceneRemains.is(entity))return;
        if(state.channels().get("body-hidden") instanceof SceneTracks.Numbers hidden)SourceAdapters.put(entity,"abstract",hidden.data()[0]!=0);
        if(!(state.channels().get("rig-layout") instanceof SceneTracks.Bytes layout))return;
        String key=new String(layout.data(),StandardCharsets.UTF_8);SceneTracks.Bones bones=(SceneTracks.Bones)state.channels().get("bones/"+key);
        Object pose=Native.type("1118769848").getConstructor(int.class,int.class).newInstance(bones.positions().length/3,bones.curves().length);ScenePoseCodec.write(pose,bones);
        // Native constructor scales its input. Indexed bones already contain the
        // renderer's scaled, physical pose, so undo only the constructor's factor.
        float scale=((Number)Native.call(entity,"strictfp","float")).floatValue();if(!Float.isFinite(scale)||scale<=0)throw new IOException("Invalid recorded body scale");
        for(Object position:(Object[])Native.field(pose,"for"))Native.call(position,"void","1987590071",1f/scale);
        Object renderer=Native.type("1326794892").getConstructor(Native.type("544082630"),Native.type("1118769848")).newInstance(entity,pose);
        SourceAdapters.put(entity,"long",renderer);
        Object mode=SceneRemains.selectedMode(entity,state);if(mode!=null)SourceAdapters.put(entity,"import",mode);
    }
    static void initializeAttributes(Object entity,SceneTracks.ObjectState state)throws Exception {
        Object container=Native.call(entity,"float","1536302723");Map<Integer,Object> attributes=new TreeMap<>();
        for(Object a:(Iterable<?>)Native.call(Native.field(container,"this"),"while","1018845839"))attributes.put(((Number)Native.field(a,"throw")).intValue(),a);
        // Initialize all selected fields before notifying native model providers.
        // This operates only on a quarantined object, never a visible live actor.
        Map<Object,Object> previous=new IdentityHashMap<>();
        for(var entry:state.channels().entrySet())if(entry.getKey().startsWith("attribute/")){
            int id=Integer.parseInt(entry.getKey().substring(10));Object a=attributes.get(id);if(a==null)throw new IOException("Scene creation attribute missing: "+id);
            String codec="native-v1";if(state.channels().get("attribute-codec/"+id) instanceof SceneTracks.Bytes b)codec=new String(b.data(),StandardCharsets.US_ASCII);
            Object value;try{value=SceneAttributeCodec.decode((Class<?>)Native.field(a,"return"),codec,((SceneTracks.Bytes)entry.getValue()).data());}catch(Exception failure){throw new IOException("Cannot restore scene attribute "+id+" type="+Native.field(a,"return")+" codec="+codec+" incarnation="+state.id(),failure);}
            previous.put(a,Native.field(a,"do"));Native.call(a,"assert","void",value);
        }
        for(var entry:previous.entrySet())Native.call(container,"instanceof","void",entry.getKey(),entry.getValue());
    }
    boolean graphReady(Actor actor)throws Exception {
        Object component=null,controller=null;
        if(Native.type("200241649").isInstance(actor.entity)){
            component=Native.call(Native.type("1478690128"),"new","1579323378",actor.entity);if(component==null)return waitFor(actor);
            controller=Native.call(component,"static","1805188833");
            if(Native.field(controller,"short")==null)Native.call(component,"break","void");
        }else if(Native.type("1848416318").isInstance(actor.entity)){
            Object rig=Native.field(actor.entity,"native");if(rig==null)return waitFor(actor);controller=Native.call(rig,"short","1805188833");
            if(Native.field(controller,"short")==null)Native.call(rig,"enum","void");
        }
        if(controller==null)return true;
        if((boolean)Native.field(controller,"throws"))throw new IOException("Indexed scene rig failed");
        if(!(boolean)Native.call(controller,"private","boolean")){
            Object loader=Native.field(controller,"default");Object task=loader==null?null:Native.field(loader,"boolean");
            if(task instanceof java.util.concurrent.CompletableFuture<?> future&&future.isDone()){
                // ReplayMod services immediate completions once per render frame.
                // Do not drain scheduled world events or multiply that budget per actor.
                future.join();
            }
        }
        if(!(boolean)Native.call(controller,"private","boolean")||Native.field(controller,"short")==null)return waitFor(actor);
        return true;
    }
    boolean waitFor(Actor actor)throws IOException {
        if(actor.loadingSince==0)actor.loadingSince=System.nanoTime();
        if(System.nanoTime()-actor.loadingSince>30_000_000_000L)throw new IOException("Indexed scene actor resources timed out: "+actor.entity.getClass().getName()+" runtime="+NativeId.of(actor.entity)+" "+waitingActor);return false;
    }
    static class NativeId {static Object of(Object entity){try{return Native.call(entity,"for","int");}catch(Exception e){return "unavailable";}}}
    interface Composer {void prepare(SceneTracks.Frame selected,NativeScenePresenter.Frame candidate)throws Exception;}
    SceneTracks.Frame preparingFrame;
    Map<SceneTracks.ObjectId,Actor> preparingActors;
    NativeScenePresenter.Frame preparingValues;
    void cancelPreparation(){preparingFrame=null;preparingActors=null;preparingValues=null;pool.cancelPreparation();}
    boolean present(SceneTracks.Frame selected)throws Exception {return present(selected,(s,c)->{});}
    boolean present(SceneTracks.Frame selected,Composer composer)throws Exception {
        if(closed)throw new IllegalStateException("Closed native scene selection");
        if(preparingFrame!=selected){cancelPreparation();preparingFrame=selected;}
        waitingActor="";preparingTime=selected.timeNs();
        long stage=profilePhases?System.nanoTime():0;
        ReplayPerformance.enter("scene-resources");Map<SceneTracks.ObjectId,Actor> actors;try{actors=preparingActors;if(actors==null)preparingActors=actors=pool.prepare(selected);}finally{ReplayPerformance.exit("scene-resources");}if(actors==null){pending++;return false;}
        long resources=profilePhases?System.nanoTime()-stage:0;if(profilePhases)stage=System.nanoTime();
        final Map<SceneTracks.ObjectId,Actor> bindings=actors;
        ReplayPerformance.enter("scene-poses");NativeScenePresenter.Frame candidate;try{candidate=preparingValues;if(candidate==null)preparingValues=candidate=presenter.prepareValues(selected,state->bindings.get(state.id()).binding);}finally{ReplayPerformance.exit("scene-poses");}if(candidate==null){waitingActor=presenter.waitingReason;pending++;return false;}
        long poses=profilePhases?System.nanoTime()-stage:0;if(profilePhases)stage=System.nanoTime();
        // Map and other domains must be composed before any visible publication.
        composer.prepare(selected,candidate);
        long map=profilePhases?System.nanoTime()-stage:0;if(profilePhases)stage=System.nanoTime();
        Map<Integer,Object> ids=new HashMap<>();List<Object> visible=new ArrayList<>();
        for(var entry:actors.entrySet()){
            Object entity=entry.getValue().entity;
            candidate.sourceIds.put(entity,entry.getKey().networkId());
            if(ids.put(entry.getKey().networkId(),entity)!=null)throw new IOException("Two alive incarnations reuse one network ID");
            int runtime=((Number)Native.call(entity,"for","int")).intValue();if(runtime!=entry.getKey().networkId()&&ids.put(runtime,entity)!=null)throw new IOException("Duplicate scene runtime ID");visible.add(entity);
        }
        // Validate looks before touching culling bounds; the immutable rig frame is
        // published only once the entire collection and spatial state are ready.
        Map<Object,double[]> looks=new IdentityHashMap<>();Map<Object,Object> bodyModes=new IdentityHashMap<>();
        for(var state:selected.presentationObjects()){
            if(state.type().equals("world-environment"))continue;
            if(state.channels().containsKey("look"))looks.put(actors.get(state.id()).entity,SceneEnvironmentCodec.numbers(state.channels().get("look"),2));
            Object entity=actors.get(state.id()).entity,mode=SceneRemains.selectedMode(entity,state);if(mode!=null)bodyModes.put(entity,mode);
        }
        Map<Object,double[]> restorePositions=new IdentityHashMap<>(),restoreLooks=new IdentityHashMap<>();Map<Object,Object> restoreModes=new IdentityHashMap<>();
        for(Object entity:bodyModes.keySet())restoreModes.put(entity,Native.field(entity,"import"));
        for(Object entity:candidate.positions.keySet())if(entity==recorder||presenter.current!=null&&presenter.current.positions.containsKey(entity)){
            Object p=Native.call(Native.type("576005245"),"char","153304200",entity,1f);restorePositions.put(entity,new double[]{NativeSceneCapture.number(p,"case"),NativeSceneCapture.number(p,"void"),NativeSceneCapture.number(p,"import")});
            if(looks.containsKey(entity))restoreLooks.put(entity,new double[]{((Number)Native.call(entity,"catch","float")).doubleValue(),((Number)Native.call(entity,"volatile","float")).doubleValue()});
        }

        try{
            for(var entry:bodyModes.entrySet())SourceAdapters.put(entry.getKey(),"import",entry.getValue());
            for(var state:selected.presentationObjects())if(state.channels().get("body-hidden") instanceof SceneTracks.Numbers hidden)SourceAdapters.put(actors.get(state.id()).entity,"abstract",hidden.data()[0]!=0);
            for(var e:candidate.positions.entrySet()){double[] p=e.getValue();Native.call(e.getKey(),"throw","void",p[0],p[1],p[2]);}
            for(var e:looks.entrySet()){double[] p=e.getValue();Native.call(e.getKey(),"class","void",(float)p[0],(float)p[1]);}
            // Indexed lighting reads candidate.day directly. Do not rewrite the
            // simulation's WorldInfo just to render an independently selected scene.
        }catch(Exception failure){
            for(var entry:restoreModes.entrySet())try{SourceAdapters.put(entry.getKey(),"import",entry.getValue());}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            for(var e:restorePositions.entrySet())try{double[] p=e.getValue();Native.call(e.getKey(),"throw","void",p[0],p[1],p[2]);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            for(var e:restoreLooks.entrySet())try{double[] p=e.getValue();Native.call(e.getKey(),"class","void",(float)p[0],(float)p[1]);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        candidate.world=world;candidate.entities=List.copyOf(visible);candidate.entityIds=Map.copyOf(ids);
        pool.publish(actors);presenter.publish(candidate);publications++;
        cancelPreparation();
        if(profilePhases)lastPhases=Map.of("resources",resources,"poses_and_attributes",poses,"map",map,"publication",System.nanoTime()-stage);
        return true;
    }
    Map<String,Object> diagnostic(){return finalDiagnostic!=null?finalDiagnostic:Map.of("publications",publications,"pending",pending,"prepared_incarnations",pool.objects.size(),"visible_actors",pool.selected.size(),"packet_prefix_replayed",false,"native_world_admission",false,"full_scene_ready",false,"waiting_actor",waitingActor);}
    public void close()throws Exception {
        if(closed)return;finalDiagnostic=diagnostic();closed=true;
        presenter.close();Exception failure=null;
        try{pool.close();}catch(Exception e){failure=e;}
        try{assets.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        try{Native.call(recorder,"throw","void",savedRecorder[0],savedRecorder[1],savedRecorder[2]);Native.call(recorder,"class","void",savedYaw,savedPitch);if(recorderController!=null)Native.call(recorderController,"throws","void",savedControllerItem);if(recorderHands!=null)Native.call(recorderHands,"final","void",NativeScenePresenter.graphIdentity(recorder),savedHandsItem);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        try{
            Collection<?> restored=(Collection<?>)Native.call(world,"volatile","java.util.Collection"),pending=(Collection<?>)Native.call(world,"throws","java.util.Set");
            if(!SceneWorldMembership.matches(originalEntities,restored,pending))throw new IOException("Indexed scene changed native world membership: expected="+originalEntities+" live="+restored+" pending="+pending);
            Set<Object> live=Collections.newSetFromMap(new IdentityHashMap<>());live.addAll(restored);
            for(var e:originalIds.entrySet())if(((Number)Native.call(e.getKey(),"for","int")).intValue()!=e.getValue()||live.contains(e.getKey())&&Native.call(world,"continue","1217489680",e.getValue())!=e.getKey())throw new IOException("Indexed scene changed original world identity");
            Map<String,Object> checked=new TreeMap<>(finalDiagnostic);checked.put("original_world_restored",true);finalDiagnostic=Map.copyOf(checked);
        }catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
}



