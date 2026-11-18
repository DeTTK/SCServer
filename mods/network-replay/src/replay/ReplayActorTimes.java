package replay;

import java.util.Map;
import java.util.WeakHashMap;

/** Native actor-relative scaled clocks follow their recorded world, not render timing. */
final class ReplayActorTimes implements ReplayRender.ActorTimes {
    record Birth(long epoch,double seconds){}
    static final class Clock {
        final long epoch;
        double previous;
        boolean analyticInitialized;
        double anchorSeconds,anchorValue,lastValue;
        float lastRate;
        Clock(long epoch,double seconds){this.epoch=epoch;previous=seconds;}
    }
    // Native actor equals/hashCode use a mutable network ID which is assigned
    // after construction. Birth records must follow the actual instance.
    static final class ActorRef extends java.lang.ref.WeakReference<Object> {
        final int hash;
        ActorRef(Object actor,java.lang.ref.ReferenceQueue<Object> queue){super(actor,queue);hash=System.identityHashCode(actor);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other||(other instanceof ActorRef ref&&get()!=null&&get()==ref.get());}
    }
    final java.lang.ref.ReferenceQueue<Object> actorsGone=new java.lang.ref.ReferenceQueue<>();
    final Map<ActorRef,Birth> births=new java.util.HashMap<>();
    final Map<Object,Clock> clocks=new WeakHashMap<>();
    Object world;
    ReplayAssets assets;
    boolean rendering;
    volatile boolean indexed;
    long samples,bindings;
    long preparedNpcPoses,invalidatedNpcParameters,settledNpcPoses;
    final java.util.Set<ActorRef> dirtyNpc=new java.util.HashSet<>();
    final Map<ActorRef,Long> graphWaits=new java.util.HashMap<>();
    final Map<ActorRef,java.lang.ref.WeakReference<Object>> admittedGraphs=new java.util.HashMap<>();
    final java.util.List<Map<String,Object>> graphAdmissions=new java.util.ArrayList<>();
    long graphReadyChecks,graphWaitChecks,graphQueuePumps;
    long scheduledGraphPasses,scheduledGraphUpdates;
    long lastGraphTick=Long.MIN_VALUE,lastGraphEpoch=-1;
    final ThreadLocal<Integer> graphPermit=ThreadLocal.withInitial(()->0);
    interface GraphAction {void run()throws Exception;}
    void withGraphUpdates(GraphAction action)throws Exception {
        int previous=graphPermit.get();graphPermit.set(previous+1);
        try{action.run();}finally{if(previous==0)graphPermit.remove();else graphPermit.set(previous);}
    }
    public boolean allowSceneGraphPreparation(){return graphPermit.get()>0;}
    public synchronized boolean allowGraphUpdate(Object object)throws Exception {
        if(graphPermit.get()>0)return true;
        // The visible native rig samples animation at render-frame time.
        // Restricting this path to logical 20-Hz ticks visibly stair-steps legs
        // and arms. Reconstruction retains the bounded logical schedule.
        if(rendering)return true;
        for(ActorRef ref:births.keySet()){
            Object actor=ref.get();if(actor==null||!owns(actor)||!Native.type("1848416318").isInstance(actor))continue;
            Object rig=Native.field(actor,"native");if(rig==object)return false;
            if(rig!=null&&Native.type("859153379").isInstance(rig)){
                Object controller=Native.call(rig,"short","1805188833");
                if(Native.field(controller,"short")==object)return false;
            }
        }
        return true;
    }
    /** A packet can change a provider without changing the native clock sample. */
    public synchronized void stateChanged(Object actor)throws Exception {
        if(!owns(actor)||!Native.type("1848416318").isInstance(actor))return;
        Object rig=Native.field(actor,"native");if(!Native.type("859153379").isInstance(rig))return;
        Object controller=Native.call(rig,"short","1805188833");
        // case is the native parameter-sample cache, not the animation phase.
        // NaN requests a fresh provider read at the same recorded time.
        SourceAdapters.put(controller,"case",Double.NaN);invalidatedNpcParameters++;
        dirtyNpc.add(new ActorRef(actor,actorsGone));
    }
    synchronized void settleNpcPoses()throws Exception {
        withGraphUpdates(()->{for(ActorRef ref:dirtyNpc){
            Object actor=ref.get();if(actor==null||!owns(actor))continue;
            int id=((Number)Native.call(actor,"for","int")).intValue();
            if(Native.call(world,"continue","1217489680",id)!=actor)continue;
            Object rig=Native.field(actor,"native"),controller=Native.call(rig,"short","1805188833");
            if((boolean)Native.field(controller,"throws")||!(boolean)Native.call(controller,"private","boolean"))continue;
            Native.call(actor,"do","910717201");Native.call(rig,"package","boolean",true,null);settledNpcPoses++;
        }});dirtyNpc.clear();
    }
    /** Native logical graph updates have recorded semantic times, independent of rendering. */
    synchronized void advanceNpcGraphs()throws Exception {
        if(!Boolean.getBoolean("sz.replay.qa.graphschedule")||world==null)return;
        // Native logical graphs run at 20 Hz. Readiness/event callbacks can
        // revisit one tick at many packet timestamps; they are not extra ticks.
        long tick=(long)Math.floor(ReplayClock.seconds*20+1e-8);
        if(lastGraphEpoch==ReplayClock.epoch&&lastGraphTick==tick)return;
        java.util.TreeMap<Integer,Object> actors=new java.util.TreeMap<>();
        for(ActorRef ref:births.keySet()){
            Object actor=ref.get();if(actor==null||!owns(actor)||!Native.type("1848416318").isInstance(actor))continue;
            int id=((Number)Native.call(actor,"for","int")).intValue();
            if(Native.call(world,"continue","1217489680",id)==actor)actors.put(id,actor);
        }
        scheduledGraphPasses++;
        withGraphUpdates(()->{for(Object actor:actors.values()){
            Object rig=Native.field(actor,"native");if(!Native.type("859153379").isInstance(rig))continue;
            Object controller=Native.call(rig,"short","1805188833");
            if((boolean)Native.field(controller,"throws")||!(boolean)Native.call(controller,"private","boolean"))continue;
            Native.call(rig,"static","boolean",false,null);scheduledGraphUpdates++;
        }});
        lastGraphEpoch=ReplayClock.epoch;lastGraphTick=tick;
    }
    final Map<Integer,java.util.ArrayDeque<Map<String,Object>>> tailTrace=new java.util.TreeMap<>(),graphTrace=new java.util.TreeMap<>();
    final Map<ActorRef,Map<String,Object>> graphTraceLast=new java.util.HashMap<>();
    long tailTraceRows,graphTraceRows,traceDropped;
    void clearTrace(){tailTrace.clear();graphTrace.clear();graphTraceLast.clear();tailTraceRows=graphTraceRows=traceDropped=0;}
    void traceRow(Map<Integer,java.util.ArrayDeque<Map<String,Object>>> trace,int id,Map<String,Object> row){
        if(!trace.containsKey(id)&&trace.size()>=128){traceDropped++;return;}
        var rows=trace.computeIfAbsent(id,key->new java.util.ArrayDeque<>());
        if(rows.size()>=1024){rows.removeFirst();traceDropped++;}rows.addLast(row);
    }
    public synchronized void tailApplied(Object actor,long order,double received,double consumed,int flags,int bytes,String trigger)throws Exception {
        if(bytes==0||!owns(actor)||!Native.type("1848416318").isInstance(actor))return;
        int id=((Number)Native.call(actor,"for","int")).intValue();
        Map<String,Object> row=new java.util.LinkedHashMap<>();row.put("order",order);row.put("received_seconds",received);row.put("consumed_seconds",consumed);
        row.put("flags",flags);row.put("bytes",bytes);row.put("trigger",trigger);tailTraceRows++;traceRow(tailTrace,id,row);
    }
    public synchronized void graphUpdated(Object rig)throws Exception {
        Object actor=null;ActorRef ref=null;
        for(ActorRef candidate:births.keySet()){
            Object value=candidate.get();if(value!=null&&owns(value)&&Native.type("1848416318").isInstance(value)&&Native.field(value,"native")==rig){actor=value;ref=candidate;break;}
        }
        if(actor==null)return;
        Object controller=Native.call(rig,"short","1805188833");Map<String,Object> inputs=new java.util.LinkedHashMap<>();
        for(Object node:(java.util.List<?>)Native.field(Native.field(controller,"const"),"for")){
            String name=node.getClass().getName();if(name.equals("sz.animation.fp.N1408423910")||name.equals("sz.animation.fp.N1654159715"))inputs.put(name,ReplayBodyPoses.graphScalars(node));
        }
        if(inputs.equals(graphTraceLast.get(ref)))return;graphTraceLast.put(ref,inputs);
        int id=((Number)Native.call(actor,"for","int")).intValue();Map<String,Object> row=new java.util.LinkedHashMap<>();
        row.put("seconds",ReplayClock.seconds);row.put("parameters_seconds",Native.field(controller,"case"));row.put("inputs",inputs);
        row.put("last_frame",ReplayBodyPoses.graphScalars(Native.field(rig,"finally")));row.put("next_frame",ReplayBodyPoses.graphScalars(Native.field(rig,"this")));
        graphTraceRows++;traceRow(graphTrace,id,row);
    }
    synchronized Map<String,Object> traceSnapshot(){return Map.of("tails",tailTrace,"graph_updates",graphTrace,"tail_rows",tailTraceRows,"graph_rows",graphTraceRows,"dropped_rows",traceDropped,"entity_limit",128,"rows_per_entity",1024);}
    void bind(DemoFile demo)throws Exception {
        demo.verifyClass("1422788721");demo.verifyClass("910717201");
        demo.verifyDependency("859153379","552ff5af08161c35156028095c58175d41718f4ed483e188bfd310fcbf93ab9b");
        demo.verifyDependency("1805188833","5189750eda7ed795dc6a18d5654fcdfe151e20403b5b356b26414079e6d6ef8b");
        try{demo.verifyDependency("387875964","d001b239bd0853128154f30088d2fa5e3693ac38c7c92cc5f02a6074807d8493");}
        catch(java.io.IOException previousRelease){
            // Current canonical animation controller keeps the same graph API;
            // its global SourceRigHooks.remember wrappers were removed.
            demo.verifyDependency("387875964","d8b4dae47591101e240843216350a411a241af86664e2a6c9ec89a8f01f6306d");
        }
        if(Boolean.getBoolean("sz.replay.qa.graphbarrier")){
            demo.verifyDependency("198200297","01898342ab66d763aca4cd0b9c28bff1cc90af7999cf2639c8dee2a566d2f9d2");
            demo.verifyDependency("1852960784","097bce4a378492d70e38b995181f2056ecc047f8ee9b0f09627e92a1f354eff6");
        }
        reset();ReplayRender.actorTimes=this;
    }
    synchronized void reset(){births.clear();clocks.clear();graphWaits.clear();admittedGraphs.clear();graphAdmissions.clear();dirtyNpc.clear();invalidatedNpcParameters=settledNpcPoses=0;clearTrace();world=null;samples=bindings=preparedNpcPoses=graphReadyChecks=graphWaitChecks=graphQueuePumps=scheduledGraphPasses=scheduledGraphUpdates=0;}
    synchronized void scope(Object value){if(world!=value){births.clear();clocks.clear();graphWaits.clear();admittedGraphs.clear();dirtyNpc.clear();clearTrace();world=value;lastGraphEpoch=-1;lastGraphTick=Long.MIN_VALUE;}}
    boolean owns(Object actor)throws Exception {return world!=null&&Native.type("1422788721").isInstance(actor)&&Native.call(actor,"transient","1500514786")==world;}
    public synchronized void created(Object actor)throws Exception {
        purgeBirths();if(owns(actor))births.put(new ActorRef(actor,actorsGone),new Birth(ReplayClock.epoch,ReplayClock.seconds));
    }
    public synchronized void configured(Object actor,Object timer)throws Exception {
        if(!owns(actor)||clocks.containsKey(timer))return;
        purgeBirths();Birth birth=births.get(new ActorRef(actor,null));
        double origin=birth!=null&&birth.epoch()==ReplayClock.epoch?birth.seconds():ReplayClock.seconds;
        clocks.put(timer,new Clock(ReplayClock.epoch,origin));bindings++;
    }
    void purgeBirths(){java.lang.ref.Reference<?> dead;while((dead=actorsGone.poll())!=null){births.remove(dead);graphWaits.remove(dead);admittedGraphs.remove(dead);graphTraceLast.remove(dead);dirtyNpc.remove(dead);}}
    /** Do not advance captured packets while an owned NPC is compiling its native graph. */
    synchronized boolean readyNpcGraphs()throws Exception {
        if(!Boolean.getBoolean("sz.replay.qa.graphbarrier")||world==null)return true;
        purgeBirths();boolean ready=true;graphReadyChecks++;java.util.List<Object> controllers=assets==null?null:new java.util.ArrayList<>();
        for(var birth:births.entrySet()){
            ActorRef ref=birth.getKey();Object actor=ref.get();if(actor==null||!Native.type("1848416318").isInstance(actor)||!owns(actor))continue;
            int id=((Number)Native.call(actor,"for","int")).intValue();
            if(Native.call(world,"continue","1217489680",id)!=actor){graphWaits.remove(ref);continue;}
            Object rig=Native.field(actor,"native");if(!Native.type("859153379").isInstance(rig))continue;
            Object controller=Native.call(rig,"short","1805188833");
            if(controllers!=null)controllers.add(controller);
            if((boolean)Native.field(controller,"throws")){graphWaits.remove(ref);continue;}
            if(Native.field(controller,"short")==null)Native.call(rig,"enum","void");
            if(!(boolean)Native.call(controller,"private","boolean")){
                Object future=Native.field(Native.field(controller,"default"),"boolean");
                if(future instanceof java.util.concurrent.CompletableFuture<?> task&&task.isDone()){
                    try{task.join();}catch(java.util.concurrent.CompletionException|java.util.concurrent.CancellationException failed){throw new java.io.IOException("Native NPC animation graph compilation failed for entity "+id,failed);}
                    // The native compiler publishes its completed result through
                    // the client's main-thread queue. Frozen replay ticks must
                    // not prevent this ordinary activation task from running.
                    Native.call(Native.call(Native.type("1852960784"),"for","1852960784"),"case","void");graphQueuePumps++;
                }
            }
            if(!(boolean)Native.call(controller,"private","boolean")){
                ready=false;graphWaitChecks++;long began=graphWaits.computeIfAbsent(ref,key->System.nanoTime());
                if(System.nanoTime()-began>30_000_000_000L)throw new java.io.IOException("Native NPC animation graph did not load for entity "+id);
            }else{
                graphWaits.remove(ref);var prior=admittedGraphs.get(ref);
                if(prior==null||prior.get()!=controller){
                    // The native input layer distinguishes its first update
                    // from subsequent updates. Initialize it at admission,
                    // before a culled actor can receive its first hit/death.
                    withGraphUpdates(()->{
                        if(Boolean.getBoolean("sz.replay.qa.graphinitial"))Native.call(rig,"package","boolean",false,null);
                        if(Boolean.getBoolean("sz.replay.qa.graphbirth"))initializeNpcRoot(Native.call(controller,"super","387875964"));
                    });
                    admittedGraphs.put(ref,new java.lang.ref.WeakReference<>(controller));
                    if(graphAdmissions.size()<256){Object animation=Native.call(controller,"super","387875964"),root=Native.call(animation,"static","1391506621");
                        graphAdmissions.add(Map.of("entity",id,"seconds",ReplayClock.seconds,"birth_seconds",birth.getValue().seconds(),"root",root==null?"":root.getClass().getName()));}
                }
            }
        }
        if(controllers!=null)assets.observe(controllers);
        return ready;
    }
    /** The native context skips root.for at dt=0; admit the root at its actual birth clock. */
    static void initializeNpcRoot(Object animation)throws Exception {
        Object root=Native.call(animation,"static","1391506621");
        if(root==null)throw new java.io.IOException("Activated native NPC graph has no root");
        // Reset through AnimContext's public contract, then select initial native
        // states with no elapsed time. Never write state-machine phases or move
        // recorded time to manufacture the first positive render delta.
        Native.call(animation,"for","void",false);
        Native.call(root,"for","void",animation,0.0f,1.0f);
    }
    void prepareNpcOwner(Object remains,SourceAdapters decoder)throws Exception {
        if(!(boolean)Native.field(remains,"transient"))return;
        Object actorWorld=Native.call(remains,"transient","1500514786");int owner=((Number)Native.field(remains,"case")).intValue();
        Object actor=Native.call(actorWorld,"continue","1217489680",owner);
        prepareNpcActor(actor,decoder);
    }
    void prepareNpcActor(Object actor,SourceAdapters decoder)throws Exception {
        if(actor==null||!Native.type("1848416318").isInstance(actor)||!owns(actor))return;
        Object actorWorld=Native.call(actor,"transient","1500514786");int owner=((Number)Native.call(actor,"for","int")).intValue();
        // A batched seek can reach removal while native interpolation still
        // owns older motion snapshots. Consume this actor's already received
        // source state in order before it disappears; leave the native pose
        // coordinates, interpolation phase and ring ownership unchanged.
        if(decoder!=null)ReplayDecode.settleEntityTails(decoder,actorWorld,owner);
        // The original NPC remains getter reads AnimContext.final(), its cached
        // last rendered pose. Reconstruction can cross a death packet before
        // another frame renders that owner. Use the context's native evaluator
        // at this packet's recorded actor time before the normal copy executes.
        Native.call(actor,"do","910717201");Object rig=Native.field(actor,"native");if(rig==null||!(boolean)Native.call(Native.call(rig,"short","1805188833"),"private","boolean"))return;
        // Prepare the native graph parameters from the updated provider before
        // evaluating bones. This is the rig's regular pose-update path; the
        // separate static() rendering path is not called here.
        withGraphUpdates(()->{
        Native.call(rig,"package","boolean",true,null);
        Object animation=Native.call(Native.call(rig,"short","1805188833"),"super","387875964");
        // do() is the native explicit pose update; extends() may keep an old
        // pose when the owner is culled (AnimContext.case is false).
        Native.call(animation,"do","void");preparedNpcPoses++;
        });
    }
    public synchronized Double sample(Object timer)throws Exception {
        Clock clock=clocks.get(timer);if(clock==null||clock.epoch!=ReplayClock.epoch)return null;
        // Graph asset workers may finish after the switch from the end of the
        // bootstrap to an earlier scene time. Indexed poses own animation;
        // these old simulation clocks must remain fixed even before publication.
        if(indexed)return ((Number)Native.field(timer,"continue")).doubleValue();
        double now=ReplayClock.seconds;if(now<clock.previous)throw new IllegalStateException("Actor clock survived reconstruction reset");
        float rate=((Number)Native.field(timer,"char")).floatValue();
        if(!Float.isFinite(rate))throw new IllegalStateException("Non-finite native actor clock rate");
        double current=((Number)Native.field(timer,"continue")).doubleValue(),value;
        if(Boolean.getBoolean("sz.replay.qa.actoranalytic")){
            if(!Double.isFinite(current))throw new IllegalStateException("Non-finite native actor clock value");
            // A constant-rate interval has one origin. Rendering more frames
            // must not accumulate a different floating-point clock value.
            // Public native rate/value changes still start a new interval at
            // the previous sample, matching the canonical timer's semantics.
            if(!clock.analyticInitialized||rate!=clock.lastRate||Double.doubleToRawLongBits(current)!=Double.doubleToRawLongBits(clock.lastValue)){
                clock.anchorSeconds=clock.previous;clock.anchorValue=current;clock.lastRate=rate;clock.analyticInitialized=true;
            }
            value=clock.anchorValue+(now-clock.anchorSeconds)*rate;clock.lastValue=value;
        }else value=current+(now-clock.previous)*rate;
        SourceAdapters.put(timer,"continue",value);
        // Keep the original parent baseline current so releasing ownership does
        // not inject the whole replay interval into a surviving native timer.
        Object parent=Native.field(timer,"new");SourceAdapters.put(timer,"if",Native.call(parent,"do","double"));
        clock.previous=now;samples++;return value;
    }
    synchronized Map<String,Object> snapshot(){return Map.of("bound_timers",clocks.size(),"bindings",bindings,"samples",samples,"prepared_npc_poses",preparedNpcPoses,"scheduled_graph_passes",scheduledGraphPasses,"scheduled_graph_updates",scheduledGraphUpdates);}
    synchronized void close(){if(ReplayRender.actorTimes==this)ReplayRender.actorTimes=null;reset();}
}
