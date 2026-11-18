package replay;
import java.util.*;

/** Native FP assets on an unregistered actor; poses are selected directly by replay time. */
final class NativeReplayFp implements AutoCloseable {
    static final String[] ROLES={"instanceof","interface","for","szFp_final","szFp_synchronized","while","byte"};
    final NativeScenePlayback playback;
    Object source,entity,component,clock;SceneTracks.ObjectId identity;SceneTracks.ObjectTrack actor;
    boolean initialized,baked;int preparationStep;double preparationEnd,timeBase;
    SceneTracks.Track positionTrack;Map<String,SceneTracks.Value> attributes;SceneTracks.Value held;
    SceneTracks.Value requested;boolean requestedSet,pendingDraw;
    long time=Long.MIN_VALUE,bakeTime,bakeOrigin,bakeStep,cueTime;String status="";
    SceneFpActions actions;boolean aiming;float aim;
    SceneFpMovement movement;SceneTracks.Numbers motionValue;
    SceneFpPoses poses=new SceneFpPoses();
    java.nio.file.Path cache;String recording,client;
    java.util.concurrent.CompletableFuture<SceneFpPoses> cached;
    final Map<String,Object> buffers=new HashMap<>();
    long publishedTime=Long.MIN_VALUE;
    final Map<Object,Object> published=new IdentityHashMap<>();
    static final class Slot {Object engine,weapon;boolean initialized;int step;double end;}
    final Map<SceneTracks.Value,Slot> slots=new HashMap<>();
    NativeReplayFp(NativeScenePlayback playback){this.playback=playback;}
    Object select(Object next)throws Exception {
        var frame=playback.selectedFrame;if(next==null||frame==null){if(catalogued)clearPublished();else close();return null;}
        SceneTracks.ObjectState selected=null;
        for(var entry:playback.actors.pool.selected.entrySet())if(entry.getValue().entity==next){selected=frame.objects().get(entry.getKey());break;}
        if(selected==null){if(catalogued)clearPublished();else close();return null;}
        if(playback.fpCatalog!=null&&playback.fpCatalog.ready){
            NativeReplayFp prepared=playback.fpCatalog.contexts.get(selected.id());
            if(prepared==null){status="FP этого персонажа не поддерживается";clearPublished();return null;}
            if(prepared!=this){clearPublished();playback.firstPerson=prepared;return prepared.select(next);}
        }
        initialize(next,selected,frame.timeNs());source=next;
        if(baked&&initialized&&publishedTime==frame.timeNs())return entity;
        clearPublished();
        if(!prepareIndex(System.nanoTime()+2_000_000L))return null;
        update(selected,frame.timeNs());double now=timeBase+frame.timeNs()/1e9;
        if(ReplayRender.sceneFpItems.get(entity)==null){clock(now,0);status="FP готов";return entity;}
        ReplayRender.fpTime.set(now);final boolean[] ready={false};
        final SceneTracks.ObjectState fpState=selected;
        try{playback.world.actorTimes.withGraphUpdates(()->{Object context=ready(now);if(context!=null){aim(context,fpState,0);publish(context,frame.timeNs());ready[0]=true;}});}finally{ReplayRender.fpTime.remove();}
        status=ready[0]?"FP готов":"Загрузка FP оружия";return ready[0]?entity:null;
    }
    boolean catalogued;
    java.util.concurrent.CompletableFuture<Void> saved;
    SceneActorAssets renderAssets=new SceneActorAssets();
    void initialize(Object next,SceneTracks.ObjectState selected,long ns)throws Exception {
        if(!selected.id().equals(identity)){
            close();var channels=new HashMap<>(selected.channels());channels.put("recorder-template",SceneRecorderTemplate.capture(next));
            entity=SceneRecorderTemplate.create(playback.world.game,playback.actors.world,new SceneTracks.ObjectState(selected.id(),"961798934",channels),playback.fpRuntimeId());
            source=next;identity=selected.id();component=Native.call(Native.type("1638940636"),"public","1638940636",entity);
            clock=Class.forName("replay.ReplayFpClock").getConstructor().newInstance();
            Object engine=Native.type("sz.animation.fp.N1706111695").getConstructor(Native.type("200241649"),Native.type("1591066367")).newInstance(entity,clock);
            Native.reflectedField(component,"szFp_private").set(component,engine);
            actor=playback.tracks.objects.stream().filter(o->o.id().equals(identity)).findFirst().orElseThrow();positionTrack=actor.channels().get("position");
            ReplayRender.sceneEquipmentStyles.put(entity,Native.call(Native.type("916325480"),"double","916325480",next));
            actions=new SceneFpActions(playback.world.packets.demo);bakeTime=SceneFpCatalog.first(actor);bakeOrigin=bakeTime;cueTime=bakeTime-1;
            timeBase=((Number)Native.call(Native.field(Native.type("1402131154"),"h"),"do","double")).doubleValue()-ns/1e9;
            var demo=playback.world.packets.demo;recording=demo.manifest.get("event_stream_sha256").getAsString();client=demo.compatibilityFingerprint+"/native-fp-poses-6";
            movement=new SceneFpMovement(playback,actor,selected);
            cache=demo.path.resolveSibling(demo.path.getFileName()+".fp-"+identity.session()+"-"+identity.networkId()+"-"+identity.generation()+".gz");
            if(java.nio.file.Files.isRegularFile(cache)){
                var path=cache;var rec=recording;var version=client;var id=identity;
                cached=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return SceneFpPoses.read(path,rec,version,id);}catch(java.io.IOException ignored){return null;}});
            }
        }
    }
    boolean prepareIndex(long deadline)throws Exception {
        if(cached!=null){if(!cached.isDone()){status="Загрузка FP трека";return false;}var loaded=cached.join();cached=null;if(loaded!=null){poses=loaded;baked=true;}}
        if(!baked){
            if(!movement.prepare(deadline)){status="Подготовка состояний движения FP";return false;}
            do{
                var state=playback.tracks.fpResources(actor,bakeTime);if(state==null)break;
                double now=timeBase+bakeTime/1e9;ReplayRender.fpTime.set(now);
                try{
                    update(state,bakeTime);final boolean[] ready={false};
                    playback.world.actorTimes.withGraphUpdates(()->{
                        if(ReplayRender.sceneFpItems.get(entity)==null){poses.item(bakeTime,new SceneTracks.Bytes(new byte[]{1}));ready[0]=true;return;}
                        Object context=ready(now);if(context==null)return;
                        clock(now,1f/60);aim(context,state,1f/60);
                        for(var cue:actions.between(actor,cueTime,bakeTime)){clock(timeBase+cue.timeNs()/1e9,0);ReplayRender.fpTime.set(timeBase+cue.timeNs()/1e9);cue(context,cue);}
                        ReplayRender.fpTime.set(now);clock(now,1f/60);prepare(context);capture(context,bakeTime);ready[0]=true;
                    });
                    if(!ready[0])break;
                }finally{ReplayRender.fpTime.remove();}
                cueTime=bakeTime;long end=Math.min(playback.tracks.durationNs,actor.deadNs()<0?Long.MAX_VALUE:actor.deadNs()-1);
                if(bakeTime==end){finish();break;}
                bakeTime=Math.min(end,bakeOrigin+Math.round(++bakeStep*1e9/60d));
            }while(System.nanoTime()<deadline);
            status="Подготовка FP: "+(int)(100d*(bakeTime-actor.bornNs())/Math.max(1,playback.tracks.durationNs-actor.bornNs()))+"%";
            if(!baked)return false;
        }
        return baked;
    }
    boolean saved()throws Exception {if(saved==null)return true;if(!saved.isDone())return false;try{saved.join();return true;}catch(java.util.concurrent.CompletionException e){throw new java.io.IOException("FP track could not be saved",e.getCause());}}
    boolean prepareVariant(SceneTracks.ObjectState state,long ns,long deadline)throws Exception {
        update(state,ns);double now=timeBase+ns/1e9;ReplayRender.fpTime.set(now);
        try {final boolean[] ready={false};playback.world.actorTimes.withGraphUpdates(()->{ready[0]=held instanceof SceneTracks.Bytes b&&b.size()==1&&b.data()[0]!=0||ready(now)!=null;});if(!ready[0])return false;
            renderAssets.inspected.remove(entity);var actor=new NativeSceneSelection.Actor(entity,false);actor.inputs=SceneSecondaryRigs.Inputs.decode(state);renderAssets.actor(actor);
            renderAssets.fpSleeves(entity);
            boolean complete=renderAssets.ready(deadline,System::nanoTime);if(complete&&held!=null){var slot=slots.get(held);slot.initialized=initialized;slot.step=preparationStep;slot.end=preparationEnd;}return complete;
        } finally {ReplayRender.fpTime.remove();}
    }
    void update(SceneTracks.ObjectState state,long ns)throws Exception {
        if(time==ns&&attributes!=null)return;
        if(attributes!=state.heldAttributes()){
            var changes=new HashMap<String,SceneTracks.Value>();
            for(var entry:state.channels().entrySet())if(entry.getKey().startsWith("attribute/")&&(attributes==null||!Objects.equals(attributes.get(entry.getKey()),entry.getValue())||!Objects.equals(attributes.get("attribute-codec/"+entry.getKey().substring(10)),state.channels().get("attribute-codec/"+entry.getKey().substring(10))))){
                changes.put(entry.getKey(),entry.getValue());String codec="attribute-codec/"+entry.getKey().substring(10);if(state.channels().containsKey(codec))changes.put(codec,state.channels().get(codec));
            }
            if(!changes.isEmpty())NativeSceneSelection.initializeAttributes(entity,new SceneTracks.ObjectState(state.id(),state.type(),changes));
            attributes=state.heldAttributes();
        }
        double[] p=SceneEnvironmentCodec.numbers(state.channels().get("position"),3),look=SceneEnvironmentCodec.numbers(state.channels().get("look"),2);
        Native.call(entity,"throw","void",p[0],p[1],p[2]);Native.call(entity,"class","void",(float)look[0],(float)look[1]);
        double[] previous=p;var value=positionTrack==null?null:positionTrack.sample(Math.max(actor.bornNs(),ns-50_000_000L));
        if(value!=null){double[] v=SceneEnvironmentCodec.numbers(value,3);double distance=0;for(int n=0;n<3;n++)distance+=Math.pow(v[n]-p[n],2);if(distance<4)previous=v;}
        Object movement=Native.call(Native.type("1704556748"),"return","1704556748",entity),motion=Native.call(movement,"if","1943200621");
        motionValue=baked?poses.motion(ns):this.movement.sample(ns);SceneFpMovement.apply(motion,motionValue);
        LocalMotion.animationState(motion,p,previous,.05f,(float)look[0],(float)look[1]);SourceAdapters.put(motion,"return",timeBase+ns/1e9);Native.call(movement,"new","void");
        var raw=state.channels().get("controller-held-item");
        boolean empty=raw==null||raw instanceof SceneTracks.Bytes bytes&&bytes.data().length==1&&bytes.data()[0]!=0;
        if(!baked&&requestedSet&&!Objects.equals(requested,raw)&&empty&&initialized){
            Object context=Native.call(component,"volatile","1382318679");if(context!=null){clock(timeBase+ns/1e9,0);Native.call(context,"return","void",Native.field(Native.type("sz.animation.fp.N706709806"),"szFp_const"),entity);}
        }
        requested=raw;requestedSet=true;
        // A TP null marks holstering. Keep its native rig until the replacement arrives.
        // During direct presentation select the item that owns the indexed FP pose.
        var item=baked?poses.item(ns):empty&&held!=null?held:raw;
        if(!Objects.equals(held,item)){
            Slot previousSlot=slots.get(held);if(previousSlot!=null){previousSlot.initialized=initialized;previousSlot.step=preparationStep;previousSlot.end=preparationEnd;}
            held=item;Slot slot=slots.get(item);
            if(slot==null){if(slots.size()>=32)throw new IllegalStateException("FP resource variants exceed 32");slot=new Slot();slot.weapon=item instanceof SceneTracks.Bytes bytes?SceneControllerItem.decode(bytes):null;slot.engine=Native.type("sz.animation.fp.N1706111695").getConstructor(Native.type("200241649"),Native.type("1591066367")).newInstance(entity,clock);slots.put(item,slot);}
            Object weapon=slot.weapon;ReplayRender.sceneFpItems.put(entity,weapon);Native.reflectedField(component,"szFp_private").set(component,slot.engine);
            System.out.println("[Replay FP] "+identity+" item at "+ns+": "+weapon+" indexed="+baked);
            Object owner=Native.call(Native.type("1810277368"),"static","1810277368",entity);
            for(String field:List.of("protected","c"))Native.type("1810277368").getDeclaredField(field).set(owner,weapon);
            if(weapon!=null){Object definition=Native.call(weapon,"interface","1366800141");if(Native.type("1745367406").isInstance(definition))Native.type("1810277368").getDeclaredField("float").setFloat(owner,((Number)Native.call(definition,"void","float",weapon)).floatValue());}
            initialized=slot.initialized;preparationStep=slot.step;preparationEnd=slot.end;
            pendingDraw=!baked&&weapon!=null;
        }
        time=ns;
    }
    Object ready(double now)throws Exception {
        if(initialized){clock(now,0);Object context=Native.call(component,"volatile","1382318679");if(context!=null){draw(context);return context;}initialized=false;preparationStep=0;}
        clock(now,0);
        Object engine=Native.field(component,"szFp_private");Native.reflectedField(engine,"szFp_void").setLong(engine,Long.MIN_VALUE);
        Native.call(component,"szFp_throw","void");Native.call(component,"szFp_double","void");Object context=Native.call(component,"volatile","1382318679");
        if(context==null||!(boolean)Native.call(context,"szFp_enum","boolean"))return null;
        initialized=true;draw(context);return context;
    }
    void draw(Object context)throws Exception {
        if(pendingDraw){
            // Cancel holstering; the native graph supplies its own draw transition.
            Native.call(context,"return","void",Native.field(Native.type("sz.animation.fp.N643627978"),"szFp_int"),entity);
            pendingDraw=false;
        }
    }
    void aim(Object context,SceneTracks.ObjectState state,float dt)throws Exception {
        boolean next=false;var v=state.channels().get("attribute/201");if(v instanceof SceneTracks.Bytes bytes){String codec="native-v1";var c=state.channels().get("attribute-codec/201");if(c instanceof SceneTracks.Bytes b)codec=new String(b.data(),java.nio.charset.StandardCharsets.US_ASCII);next=Boolean.TRUE.equals(SceneAttributeCodec.decode(Boolean.class,codec,bytes.data()));}
        Object owner=Native.call(Native.type("1810277368"),"static","1810277368",entity);
        if(next!=aiming){aiming=next;Native.call(context,"return","void",Native.type("1412480992").getConstructor(boolean.class).newInstance(next),entity);}
        float duration=((Number)Native.type("1810277368").getDeclaredField("float").get(owner)).floatValue();aim=baked?poses.aim(time):Math.max(0,Math.min(1,aim+(aiming?1:-1)*dt/Math.max(.001f,duration)));
        SceneFpAim.apply(context,owner,aim);
    }
    void cue(Object context,SceneFpActions.Cue cue)throws Exception {
        Object event;if(cue.kind()==SceneFpActions.Kind.SHOT)event=Native.field(Native.type("1642176723"),"this");
        else {Object packet=playback.world.packets.decode(cue.event()),item=Native.field(packet,"case");int count=item==null?0:((Number)Native.call(item,"import","int")).intValue();event=Native.type("318048108").getConstructor(float.class,int.class,boolean.class).newInstance(((Number)Native.field(packet,"class")).floatValue(),count,(boolean)Native.field(packet,"if"));}
        Native.call(context,"return","void",event,entity);
    }
    void capture(Object context,long ns)throws Exception {
        poses.item(ns,(SceneTracks.Bytes)held);
        poses.aim(ns,aim);
        poses.motion(ns,motionValue);
        Object data=Native.call(Native.field(context,"double"),"byte","java.lang.Object");
        for(String role:ROLES){Object graph=Native.call(data,role,"387875964");if(graph!=null)poses.add(role,ns,ScenePoseCodec.read(Native.call(graph,"extends","1118769848")));}
    }
    void finish(){
        poses.finish();baked=true;var completed=poses;var path=cache;var rec=recording;var version=client;var id=identity;
        saved=java.util.concurrent.CompletableFuture.runAsync(()->{try{completed.write(path,rec,version,id);}catch(java.io.IOException failure){throw new java.util.concurrent.CompletionException(failure);}});
    }
    void publish(Object context,long ns)throws Exception {
        Object owner=Native.call(Native.type("1810277368"),"static","1810277368",entity);SceneFpAim.apply(context,owner,poses.aim(ns));
        Object data=Native.call(Native.field(context,"double"),"byte","java.lang.Object");
        for(String role:poses.tracks.keySet()){Object graph=Native.call(data,role,"387875964");var bones=poses.sample(role,ns);if(graph==null||bones==null)continue;Object pose=buffers.get(role);if(pose==null||((Object[])Native.field(pose,"for")).length!=bones.boneCount()||((float[])Native.field(pose,"strictfp")).length!=bones.curveCount()){pose=ScenePoseCodec.create(bones);buffers.put(role,pose);}ScenePoseCodec.write(pose,bones);published.put(graph,pose);ReplayRender.sceneFpPoses.put(graph,pose);}
        publishedTime=ns;
    }
    void clearPublished(){for(Object graph:published.keySet())ReplayRender.sceneFpPoses.remove(graph);published.clear();publishedTime=Long.MIN_VALUE;}
    Float fov()throws Exception {
        if(entity==null||!initialized)return null;
        float base=((Number)Native.call(Native.field(Native.type("1546247182"),"a"),"class","float")).floatValue();Object item=ReplayRender.sceneFpItems.get(entity);
        if(item==null||!Native.type("1745367406").isInstance(Native.call(item,"interface","1366800141")))return base;
        Object owner=Native.call(Native.type("1810277368"),"static","1810277368",entity);
        if((boolean)Native.call(owner,"throw","boolean"))return base;
        return SceneFpAim.fov(base,((Number)Native.call(owner,"long","float",item)).floatValue(),aim);
    }
    AutoCloseable renderScope(Object requested)throws Exception {
        if(requested!=entity||entity==null||!initialized)return null;
        return ReplayRender.beginFpView(entity,Native.call(Native.type("1810277368"),"static","1810277368",entity),ReplayRender.sceneFpItems.get(entity));
    }
    void clock(double seconds,float delta)throws Exception {Native.reflectedField(clock,"seconds").setDouble(clock,seconds);Native.reflectedField(clock,"delta").setFloat(clock,delta);Native.reflectedField(clock,"serial").setLong(clock,Native.reflectedField(clock,"serial").getLong(clock)+1);}
    void prepare(Object context)throws Exception {
        Native.call(context,"szFp_class","void",entity);
        if(motionValue!=null&&motionValue.data()[4]>0){float scale=(float)motionValue.data()[4];Object parameters=Native.call(Native.field(context,"synchronized"),"abstract","230325512");Native.call(parameters,"szFp_boolean","void",Math.max(.5f,Math.min(1.5f,scale)));Native.call(parameters,"szFp_extends","void",scale);}
        Native.call(context,"protected","void");Native.call(context,"try","void");Native.call(context,"szFp_static","void",entity);
    }
    public void close()throws Exception {
        clearPublished();try{renderAssets.close();if(movement!=null)movement.close();for(var slot:slots.values())Native.call(slot.engine,"szFp_static","void");if(component!=null&&slots.isEmpty())Native.call(component,"szFp_private","void");}
        finally{if(entity!=null){ReplayRender.sceneFpItems.remove(entity);ReplayRender.sceneBodyViews.remove(entity);ReplayRender.sceneEquipmentStyles.remove(entity);}source=null;entity=null;component=null;clock=null;positionTrack=null;actor=null;initialized=baked=false;preparationStep=0;identity=null;attributes=null;held=null;requested=null;requestedSet=pendingDraw=false;time=Long.MIN_VALUE;poses=new SceneFpPoses();buffers.clear();slots.clear();cached=null;saved=null;actions=null;movement=null;motionValue=null;aim=0;aiming=false;renderAssets=new SceneActorAssets();}
    }
}
