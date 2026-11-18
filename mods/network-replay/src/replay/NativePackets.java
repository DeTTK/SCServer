package replay;

import com.google.gson.*;
import java.io.*;
import java.util.*;

/** Codec diagnostics run on the game thread after native registries are ready. */
final class NativePackets {
    final DemoFile demo;
    final Set<String> verified=new HashSet<>();
    final Map<String,Map<Integer,Integer>> registryMaps=new HashMap<>();
    final SourceAdapters adapters;
    boolean liveEntityTypesVerified;
    void preflight()throws Exception {
        Set<String> required=new HashSet<>();
        for(DemoFile.Event event:demo.events)if(event.incoming()){
            JsonObject mapping=demo.mapping(event);
            if(mapping==null||mapping.get("target_class").isJsonNull())throw new IOException("Unmapped packet "+event.sourceId());
            required.add(mapping.get("target_class").getAsString());
        }
        if(demo.manifest.has("target_adapter_hashes"))required.addAll(demo.manifest.getAsJsonObject("target_adapter_hashes").keySet());
        if(demo.manifest.has("bootstrap_adapter_hashes"))required.addAll(demo.manifest.getAsJsonObject("bootstrap_adapter_hashes").keySet());
        for(String name:required)demo.verifyClass(name);
        ReplayAssets.verify(demo);
        demo.verifyDependency("126423472","eba857174bd0aa253b7ed3dd5d68971e42a57ffdfada0043bc10d4a43eb9bd03");
        demo.verifyDependency("1219970978","bd6147246a32238b3d42e46bf617fde36279c1d987f2c88a5b6d6defe1cf4fff");demo.verifyClass("1318329920");
        demo.verifyDependency("1364948573","12c3960a5879fdc83d6ade315885d950063145f43c2734e138502c323bbc4e78");
        if(adapters!=null)demo.verifyDependency("1943200621","80a6fd6f1168a15e542ce87c64cfc410ccb5a73b2144c145720f09a94d105736");
        if(adapters!=null)demo.verifyDependency("861754670","9cc087e9e69da4640fbadac7c5fa39d104cee05780ae932b6016894034cb7c01");
        if(adapters!=null)demo.verifyDependency("189575079","d6a3010f4991583eaefd89a68d9b7fd360e5ce7487dca92cd122368c7320531c");
    }
    NativePackets(DemoFile demo){
        this.demo=demo;
        adapters=demo.manifest.has("source_profile")&&demo.manifest.get("source_profile").getAsString().equals("08.10-2026")?new SourceAdapters(demo):null;
        for(var family:demo.registries.entrySet()){
            Map<Integer,Integer> ids=new HashMap<>();for(var id:family.getValue().getAsJsonObject().entrySet())ids.put(Integer.parseInt(id.getKey()),id.getValue().getAsInt());
            registryMaps.put(family.getKey(),Map.copyOf(ids));
        }
        Map<Integer,Integer> packets=new HashMap<>();
        for(var entry:demo.packets.entrySet()){
            JsonObject info=entry.getValue().getAsJsonObject();if(!info.get("target_id").isJsonNull()&&(info.get("codec_shape_equal").getAsBoolean()||(info.has("adapter")&&Set.of("source_hit_extra_flag","source_equipment_instance_uuid","source_dual_quest_hint_clear","source_entity_spawn_v1").contains(info.get("adapter").getAsString()))))packets.put(Integer.parseInt(entry.getKey()),info.get("target_id").getAsInt());
        }
        registryMaps.put("594335374",Map.copyOf(packets));
    }
    Object decode(DemoFile.Event event)throws Exception {
        if(adapters!=null&&!liveEntityTypesVerified&&demo.registryEvidence.has("entity_types")){
            demo.verifyClass("423259911");Map<Integer,JsonObject> live=new HashMap<>();
            for(JsonElement entry:RegistrySnapshot.read().getAsJsonArray("entity_types"))live.put(entry.getAsJsonObject().get("id").getAsInt(),entry.getAsJsonObject());
            JsonArray expected=demo.registryEvidence.getAsJsonObject("entity_types").getAsJsonArray("target");
            if(live.size()!=expected.size())throw new IOException("Native entity registry size changed");
            for(JsonElement entry:expected){JsonObject value=entry.getAsJsonObject(),actual=live.get(value.get("id").getAsInt());
                if(actual==null||!actual.get("name").equals(value.get("name"))||!actual.get("class").equals(value.get("class")))throw new IOException("Native entity registry identity changed: "+value);
            }liveEntityTypesVerified=true;
        }
        if(adapters!=null&&demo.manifest.has("target_adapter_hashes"))for(String name:demo.manifest.getAsJsonObject("target_adapter_hashes").keySet())adapters.verify(name);
        JsonObject info=demo.mapping(event);
        if(info==null||info.get("target_class").isJsonNull())throw new IOException("Unmapped packet "+event.sourceId());
        if(adapters!=null&&info.has("adapter")&&info.get("adapter").getAsString().equals("source_motion_v1"))return Motion.read(event.payload());
        if(adapters!=null&&info.has("adapter")&&info.get("adapter").getAsString().equals("source_local_controls_v1"))return SelfMotion.read(event.payload());
        Set<String> semantic=Set.of("source_hit_extra_flag","source_equipment_instance_uuid","source_dual_quest_hint_clear","source_entity_spawn_v1");
        if(!info.get("codec_shape_equal").getAsBoolean()&&!(adapters!=null&&info.has("adapter")&&semantic.contains(info.get("adapter").getAsString())))throw new IOException("Changed codec for "+event.sourceId());
        String name=info.get("target_class").getAsString();if(!verified.contains(name)){demo.verifyClass(name);verified.add(name);}
        Object packet=Native.type(name).getDeclaredConstructor().newInstance();
        ReplayDecode.begin(registryMaps);if(adapters!=null)ReplayDecode.use(adapters);ReplayClock.beginDecode();try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(event.payload()))){
            if(adapters!=null&&info.has("adapter")&&info.get("adapter").getAsString().equals("source_dual_quest_hint_clear")){
                QuestHints.Clear clear=new QuestHints.Clear(input.readBoolean());if(input.available()!=0)throw new IOException("Unread quest clear bytes: "+input.available());return clear;
            }
            if(adapters!=null&&info.has("adapter")&&info.get("adapter").getAsString().equals("source_weather_v1")){
                adapters.verify("746015971");adapters.verify("126634276");adapters.verify("217636303");
                SourceWeather.Frame frame=SourceWeather.read(input,in->Native.call(Native.type("1046176253"),"assert","746015971",in));
                if(input.available()!=0)throw new IOException("Unread source weather bytes: "+input.available());return frame;
            }
            Native.call(packet,"extends","void",input);
            if(input.available()!=0)throw new IOException("Unread payload bytes: "+input.available());
        }finally{ReplayClock.endDecode();ReplayDecode.end();}
        return packet;
    }
    /** Login and world handlers retain the game's own lifecycle. No UDP socket. */
    static final class World implements Timeline.Sink {
        final Object game;final NativePackets packets;Object connection;
        final ReplaySessions menuSession=new ReplaySessions();
        final Map<SourceWeather.Kind,SourceWeather.Update> weather=new EnumMap<>(SourceWeather.Kind.class);
        final SourceWeatherProgram weatherProgram;
        final ReplayBodyPoses bodyPoses;
        final ReplayActorTimes actorTimes=new ReplayActorTimes();
        final ReplayAssets assets=new ReplayAssets();
        boolean prepareAssets;
        final SourceWeatherEnvironment environment;
        final SourceTimelineController sourceTimeline;
        final SourceElectricController sourceElectric;
        final SourceExtractorController sourceExtractors;
        final ExtractorSceneQa sourceExtractorQa=new ExtractorSceneQa();
        final QuestHints questHints=new QuestHints();
        final SourceEffects effects;
        final ReplayAudio audio=new ReplayAudio();
        final ReplayLatency latency=ReplayLatency.nativeDisplay();
        NativeSceneCapture sceneCapture;
        final MotionWorld motion;
        final LocalMotion local;
        boolean bootstrapped,loginSeen,lastApplied,settled;int sessions,preWorldSkipped,connectionControlsArchived;
        boolean bootstrapOnly;
        final SceneWorldAdmission indexedAdmission=new SceneWorldAdmission();
        void completeIndexedView(boolean frameReady)throws Exception {
            indexedAdmission.present(frameReady,new SceneWorldAdmission.Access(){
                public boolean connected()throws Exception {return Native.call(connection,"do","272922522")==Native.field(Native.type("272922522"),"GAME_SERVER_CONNECTED");}
                public Object screen()throws Exception {return Native.call(game,"float","622415611");}
                public boolean ownedLoading(Object screen)throws Exception {return Native.type("1030535009").isInstance(screen)&&Native.field(screen,"else")==connection;}
                public void finish(Object screen)throws Exception {Native.call(screen,"import","void");}
            });
        }
        // Native base-handler messages return to backend, redirect, or close a
        // transport. Decode/archive them, but retain the replay world at EOF.
        static final Set<String> CONNECTION_CONTROLS=Set.of("1556037196","1459575880","92076752","1857004924","1444193065");
        int settledComponents,advancedComponents;
        Object simulationClock;long simulationFrontiers;
        boolean graphDue;
        public Timeline.TimeAdvance advanceTime(long from,long to)throws Exception {
            if(bootstrapOnly&&bootstrapped)return new Timeline.TimeAdvance(from,false);
            if(graphDue&&Boolean.getBoolean("sz.replay.qa.graphschedule")){
                ReplayClock.seconds=from/1e9;
                if(!ready())return new Timeline.TimeAdvance(from,false);
            }
            if(simulationClock!=null&&bootstrapped){
                long next=ReplayClock.nextTickNs(simulationClock);
                if(next<=to){long frontier=Math.max(from,next);ReplayClock.seconds=frontier/1e9;simulationFrontiers++;graphDue=true;return new Timeline.TimeAdvance(frontier,false);}
            }
            if(bootstrapped&&Boolean.getBoolean("sz.replay.qa.physicsbarrier")){
                // The event can add an impulse or replace a collider. Complete
                // the native body's earlier fixed steps before dispatching it.
                // The ordinary worker still owns stepping; waiting advances
                // recorded time only to this frontier, never past the event.
                ReplayClock.seconds=to/1e9;
                if(!bodyPoses.awaitReady())return new Timeline.TimeAdvance(to,false);
            }
            return new Timeline.TimeAdvance(to,true);
        }
        static final Set<Integer> PRE_WORLD=Set.of(146,1667,1090,325,1254);
        JsonArray describeMotionEntities(Motion.Frame frame)throws Exception {
            JsonArray result=new JsonArray();Object nativeWorld=Native.call(connection,"instanceof","1054393445");Set<Integer> seen=new HashSet<>();
            for(Motion.Update update:frame.updates())if(seen.add(update.entity())){
                JsonObject entry=new JsonObject();entry.addProperty("id",update.entity());entry.addProperty("source_flags",update.flags());entry.addProperty("tail_bytes",update.tail().length);
                Object entity=Native.call(nativeWorld,"continue","1217489680",update.entity());entry.addProperty("loaded",entity!=null);
                if(entity!=null){entry.addProperty("class",entity.getClass().getName());JsonArray components=new JsonArray();int index=0;
                    for(Object component:(List<?>)Native.call(entity,"static","java.util.List")){JsonObject value=new JsonObject();value.addProperty("index",index++);value.addProperty("class",component.getClass().getName());components.add(value);}entry.add("components",components);
                }result.add(entry);
            }return result;
        }
        World(Object game,NativePackets packets){this.game=game;this.packets=packets;this.weatherProgram=new SourceWeatherProgram(packets.demo);this.bodyPoses=new ReplayBodyPoses(packets.adapters);this.environment=new SourceWeatherEnvironment(packets.adapters);this.effects=new SourceEffects(packets.adapters);this.motion=new MotionWorld(packets.adapters);this.local=new LocalMotion(packets.adapters);this.sourceTimeline=new SourceTimelineController(this);this.sourceElectric=new SourceElectricController(this);this.sourceExtractors=new SourceExtractorController(packets.adapters);}
        void checkDeferred()throws IOException {if(packets.adapters!=null&&packets.adapters.tailFailure!=null)throw new IOException("Deferred source motion component failed",packets.adapters.tailFailure);}
        public boolean ready()throws Exception {
            return resourcesReady()&&(!bootstrapOnly||!bootstrapped);
        }
        boolean resourcesReady()throws Exception {
            checkDeferred();if(connection==null)return true;Object handler=Native.field(connection,"static"),transport=Native.field(handler,"static");
            if(bootstrapped){Object recordedWorld=Native.call(connection,"instanceof","1054393445");bodyPoses.scope(recordedWorld);actorTimes.scope(recordedWorld);}
            if(transport!=null&&Native.field(transport,"continue")!=null){
                // Asset/chunk work must also advance while the recorded clock
                // waits for it. This is the native connection pump, with no
                // added gameplay time and no external transport.
                Native.call(connection,"for","void",0d);checkDeferred();
            }
            boolean ready=(transport==null||Native.field(transport,"continue")==null)&&bodyPoses.awaitReady()&&actorTimes.readyNpcGraphs()&&(sceneCapture==null||sceneCapture.ready(this));
            if(ready&&graphDue&&Boolean.getBoolean("sz.replay.qa.graphschedule")){actorTimes.advanceNpcGraphs();graphDue=false;}
            return ready;
        }
        public void reset()throws Exception {
            actorTimes.assets=prepareAssets?assets:null;
            ReplayClock.rebase();
            ReplayClock.seedVisual(packets.demo.captureMonotonicOriginNs);
            simulationClock=null;simulationFrontiers=0;graphDue=false;
            if(Boolean.getBoolean("sz.replay.qa.simticks")){
                if(Boolean.getBoolean("sz.replay.qa.tickloop"))packets.demo.verifyDependency("1402131154","8c255351d4aeb621586434fce07c5d3551b1293e6a6985701f135986cffccbcf");
                packets.demo.verifyClass("1591066367");simulationClock=Native.field(Native.type("1402131154"),"h");
                // Use the actual constructor rate and preserve its native menu
                // baseline. Each replay epoch begins at recorded time zero.
                ReplayClock.imported(simulationClock);if(ReplayClock.failure!=null)throw new IOException("Replay simulation clock initialization failed",ReplayClock.failure);
            }
            ReplayRender.clearResources();
            if(packets.adapters!=null){
                for(String name:List.of("1831969298","1335989370","1069936767","1141491285","1462088549","1534871542"))packets.adapters.verify(name);
                for(String name:List.of("1182339164","353540541","1198595589","779455578"))packets.adapters.verify(name);
                for(String name:List.of("StandardJvmBootstrap","1553688601","1850427543","637620157"))packets.adapters.verify(name);
                for(DemoFile.Event event:packets.demo.events){JsonObject info=packets.demo.mapping(event);
                    if(event.incoming()&&info!=null&&info.has("target_class")&&!info.get("target_class").isJsonNull()&&info.get("target_class").getAsString().equals("637620157")){
                        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(event.payload()))){ReplayClock.seedWall(input.readLong(),event.timeNs()/1e9,input.readFloat());}break;
                    }
                }
            }
            if(packets.adapters!=null){
                for(String name:List.of("1422788721","468333307","1619781176","764970879","656386660"))packets.adapters.verify(name);
                // The current canonical client retired the global Source rig.
                // Validate its bindings only on releases that actually install it.
                boolean tp=NativePackets.class.getClassLoader().getResource("szgmod/SourceTpWeaponLayer.class")!=null;
                boolean rig=NativePackets.class.getClassLoader().getResource("szgmod/SourceRigHooks.class")!=null;
                boolean fp=NativePackets.class.getClassLoader().getResource("szgmod/SourceWeaponLayer.class")!=null;
                if(tp||rig||fp){
                    if(!tp||!rig||!fp)throw new IOException("Incomplete installed Source rig bridge");
                    for(String name:List.of("szgmod/SourceTpWeaponLayer","szgmod/SourceRigHooks"))packets.adapters.verify(name);
                    packets.adapters.verifyDependency("szgmod/SourceWeaponLayer","dfe1ffaa564924216f040d6fa696317042a7a38b5aa87ebf7b21c01b3f030555");
                }
            }
            if(Boolean.getBoolean("sz.replay.qa.weaponprobe")){
                packets.demo.verifyDependency("276406255","be0c46bc71b871059d2288fc4705e0a2efd62c3e6280e7bf737a517d244398ff");
                packets.demo.verifyDependency("353451775","e152ac1b0635649498ebbf6566e307535da74c0dfb4af419d2592838880bbad3");
            }
            bodyPoses.reset();
            actorTimes.bind(packets.demo);
            local.reset();
            motion.reset();
            latency.reset();
            if(packets.adapters!=null){packets.adapters.appliedTailBytes=packets.adapters.appliedGunDetails=packets.adapters.sourceMotifProgress=packets.adapters.staleLocalReloads=0;packets.adapters.appliedTailComponents=0;packets.adapters.motifProgressSnapshots.clear();packets.adapters.sourceOnlyOccurrences.clear();packets.adapters.sourceOnlyLastValues.clear();}
            sessions=preWorldSkipped=0;loginSeen=false;openSession();if(packets.adapters!=null)packets.adapters.discardedTailBytes=0;
        }
        void openSession()throws Exception {
            indexedAdmission.reset();
            actorTimes.reset();
            sourceExtractorQa.reset();
            sourceExtractors.unbind();
            try{sourceTimeline.unbind();}finally{sourceElectric.unbind();}
            audio.finish();
            bootstrapped=settled=false;sessions++;
            local.detach();
            if(packets.adapters!=null){ReplayDecode.clearDeferred(packets.adapters);packets.adapters.tailFailure=null;}
            weather.clear();
            questHints.reset();
            menuSession.take(game,connection);
            if(connection!=null)Native.call(connection,"else","void");
            Object transport=Class.forName("replay.OfflineTransport",true,getClass().getClassLoader()).getConstructor().newInstance();
            connection=Native.type("1219970978").getConstructor(Native.type("1402131154"),String.class,Native.type("1244605664"),Native.type("556118971"),Native.type("272922522"),Native.type("806156907")).newInstance(game,"replay",Native.field(Native.type("1244605664"),"DIRECT_INMEMORY"),Native.field(Native.type("556118971"),"UDP"),Native.field(Native.type("272922522"),"GAME_SERVER_LOGIN"),transport);
            ReplayRender.offlineConnection=connection;
            Native.call(game,"protected","void",connection);
            Native.call(game,"enum","void",connection);
            questHints.bind(game,packets.adapters);effects.bind();environment.bind();audio.bind();sourceElectric.bind();sourceTimeline.bind();
            sourceExtractors.bind();
            latency.bind(packets.demo);
        }
        public void apply(DemoFile.Event event)throws Exception {
            ReplayClock.seconds=event.timeNs()/1e9;lastApplied=settled=false;graphDue=true;
            checkDeferred();
            if(!event.incoming()&&event.sourceId()==1){
                Object login=packets.decode(event);String recordedName=(String)Native.field(login,"throws");
                if(loginSeen)openSession();SourceAdapters.put(connection,"native",recordedName);
                // The native HUD keys its local social/configuration model by
                // connection character name. Follow the ordinary initializer
                // after changing that name, before any world/UI packet runs.
                packets.adapters.verify("1032432581");packets.adapters.verify("1230575234");
                Native.call(Native.type("1032432581"),"throw","void",connection);loginSeen=true;return;
            }
            JsonObject mapping=packets.demo.mapping(event);
            String target=mapping!=null&&mapping.has("target_class")&&!mapping.get("target_class").isJsonNull()?mapping.get("target_class").getAsString():"";
            if((!event.incoming()&&target.equals("782134028"))||(event.incoming()&&target.equals("1482463136"))){
                Object ping=packets.decode(event);long token=((Number)Native.field(ping,"private")).longValue();
                if(event.incoming()){lastApplied=true;latency.response(event.connection(),token,event.timeNs());}
                else latency.request(event.connection(),token,event.timeNs());
                return;
            }
            if(!bootstrapped&&event.sourceId()!=11&&(!event.incoming()||!PRE_WORLD.contains(event.sourceId()))){if(event.incoming())preWorldSkipped++;return;}
            local.bind(game);
            if(!event.incoming()){
                if(event.sourceId()==223&&packets.adapters!=null)local.apply(game,SelfMotion.read(event.payload()));return;
            }
            lastApplied=true;
            Object packet=packets.decode(event);
            if(CONNECTION_CONTROLS.contains(target)){connectionControlsArchived++;lastApplied=false;return;}
            if(packet instanceof QuestHints.Clear clear){questHints.clear(clear.primary());return;}
            if(packets.adapters!=null&&event.sourceId()==1309){questHints.update(Native.field(packet,"finally"));return;}
            if(packet instanceof Motion.Frame frame){ReplayDecode.begin(packets.registryMaps);ReplayDecode.use(packets.adapters);ReplayClock.beginDecode();try{motion.apply(connection,frame);}finally{ReplayClock.endDecode();ReplayDecode.end();}return;}
            if(packet instanceof SourceWeather.Frame frame){
                List<Object> legacy=new ArrayList<>();
                for(SourceWeather.Update update:frame.updates()){
                    if(update.kind()==SourceWeather.Kind.ELECTRIC_STORM)sourceElectric.require((SourceWeather.Electric)update.current());
                    if(update.kind()==SourceWeather.Kind.WORLD_TIMELINE)sourceTimeline.require((SourceWeather.WorldTimeline)update.current());
                    weather.put(update.kind(),update);
                    if(update.kind().ordinal()<2){Object nativeUpdate=Native.type("217636303").getDeclaredConstructor().newInstance();
                        Object[] kinds=(Object[])Native.call(Native.type("1384066014"),"values","[L1384066014;");
                        SourceAdapters.put(nativeUpdate,"void",kinds[update.kind().ordinal()]);SourceAdapters.put(nativeUpdate,"switch",update.current());SourceAdapters.put(nativeUpdate,"enum",update.previous());legacy.add(nativeUpdate);
                    }
                }packet=Native.type("1620392336").getDeclaredConstructor().newInstance();SourceAdapters.put(packet,"const",legacy);
            }
            if(packets.adapters!=null){packets.adapters.verify("1536302723");packets.adapters.verify("1993742576");}
            Object handler=Native.field(Native.field(connection,"static"),"char");
            ReplayDecode.begin(packets.registryMaps);if(packets.adapters!=null)ReplayDecode.use(packets.adapters);ReplayClock.beginDecode();
            if(packets.adapters!=null)packets.adapters.activeQuestHints=questHints;
            try{Native.call(handler,"switch","void",packet);}finally{if(packets.adapters!=null)packets.adapters.activeQuestHints=null;ReplayClock.endDecode();ReplayDecode.end();}
            checkDeferred();if(event.sourceId()==11){bootstrapped=true;local.bind(game);}
            if(sceneCapture!=null&&bootstrapped)sceneCapture.spawned(event,packet,Native.call(connection,"instanceof","1054393445"));
        }
        public void settle()throws Exception {
            if(settled||!bootstrapped||connection==null||!ready())return;
            Object nativeWorld=Native.call(connection,"instanceof","1054393445");
            settledComponents=ReplayDecode.settleTails(packets.adapters,nativeWorld);advancedComponents=0;
            actorTimes.settleNpcPoses();checkDeferred();settled=true;
        }
        void close()throws Exception {try{try{sourceExtractorQa.reset();}finally{sourceExtractors.unbind();}}finally{closeExisting();}}
        void closeExisting()throws Exception {try{try{sourceTimeline.unbind();}finally{try{sourceElectric.unbind();}finally{try{audio.finish();}finally{try{latency.close();}finally{environment.unbind();effects.unbind();questHints.unbind();local.reset();if(packets.adapters!=null)ReplayDecode.clearDeferred(packets.adapters);if(connection!=null){Native.call(connection,"else","void");connection=null;}}}}}}finally{try{bodyPoses.close();}finally{try{actorTimes.close();}finally{menuSession.restore(game);}}}}
    }
}
