package replay;

import com.google.gson.*;
import szmods.api.*;
import java.nio.file.*;
import java.util.*;

/** In-game replay library and offline viewer. */
public final class ReplayMod implements ClientMod {
    ModContext context;Object game;DemoFile demo;NativePackets codecs;NativePackets.World world;Timeline timeline;
    String status="F12: список реплеев",lastError="";boolean panel,openKey,autoLoaded,auditing,panelCapture;
    ReplayLibrary library;
    int auditCursor,auditPassed,auditFailed,auditUnmapped;JsonArray auditFailures=new JsonArray();
    final Map<Integer,int[]> auditPackets=new TreeMap<>();final Map<String,Integer> auditErrors=new TreeMap<>();
    int auditMotion,auditMotionRecords;long auditMotionTailBytes;
    int auditLocal,auditLocalFailed;
    int auditWeather,auditActiveWeather;JsonArray auditWeatherStates=new JsonArray();
    int auditQuestClears;
    NativePackets.World probeWorld;int probeCursor,probeApplied;boolean probeRequested,probeBootstrapped;
    JsonObject probeReport;
    boolean placedPanel;
    boolean frameActive;
    final ReplayFrameProfile frameProfile=new ReplayFrameProfile();
    boolean pendingStart;int pendingStartFrames;
    JsonObject qaUiSample;
    ReplayTickLoop tickLoop;
    Thread frameThread;
    final ReplayRender.WorldTicks tickHandler=this::nativeTickCompleted;
    void nativeTickCompleted(Object owner)throws Exception {
        if(owner!=game||Thread.currentThread()!=frameThread||world==null||!world.bootstrapped||world.simulationClock==null||tickLoop==null)return;
        tickLoop.completed();ReplayClock.seconds=timeline.position/1e9;
    }
    boolean drawnFrame,browse;
    long lastStateNs;
    long playbackLastNs;
    int cameraSubject=-1;long cameraSelection;boolean cameraFirstPerson;
    float scrubSeconds;boolean scrubbing;
    NativeSceneCapture sceneCapture;
    NativeSceneValuesQa sceneValuesQa;
    SceneTracks preparedScene;NativeScenePlayback scenePlayback;NativeSceneAudio sceneAudio;
    NativeSceneEffects sceneEffects;
    boolean sceneBootstrapping;
    SceneLoading sceneLoading;
    final FreeCamera camera=new FreeCamera();
    final ReplayCursor cursor=new ReplayCursor();
    final ReplayNativeUi nativeUi=new ReplayNativeUi();
    record OpenRequest(Path path,boolean play){}
    final Set<Integer> down=new HashSet<>();final Queue<OpenRequest> opens=new java.util.concurrent.ConcurrentLinkedQueue<>();
    interface Action {void run()throws Exception;}
    final Queue<Action> actions=new java.util.concurrent.ConcurrentLinkedQueue<>();
    public void onLoad(ModContext c){context=c;Path mods=Path.of(System.getProperty("sz.mods.dir","mods")).toAbsolutePath();library=new ReplayLibrary(Path.of(System.getProperty("sz.replay.directory",mods.getParent().resolve("demos").toString())));library.refresh();context.log("Network Replay loaded. F12 opens the replay library.");}
    public void registerKeybinds(KeybindRegistry r){r.register("open","Список реплеев",301);}
    public void registerSettings(SettingsRegistry r){
        var tab=r.registerTab("replay","Сетевой реплей");tab.section("files","Файлы .demo — F12");
        var open=tab.booleanSetting("open","Открыть список реплеев",false);open.onChange(v->{if(v){panel=true;library.refresh();open.set(false);}});
        var audit=tab.booleanSetting("audit","Проверить нативные декодеры",false);audit.onChange(v->{if(v){actions.add(this::beginAudit);audit.set(false);}});
    }
    void choose(){
        Thread t=new Thread(()->{
            try{javax.swing.JFileChooser chooser=new javax.swing.JFileChooser(library.directory.toFile());chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Network demo (*.demo)","demo"));if(chooser.showOpenDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION)opens.add(new OpenRequest(chooser.getSelectedFile().toPath(),true));}
            catch(Exception e){context.log("File picker failed: "+e);}
        },"Replay file picker");t.setDaemon(true);t.start();
    }
    void load(Path path,boolean play)throws Exception {
        DemoFile next=new DemoFile(path);
        List<Path> sessions=ReplayArchives.split(next,library.directory);if(sessions.size()>1){next=new DemoFile(sessions.get(0));library.refresh();}
        NativePackets nextCodecs=new NativePackets(next);
        if(play)nextCodecs.preflight();
        stop();lastError="";probeRequested=false;demo=next;codecs=nextCodecs;timeline=new Timeline(demo.events);
        status=demo.playbackBlocker();if(status==null)status="Реплей загружен";panel=true;browse=false;context.log("Loaded "+demo.path+" events="+demo.events.size()+" status="+status);
        writeState();if(Boolean.getBoolean("sz.replay.audit"))beginAudit();
        if(play&&!auditing)start();
    }
    void beginAudit(){if(demo==null||world!=null||probeWorld!=null)return;try{Files.writeString(context.configDirectory().resolve("attribute-registry.json"),RegistrySnapshot.read().toString());}catch(Exception e){context.log("Attribute snapshot: "+root(e));}auditing=true;auditCursor=auditPassed=auditFailed=auditUnmapped=0;auditMotion=auditMotionRecords=auditLocal=auditLocalFailed=0;auditMotionTailBytes=0;auditWeather=auditActiveWeather=auditQuestClears=0;auditWeatherStates=new JsonArray();auditFailures=new JsonArray();auditPackets.clear();auditErrors.clear();status="Native decoder audit";}
    void auditStep()throws Exception {
        int end=Math.min(demo.events.size(),auditCursor+48);
        for(;auditCursor<end;auditCursor++){
            DemoFile.Event event=demo.events.get(auditCursor);
            if(!event.incoming()){
                if(event.sourceId()==223&&codecs.adapters!=null)try{codecs.decode(event);auditLocal++;}catch(Exception e){auditLocalFailed++;auditErrors.merge("outgoing 223: "+root(e),1,Integer::sum);}
                continue;
            }
            int[] totals=auditPackets.computeIfAbsent(event.sourceId(),id->new int[3]);
            if(event.targetId()<0){auditUnmapped++;totals[2]++;continue;}
            try{Object decoded=codecs.decode(event);if(decoded instanceof Motion.Frame motion){auditMotion++;auditMotionRecords+=motion.updates().size();for(Motion.Update update:motion.updates())auditMotionTailBytes+=update.tail().length;}
                if(decoded instanceof QuestHints.Clear)auditQuestClears++;
                if(decoded instanceof SourceWeather.Frame weather){auditWeather++;for(SourceWeather.Update update:weather.updates())if(update.kind().ordinal()>=2){boolean ported=update.kind()==SourceWeather.Kind.WORLD_TIMELINE&&demo.hashes.has("962844222")||update.kind()==SourceWeather.Kind.ELECTRIC_STORM&&demo.hashes.has("253290967");if(update.unsupportedActive()&&!ported)auditActiveWeather++;JsonObject state=new JsonObject();state.addProperty("event",auditCursor);state.addProperty("kind",update.kind().name());state.addProperty("current",update.current().toString());state.addProperty("previous",update.previous()==null?null:update.previous().toString());state.addProperty("requires_active_handler",update.unsupportedActive());state.addProperty("active_handler_ported",ported);auditWeatherStates.add(state);}}
                auditPassed++;totals[0]++;}
            catch(Exception | LinkageError e){auditFailed++;totals[1]++;auditErrors.merge(event.sourceId()+": "+root(e),1,Integer::sum);if(auditFailures.size()<200){JsonObject failure=new JsonObject();failure.addProperty("event",auditCursor);failure.addProperty("packet",event.sourceId());failure.addProperty("time_ns",event.timeNs());failure.addProperty("error",root(e));auditFailures.add(failure);}}
        }
        if(auditCursor==demo.events.size()){
            auditing=false;JsonObject report=new JsonObject();report.addProperty("demo",demo.path.toString());report.addProperty("incoming",demo.manifest.get("incoming_count").getAsInt());report.addProperty("decoded_full_payload",auditPassed);report.addProperty("decode_failures",auditFailed);report.addProperty("unmapped",auditUnmapped);report.addProperty("world_application_verified",false);report.add("failures",auditFailures);
            report.addProperty("compatibility_fingerprint",demo.compatibilityFingerprint);
            report.addProperty("live_entity_types_verified",codecs.liveEntityTypesVerified);
            JsonObject perPacket=new JsonObject();for(var entry:auditPackets.entrySet()){JsonObject counts=new JsonObject();counts.addProperty("decoded",entry.getValue()[0]);counts.addProperty("failed",entry.getValue()[1]);counts.addProperty("unmapped",entry.getValue()[2]);perPacket.add(entry.getKey().toString(),counts);}report.add("packets",perPacket);
            JsonObject errors=new JsonObject();auditErrors.forEach(errors::addProperty);report.add("error_counts",errors);
            report.addProperty("native_outer_codec_decoded",auditPassed-auditMotion-auditWeather-auditQuestClears);report.addProperty("adapted_quest_hint_clears",auditQuestClears);report.addProperty("adapted_weather_frames",auditWeather);report.addProperty("active_weather_updates_requiring_port",auditActiveWeather);report.add("source_weather_states",auditWeatherStates);report.addProperty("adapted_motion_frames",auditMotion);report.addProperty("adapted_motion_updates",auditMotionRecords);report.addProperty("decoded_motion_tail_bytes",auditMotionTailBytes);report.addProperty("motion_tail_application_verified",false);report.addProperty("compressed_chunk_contents_verified",false);report.addProperty("source_only_fields_rendered",false);
            report.addProperty("recorded_local_control_frames",auditLocal);report.addProperty("local_control_decode_failures",auditLocalFailed);report.addProperty("local_prediction_applied",false);
            if(codecs.adapters!=null){report.add("prefab_decode_failures",codecs.adapters.prefabFailures);report.addProperty("prefabs_decoded",codecs.adapters.prefabsDecoded);}
            Files.writeString(context.configDirectory().resolve("native-audit.json"),report.toString());
            status="Audit: "+auditPassed+" decoded, "+auditFailed+" failed, "+auditUnmapped+" unmapped";context.log(status);writeState();
        }
    }
    public void onFrame(ClientFrame frame)throws Exception {
        ReplayRender.beginSceneRenderFrame();
        if(frameActive)return;frameActive=true;
        long frameBegan=frameProfile.begin(world!=null&&timeline!=null&&timeline.playing&&!timeline.seeking);
        ReplayPerformance.preparing=false;ReplayPerformance.enter("replay-frame");
        try{
            if((world!=null||probeWorld!=null)&&ReplayClock.failure!=null){if(probeWorld!=null){finishWorldProbe(ReplayClock.failure);return;}Throwable failure=ReplayClock.failure;fail(failure);stop();writeState();return;}
        game=frame.game();
        drawnFrame=false;
        if(!panel)panelCapture=false;
        if(panel||world!=null||probeWorld!=null)cursor.acquire(game);else cursor.release();
        if(!autoLoaded){autoLoaded=true;String file=System.getProperty("sz.replay.demo","");if(!file.isBlank())opens.add(new OpenRequest(Path.of(file),Boolean.getBoolean("sz.replay.autoplay")));}
        OpenRequest request=opens.poll();if(request!=null)try{load(request.path(),request.play());}catch(Exception e){fail(e);}
        try{
            for(int count=0;count<8;count++){Action action=actions.poll();if(action==null)break;action.run();}
            if(pendingStart&&--pendingStartFrames<=0){pendingStart=false;beginPlayback();}
            boolean key=(boolean)Native.call(Native.type("877572775"),"szmods$original$interface","boolean",301);
            if(key&&!openKey){panel=!panel;if(panel&&(world==null||browse))library.refresh();}openKey=key;
            // An empty frame also closes detached panels after F12 and pumps
            // their input. Do not leave the last platform-window frame alive.
            if(world!=null)Native.call(Native.call(Native.type("976516049"),"try","976516049"),"throw","void");
            if(auditing)auditStep();
            if(Boolean.getBoolean("sz.replay.worldprobe")&&!probeRequested&&demo!=null&&!auditing){probeRequested=true;beginWorldProbe();}
            if(probeWorld!=null)worldProbeStep();
            if(world!=null){
                Timeline timeline=sceneLoading==null?this.timeline:sceneLoading.preparation;
                if(sceneAudio!=null&&!sceneAudio.restoreReady()){playbackLastNs=System.nanoTime();return;}
                if(sceneAudio!=null)sceneAudio.prepareResources();
                world.bootstrapOnly=sceneBootstrapping&&preparedScene!=null&&sceneAudio!=null&&sceneAudio.cached&&(sceneEffects==null||sceneEffects.cached)&&Boolean.parseBoolean(System.getProperty("sz.replay.minimal-world","true"));
                frameThread=Thread.currentThread();if(tickLoop!=null)tickLoop.beginFrame(tickLoop.maxImports);
                world.checkDeferred();long bakeElapsed=sceneCapture==null?sceneEffects!=null&&sceneEffects.recording?sceneEffects.drive(timeline):-1:sceneCapture.drive(timeline,world);boolean reconstruct=timeline.seeking||world.audio.catchup;
                world.actorTimes.rendering=timeline.playing&&!timeline.seeking;
                world.audio.configure(!timeline.playing||reconstruct,timeline.speed);
                long now=System.nanoTime();long elapsed=playbackLastNs==0?0:Math.max(0,now-playbackLastNs);playbackLastNs=now;
                if(bakeElapsed>=0)elapsed=bakeElapsed;
                if(scenePlayback!=null){
                    SceneMainTasks.pump();
                    nativeUi.frame(game,now);
                    if(!scenePlayback.resourcesReady()){
                        status=scenePlayback.resourceStatus();playbackLastNs=System.nanoTime();
                        if(panel&&scenePlayback.selectedFrame==null)draw();
                        if(System.nanoTime()-lastStateNs>500_000_000L)writeState();return;
                    }
                    status="Новая система: прямой выбор времени";
                    if(status.equals("Загрузка персонажей записи…")||status.equals("Загрузка геометрии записи…")||status.equals("Загрузка объектов карты…"))status="Новая система: прямой выбор времени";
                    if(timeline.playing||timeline.seeking)scenePlayback.prefetch(timeline.target,timeline.speed);
                    timeline.advance(elapsed,256,scenePlayback);ReplayClock.seconds=timeline.position/1e9;
                    world.completeIndexedView(scenePlayback.selectedFrame!=null);
                    camera.externalView=Boolean.TRUE.equals(directorHook("camera.director-active.v1"));camera.recorded(game,world.local);scenePlayback.camera(camera);
                    camera.move(frame.deltaSeconds());if(sceneAudio!=null&&!Boolean.getBoolean("sz.replay.qa.scene-audio-off")){if(!camera.externalView)sceneAudio.listener(camera.pose);sceneAudio.present(timeline.position,!timeline.playing||timeline.seeking,timeline.speed,timeline.selectionRevision);}if(System.nanoTime()-lastStateNs>500_000_000L)writeState();
                    return;
                }
                timeline.advance(elapsed,256,world,System::nanoTime,timeline.seeking?12_000_000L:3_000_000L);ReplayClock.seconds=timeline.position/1e9;
                world.sourceExtractorQa.step(game,world);
                world.audio.clockLag(Math.max(0,timeline.target-timeline.position),50_000_000L);
                world.audio.sync(sceneCapture!=null||!timeline.playing||timeline.seeking||reconstruct||world.audio.catchup||!world.bootstrapped,timeline.speed);
                if(reconstruct)world.audio.reconstruct(ReplayClock.seconds);
                world.audio.reconstructAuthored(ReplayClock.seconds);
                world.audio.commit();
                if(sceneAudio!=null)sceneAudio.sample(timeline.position);
                if(sceneEffects!=null)sceneEffects.sample(timeline.position);
                if(sceneBootstrapping&&world.bootstrapped&&(world.bootstrapOnly||!timeline.seeking&&timeline.position==demo.durationNs)&&world.resourcesReady()&&(sceneCapture==null||sceneCapture.finished)&&(sceneEffects==null||sceneEffects.ready())&&(!world.bootstrapOnly||sceneAudio.resourcesReady()))activateScene();
                camera.externalView=Boolean.TRUE.equals(directorHook("camera.director-active.v1"));
                camera.recorded(game,world.local);
                if(Boolean.getBoolean("sz.replay.qa.followself")&&camera.following==-1&&world.local.player!=null)camera.follow(world.local.player);
                if(sceneValuesQa!=null){sceneValuesQa.step(demo,world,timeline);if(sceneValuesQa.visible()!=null)camera.follow(sceneValuesQa.binding.entity());}
                world.sourceExtractorQa.view(camera);camera.move(frame.deltaSeconds());if(System.nanoTime()-lastStateNs>500_000_000L)writeState();

            }
            // A paused seek to the beginning can leave only the offline
            // connection open, before the first world bootstrap packet. The
            // loading screen has no video.before-post hook; controls must
            // remain reachable there to resume or choose another timestamp.
            if(panel&&probeWorld==null&&(world==null||!world.bootstrapped))draw();
            if(Boolean.getBoolean("sz.replay.qa.input")&&System.nanoTime()-lastStateNs>500_000_000L)writeState();
        }catch(Exception|LinkageError e){fail(e);}
        }finally{ReplayPerformance.exit("replay-frame");ReplayPerformance.preparing=false;try{frameProfile.end(frameBegan,timeline==null?0:timeline.position,timeline==null?0:timeline.target,timeline==null?0:timeline.cursor,context.configDirectory(),timeline!=null&&!timeline.playing&&!timeline.seeking);}finally{frameActive=false;}}
    }
    static String root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.toString();}
    void beginWorldProbe()throws Exception {
        if(demo==null||probeWorld!=null)return;
        codecs.preflight();ReplaySessions.leaveGame(game);
        if(!Boolean.getBoolean("sz.replay.agent"))throw new IllegalStateException("Агент реплеев не установлен. Пересоберите мод с -Install и перезапустите клиент.");
        if(codecs.adapters==null)throw new IllegalStateException("World probe requires an audited source profile");
        probeReport=new JsonObject();probeReport.addProperty("demo",demo.path.toString());probeReport.addProperty("world_application_verified",false);probeReport.addProperty("pre_world_packets_applied",false);
        probeReport.addProperty("compatibility_fingerprint",demo.compatibilityFingerprint);
        probeCursor=0;probeApplied=0;probeBootstrapped=false;probeWorld=new NativePackets.World(game,codecs);
        camera.reset();probeReport.addProperty("in_progress",true);Files.writeString(context.configDirectory().resolve("world-audit.json"),probeReport.toString());
        try{ReplayClock.begin();probeWorld.reset();status="Проверка создания офлайн-мира";}catch(Exception|LinkageError e){finishWorldProbe(e);}
    }
    void worldProbeStep()throws Exception {
        // Use the same session reconstruction and gameplay dispatcher as playback.
        for(int count=0;count<2&&probeCursor<demo.events.size();probeCursor++){
            try{if(!probeWorld.ready())return;}catch(Exception|LinkageError e){finishWorldProbe(e);return;}
            DemoFile.Event event=demo.events.get(probeCursor);
            if(probeCursor%50==0){probeReport.addProperty("next_event",probeCursor);probeReport.addProperty("next_packet",event.sourceId());probeReport.addProperty("applied_packets",probeApplied);Files.writeString(context.configDirectory().resolve("world-audit.json"),probeReport.toString());}
            count++;
            try{probeWorld.apply(event);if(probeWorld.lastApplied)probeApplied++;if(event.sourceId()==11)probeBootstrapped=true;
                probeReport.addProperty("native_world_created",Native.call(game,"throws","1054393445")!=null);
            }catch(Exception|LinkageError e){probeReport.addProperty("native_world_created",Native.call(game,"throws","1054393445")!=null);probeReport.addProperty("failed_event",probeCursor);probeReport.addProperty("failed_packet",event.sourceId());if(event.sourceId()==700)probeReport.add("motion_entity_bindings",probeWorld.describeMotionEntities(Motion.read(event.payload())));finishWorldProbe(e);return;}
        }
        if(probeCursor==demo.events.size())finishWorldProbe(null);
    }
    void finishWorldProbe(Throwable error)throws Exception {
        probeReport.addProperty("in_progress",false);
        probeReport.addProperty("applied_packets",probeApplied);probeReport.addProperty("error",error==null?"":root(error));
        if(probeWorld!=null){probeReport.addProperty("motion_updates_applied",probeWorld.motion.updates);probeReport.addProperty("motion_unloaded_entities_skipped",probeWorld.motion.skippedUnloaded);probeReport.addProperty("motion_snapshots_queued",probeWorld.motion.queued);probeReport.addProperty("motion_tails_immediate",probeWorld.motion.immediate);probeReport.addProperty("motion_tail_bytes_applied",codecs.adapters.appliedTailBytes);probeReport.addProperty("motion_components_applied",codecs.adapters.appliedTailComponents);}
        if(probeWorld!=null){probeReport.addProperty("motion_missing_entity_updates",probeWorld.motion.skippedMissing);probeReport.addProperty("motion_non_actor_updates",probeWorld.motion.skippedNonActor);probeReport.add("motion_missing_entity_ids",new Gson().toJsonTree(probeWorld.motion.missingEntities));probeReport.add("motion_non_actor_classes",new Gson().toJsonTree(probeWorld.motion.nonActorClasses));}
        if(probeWorld!=null){probeReport.addProperty("motion_missing_entity_tail_bytes",probeWorld.motion.missingTailBytes);probeReport.addProperty("motion_pending_chunk_entity_updates",probeWorld.motion.pendingEntityUpdates);}
        if(probeWorld!=null){probeReport.add("source_only_attribute_occurrences",new Gson().toJsonTree(codecs.adapters.sourceOnlyOccurrences));probeReport.add("source_only_attribute_last_values",new Gson().toJsonTree(codecs.adapters.sourceOnlyLastValues));probeReport.addProperty("stale_local_reload_updates_filtered",codecs.adapters.staleLocalReloads);}
        if(probeWorld!=null){probeReport.addProperty("local_frames_applied",probeWorld.local.frames);probeReport.addProperty("local_positions_applied",probeWorld.local.positions);probeReport.addProperty("local_looks_applied",probeWorld.local.looks);probeReport.addProperty("local_mouse_trace_bytes_preserved",probeWorld.local.inputTraceBytes);probeReport.addProperty("local_mouse_trace_records_verified",probeWorld.local.inputTraceRecords);probeReport.addProperty("local_mouse_extended_aim_records",probeWorld.local.inputTraceAim);probeReport.addProperty("weapon_display_snapshots_applied",codecs.adapters.appliedGunDetails);probeReport.addProperty("source_motif_progress_applied",codecs.adapters.sourceMotifProgress);}
        if(error!=null){java.io.StringWriter trace=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(trace));probeReport.addProperty("stack",trace.toString());}
        status=error==null?"World probe completed; visual equivalence remains unverified":"World probe: "+root(error);
        if(probeWorld!=null){probeReport.addProperty("sessions_reconstructed",probeWorld.sessions);probeReport.addProperty("connection_setup_packets_archived",probeWorld.preWorldSkipped);}
        boolean review=error==null&&Boolean.getBoolean("sz.replay.review");
        if(review){world=probeWorld;probeWorld=null;timeline=Timeline.afterApplied(demo.events,probeCursor);status="Проверка управления: визуальное соответствие записи ещё не подтверждено";probeReport.addProperty("controls_review_open",true);}
        else try{if(probeWorld!=null)probeWorld.close();}catch(Exception e){probeReport.addProperty("cleanup_error",root(e));}finally{probeWorld=null;camera.reset();ReplayClock.end();}
        Files.writeString(context.configDirectory().resolve("world-audit.json"),probeReport.toString());context.log(status);writeState();
    }
    void fail(Throwable e){if(sceneCapture!=null)sceneCapture.abort(e);String next=root(e);boolean changed=!next.equals(lastError);lastError=next;status=lastError;if(timeline!=null)timeline.playing=false;if(changed){context.log("Replay stopped: "+lastError);try{var trace=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(trace));Files.writeString(context.configDirectory().resolve("last-failure.txt"),trace.toString());}catch(Exception diagnostic){e.addSuppressed(diagnostic);}}try{writeState();}catch(Exception ignored){}}
    ReplayStateWriter stateWriter;
    NativePackets.World diagnosticWorld;JsonObject frozenDiagnostics;
    void writeState()throws Exception {
        ReplayPerformance.enter("state-snapshot");try{
        boolean frozen=scenePlayback!=null&&diagnosticWorld==world&&frozenDiagnostics!=null;
        JsonObject state=frozen?frozenDiagnostics.deepCopy():new JsonObject();state.addProperty("status",status);state.addProperty("error",lastError);state.addProperty("loaded",demo!=null);state.addProperty("world_started",world!=null);
        if(scenePlayback!=null&&scenePlayback.fpCatalog!=null)state.add("fp_catalog",new Gson().toJsonTree(scenePlayback.fpCatalog.diagnostic()));state.addProperty("preparing_resources",scenePlayback!=null&&scenePlayback.preparingResources());state.addProperty("camera_subject",cameraSubject);state.addProperty("cinematic_active",camera.externalView);
        if(scenePlayback!=null)state.add("scene_values",new Gson().toJsonTree(scenePlayback.residentValues.diagnostic()));
        if(scenePlayback!=null&&scenePlayback.actorCatalog!=null)state.add("actor_catalog",new Gson().toJsonTree(scenePlayback.actorCatalog.diagnostic()));
        if(scenePlayback!=null&&scenePlayback.actorCatalog!=null)state.add("actor_assets",new Gson().toJsonTree(scenePlayback.actors.assets.diagnostic()));
        if(scenePlayback!=null&&scenePlayback.terrainCatalog!=null)state.add("terrain_catalog",new Gson().toJsonTree(scenePlayback.terrainCatalog.diagnostic()));
        if(scenePlayback!=null&&scenePlayback.mapCatalog!=null){state.add("map_catalog",new Gson().toJsonTree(scenePlayback.mapCatalog.diagnostic()));state.add("map_assets",new Gson().toJsonTree(scenePlayback.map.assets.diagnostic()));}
        if(scenePlayback!=null&&scenePlayback.map!=null){state.addProperty("map_index_ready",scenePlayback.mapIndexReady);state.addProperty("map_index_rows",scenePlayback.map.preparation.completePrepared);}
        if(scenePlayback!=null&&scenePlayback.map!=null&&scenePlayback.map.views!=null)state.add("map_views",new Gson().toJsonTree(scenePlayback.map.views.diagnostic()));
        if(scenePlayback!=null)state.add("native_ui",new Gson().toJsonTree(nativeUi.diagnostic()));
        if(demo!=null){state.addProperty("demo",demo.path.toString());state.addProperty("events",demo.events.size());state.addProperty("duration_ns",demo.durationNs);state.addProperty("blocker",demo.playbackBlocker());state.addProperty("compatibility_fingerprint",demo.compatibilityFingerprint);}
        if(timeline!=null){state.addProperty("position_ns",timeline.position);state.addProperty("target_ns",timeline.target);state.addProperty("lag_ns",Math.max(0,timeline.target-timeline.position));state.addProperty("next_event",timeline.cursor);state.addProperty("playing",timeline.playing);state.addProperty("seeking",timeline.seeking);state.addProperty("speed",timeline.speed);}
        if(sceneCapture!=null)state.add("scene_track_preparation",new Gson().toJsonTree(sceneCapture.diagnostic()));
        if(sceneValuesQa!=null)state.add("scene_values_qa",new Gson().toJsonTree(sceneValuesQa.diagnostic()));
        state.addProperty("indexed_bootstrap",sceneBootstrapping);state.addProperty("indexed_playback",scenePlayback!=null);
        if(sceneLoading!=null){state.addProperty("bootstrap_position_ns",sceneLoading.preparation.position);state.addProperty("bootstrap_target_ns",sceneLoading.preparation.target);}
        if(scenePlayback!=null)state.add("indexed_scene",new Gson().toJsonTree(scenePlayback.diagnostic()));
        if(sceneAudio!=null)state.add("indexed_audio",new Gson().toJsonTree(sceneAudio.diagnostic()));
        if(sceneEffects!=null)state.add("indexed_effects",new Gson().toJsonTree(sceneEffects.diagnostic()));
        if(world!=null&&!frozen){JsonObject stats=new JsonObject();stats.addProperty("sessions",world.sessions);stats.addProperty("motion_updates_applied",world.motion.updates);stats.addProperty("motion_missing_entity_updates",world.motion.skippedMissing);stats.addProperty("motion_pending_chunk_entity_updates",world.motion.pendingEntityUpdates);stats.addProperty("motion_missing_entity_tail_bytes",world.motion.missingTailBytes);stats.addProperty("local_frames_applied",world.local.frames);stats.addProperty("motion_tail_bytes_applied",codecs.adapters.appliedTailBytes);stats.addProperty("motion_tail_bytes_pending",ReplayDecode.pendingTailBytes(codecs.adapters));stats.addProperty("motion_tail_bytes_discarded",codecs.adapters.discardedTailBytes);stats.addProperty("stale_local_reload_updates_filtered",codecs.adapters.staleLocalReloads);stats.add("source_only_attribute_occurrences",new Gson().toJsonTree(codecs.adapters.sourceOnlyOccurrences));stats.add("source_only_attribute_last_values",new Gson().toJsonTree(codecs.adapters.sourceOnlyLastValues));state.add("world_stats",stats);}
        if(camera.pose!=null){JsonObject pose=new JsonObject();pose.addProperty("x",camera.pose.p().x());pose.addProperty("y",camera.pose.p().y());pose.addProperty("z",camera.pose.p().z());pose.addProperty("yaw",camera.yaw);pose.addProperty("pitch",camera.pitch);state.add("camera",pose);}
        if(camera.pose!=null){JsonObject c=state.getAsJsonObject("camera");c.addProperty("following_entity",camera.following);c.addProperty("recorded_anchor",camera.recordedAnchor);c.addProperty("right_mouse",camera.rightMouse);c.addProperty("input_blocked",camera.mouseBlocked);c.addProperty("dragging",camera.dragging);c.addProperty("panel_capture",panelCapture);}
        state.addProperty("native_hud_hidden",ReplayClock.active);
        if(Boolean.getBoolean("sz.replay.qa.input")){
            state.addProperty("raw_cursor_x",camera.mouseX);state.addProperty("raw_cursor_y",camera.mouseY);
            Object activeWorld=game==null?null:Native.call(game,"throws","1054393445");
            if(activeWorld!=null)state.addProperty("native_world_time",((Number)Native.call(activeWorld,"if","long")).longValue());
        }
        if(world!=null){
            state.addProperty("local_position_received",world.local.positioned);
            if(Boolean.getBoolean("sz.replay.qa.input")&&world.local.player!=null){
                Object self=world.local.player;
                JsonObject recorder=new JsonObject();recorder.addProperty("class",self.getClass().getName());
                recorder.addProperty("id",((Number)Native.call(self,"for","int")).intValue());
                recorder.addProperty("tp_body_submissions",camera.recorderBodyDraws);
                recorder.addProperty("tp_actor_submissions",camera.recorderActorDraws);
                recorder.addProperty("spectator_visible",ReplayRender.recorderVisible);
                recorder.addProperty("camera_perspective",Native.call(camera.camera,"goto","1308185038").toString());
                recorder.addProperty("in_native_render_collection",((Collection<?>)Native.call(Native.field(game,"g"),"volatile","java.util.Collection")).contains(self));
                Object actor=Native.call(Native.type("1478690128"),"new","1579323378",self);
                recorder.addProperty("tp_component",actor!=null);
                if(actor!=null){Object nativeGraph=Native.call(actor,"static","1805188833");recorder.addProperty("tp_ready",(boolean)Native.call(nativeGraph,"private","boolean"));}
                Object controller=Native.call(Native.type("1704556748"),"return","1704556748",self);
                Object motion=Native.call(controller,"if","1943200621");recorder.addProperty("movement_speed",((Number)Native.call(motion,"this","float")).floatValue());
                recorder.addProperty("movement_delta",((Number)Native.field(motion,"assert")).floatValue());
                recorder.addProperty("movement_stance",Native.call(motion,"throw","430277821").toString());
                state.add("recorder",recorder);
            }
            LocalMotion.CameraAnchor anchor=world.local.cameraAnchor;
            if(anchor!=null){JsonObject source=new JsonObject();source.addProperty("x",anchor.pose().p().x());source.addProperty("y",anchor.pose().p().y());source.addProperty("z",anchor.pose().p().z());source.addProperty("yaw",anchor.yaw());source.addProperty("pitch",anchor.pitch());source.addProperty("current_player",anchor.player()==camera.player);state.add("local_camera_anchor",source);}
        }
        if(world!=null)state.add("resource_fallbacks",new Gson().toJsonTree(ReplayRender.resources()));
        if(world!=null&&!frozen){JsonObject stats=state.getAsJsonObject("world_stats");
            stats.addProperty("connection_controls_archived",world.connectionControlsArchived);
            stats.addProperty("recorded_world_epoch_ms",ReplayClock.wallMillis());
            stats.addProperty("capture_visual_clock_recorded",demo.captureClockRecorded);
            stats.addProperty("capture_visual_origin_ns",demo.captureMonotonicOriginNs);
            stats.addProperty("visual_clock_ns",ReplayClock.visualNanos());
            stats.add("source_weather_configuration",world.weatherProgram.summary());
            stats.addProperty("source_resource_configs",demo.sourceResourceCount());stats.addProperty("source_config_bundle_sha256",demo.sourceConfigBundleHash);
            stats.addProperty("remains_pose_cache_isolated",world.bodyPoses.bound);stats.addProperty("remains_pose_cache_generation",world.bodyPoses.generation);stats.addProperty("remains_pose_cache_entries",world.bodyPoses.entries());
            stats.addProperty("remains_preparation_count",world.bodyPoses.preparationCount);stats.add("remains_preparations",new Gson().toJsonTree(world.bodyPoses.preparations));
            stats.add("remains_physics",new Gson().toJsonTree(world.bodyPoses.physicsSnapshot()));
            stats.add("actor_clocks",new Gson().toJsonTree(world.actorTimes.snapshot()));
            if(world.simulationClock!=null){stats.addProperty("simulation_frontiers",world.simulationFrontiers);stats.add("simulation_clock",new Gson().toJsonTree(ReplayClock.timerSnapshot(world.simulationClock)));}
            if(tickLoop!=null)stats.add("native_tick_loop",new Gson().toJsonTree(tickLoop.snapshot()));
            if(Boolean.getBoolean("sz.replay.qa.tailtrace"))stats.add("npc_tail_trace",new Gson().toJsonTree(world.actorTimes.traceSnapshot()));
            if(Boolean.getBoolean("sz.replay.qa.graphbarrier")){
                stats.addProperty("npc_parameter_invalidations",world.actorTimes.invalidatedNpcParameters);stats.addProperty("npc_paused_pose_updates",world.actorTimes.settledNpcPoses);
                stats.addProperty("npc_graph_ready_checks",world.actorTimes.graphReadyChecks);stats.addProperty("npc_graph_wait_checks",world.actorTimes.graphWaitChecks);
                stats.addProperty("npc_graph_main_queue_pumps",world.actorTimes.graphQueuePumps);
                stats.addProperty("npc_graph_waiting",world.actorTimes.graphWaits.size());stats.add("npc_graph_admissions",new Gson().toJsonTree(world.actorTimes.graphAdmissions));
            }
            stats.addProperty("weather_blend_updates",world.environment.updates);stats.addProperty("weather_blend_synthetic_fixture",world.environment.synthetic);stats.add("weather_blend_timeline",new Gson().toJsonTree(world.environment.blend.timeline().entrySet().stream().collect(java.util.stream.Collectors.toMap(e->((Enum<?>)e.getKey()).name(),Map.Entry::getValue))));
            stats.addProperty("weather_zone_calls",ReplayRender.weatherZoneCalls);stats.addProperty("weather_blend_calls",ReplayRender.weatherBlendCalls);stats.addProperty("weather_blend_strength",ReplayRender.weatherBlendStrength);
            stats.add("source_timeline",world.sourceTimeline.snapshot());
            stats.add("source_electric_storm",world.sourceElectric.snapshot());
            stats.add("source_resource_extractors",world.sourceExtractors.snapshot());
            if(world.sourceExtractorQa.enabled)stats.add("source_extractor_scene_qa",world.sourceExtractorQa.snapshot(world));
            stats.addProperty("weather_blend_electric",world.environment.blend.electric());
            if(ReplayRender.testInput()&&demo.hashes.has("569545768")&&demo.hashes.has("648055939"))stats.add("rendered_weather_data",world.environment.renderedState());
            JsonObject audio=new JsonObject();audio.addProperty("events",world.audio.events);audio.addProperty("channels",world.audio.channels);audio.addProperty("paused_voices",world.audio.pausedVoices);audio.addProperty("paused",world.audio.paused);audio.addProperty("speed",world.audio.speed);audio.addProperty("expired_voices",world.audio.expired);audio.addProperty("seek_positions_applied",world.audio.repositioned);audio.addProperty("seek_finished_voices",world.audio.finished);
            if(Boolean.getBoolean("sz.replay.qa.input"))audio.add("voices",new Gson().toJsonTree(world.audio.details()));state.add("audio",audio);
            stats.addProperty("settled_snapshot_components",world.settledComponents);stats.addProperty("advanced_snapshot_components",world.advancedComponents);
            if(Boolean.getBoolean("sz.replay.qa.input"))stats.add("pending_motion_tails",new Gson().toJsonTree(ReplayDecode.pendingTailDetails(codecs.adapters)));
            stats.addProperty("motion_tail_bytes_received",world.motion.receivedTailBytes);
            stats.addProperty("motion_non_actor_tail_bytes",world.motion.nonActorTailBytes);
            long accounted=codecs.adapters.appliedTailBytes+ReplayDecode.pendingTailBytes(codecs.adapters)+codecs.adapters.discardedTailBytes+world.motion.missingTailBytes+world.motion.nonActorTailBytes;
            stats.addProperty("motion_tail_bytes_accounted",accounted);stats.addProperty("motion_tail_accounting_complete",accounted==world.motion.receivedTailBytes);
            stats.addProperty("primary_quest_hint_id",QuestHints.id(world.questHints.primary));stats.addProperty("secondary_quest_hint_id",QuestHints.id(world.questHints.secondary));stats.addProperty("active_secondary_quest_id",world.questHints.activeSecondary());
            stats.addProperty("primary_quest_hint_draws",world.questHints.primaryDraws);stats.addProperty("secondary_quest_hint_draws",world.questHints.secondaryDraws);
            stats.addProperty("source_gun_displays_applied",codecs.adapters.appliedGunDetails);stats.addProperty("source_motif_progress_applied",codecs.adapters.sourceMotifProgress);stats.add("source_motif_progress_snapshots",new Gson().toJsonTree(codecs.adapters.motifProgressSnapshots));
            if(world.bootstrapped&&Boolean.getBoolean("sz.replay.qa.weaponprobe"))stats.add("native_weapon_probe",new Gson().toJsonTree(NativeWeaponProbe.snapshot(world)));
            stats.addProperty("effect_render_time_seconds",ReplayClock.effectRenderSeconds);stats.addProperty("effect_render_clock_calls",ReplayClock.effectRenderCalls);
            stats.addProperty("motion_pending_entities_activated",world.motion.activatedPendingEntities);stats.addProperty("motion_pending_admission_failed",world.motion.failedPendingActivations);stats.add("motion_missing_entity_ids",new Gson().toJsonTree(world.motion.missingEntities));
            stats.add("motion_missing_entity_details",new Gson().toJsonTree(world.motion.missingEntityDetails));
            stats.addProperty("motion_hidden_remains_updates",world.motion.hiddenRemainsUpdates);
            stats.addProperty("motion_pending_entity_updates",world.motion.pendingEntityUpdates);stats.addProperty("motion_pending_chunk_entity_updates",world.motion.missingChunkUpdates);
            stats.addProperty("recorded_ping_ms",world.latency.pingMillis);stats.addProperty("recorded_ping_samples",world.latency.samples);stats.addProperty("unmatched_ping_responses",world.latency.unmatched);
        }
        if(Boolean.getBoolean("sz.replay.qa.input")){state.add("qa_input",new Gson().toJsonTree(ReplayRender.inputDiagnostic()));state.addProperty("qa_bootstrap_transformed",Boolean.getBoolean("sz.replay.transform.StandardJvmBootstrap"));state.addProperty("qa_backend_transformed",Boolean.getBoolean("sz.replay.transform.533013651"));if(qaUiSample!=null)for(var sample:qaUiSample.entrySet())state.add(sample.getKey(),sample.getValue().deepCopy());}
        if(stateWriter==null)stateWriter=new ReplayStateWriter(context.configDirectory().resolve("state.json"));
        if(frozen){
            JsonObject stats=state.getAsJsonObject("world_stats");stats.addProperty("visual_clock_ns",ReplayClock.visualNanos());stats.addProperty("recorded_world_epoch_ms",ReplayClock.wallMillis());
            stats.addProperty("effect_render_time_seconds",ReplayClock.effectRenderSeconds);stats.addProperty("effect_render_clock_calls",ReplayClock.effectRenderCalls);
            JsonObject audio=state.getAsJsonObject("audio");audio.addProperty("paused",world.audio.paused);audio.addProperty("speed",world.audio.speed);
        }
        // Reconstruction diagnostics are immutable once direct playback starts.
        // Refresh selected scene/clock/camera values above; serialize this private
        // tree on the writer, never native objects or shared mutable maps.
        if(scenePlayback!=null&&!frozen){diagnosticWorld=world;frozenDiagnostics=state.deepCopy();}
        stateWriter.submit(state::toString);
        lastStateNs=System.nanoTime();
        }finally{ReplayPerformance.exit("state-snapshot");}
    }
    void start()throws Exception {
        if(demo==null)return;String blocker=demo.playbackBlocker();if(blocker!=null){status=blocker;writeState();return;}
        if(pendingStart)return;
        // A verified demo still requires the installed replay agent's clock hooks.
        if(!Boolean.getBoolean("sz.replay.agent"))throw new IllegalStateException("Агент реплеев не установлен. Пересоберите мод с -Install и перезапустите клиент.");
        codecs.preflight();
        if(System.getProperty("sz.replay.prepare-scene","").isBlank()){
            String selectedScene=System.getProperty("sz.replay.scene","");Path scenePath=selectedScene.isBlank()?SceneTrackArchive.beside(demo.path):Path.of(selectedScene);
            if(!Files.isRegularFile(scenePath))throw new java.io.IOException("У записи нет подготовленной сцены. Подготовка записи выполняется отдельно от просмотра.");
            SceneAudioArchive.requirePrepared(demo.path,demo.manifest.get("event_stream_sha256").getAsString(),demo.compatibilityFingerprint,demo.durationNs);
        }
        ReplaySessions.leaveGame(game);
        pendingStart=true;pendingStartFrames=2;status="Открытие реплея…";writeState();
    }
    void beginPlayback()throws Exception {
        beginPlayback(false);
    }
    void beginPlayback(boolean prepare)throws Exception {
        playbackLastNs=System.nanoTime();
        lastError="";auditing=false;codecs=new NativePackets(demo);world=new NativePackets.World(game,codecs);timeline=new Timeline(demo.events);timeline.playing=true;ReplayClock.begin();
        // Playback starts directly; there is no replay-wide asset prepass.
        if(Boolean.getBoolean("sz.replay.qa.tickloop")){
            tickLoop=new ReplayTickLoop(timeline,world,()->{Native.call(world.simulationClock,"import","void");if(ReplayClock.failure!=null)throw new java.io.IOException("Native replay timer continuation failed",ReplayClock.failure);});
            ReplayRender.worldTicks=tickHandler;
        }
        status="Просмотр реплея";
        String capture=System.getProperty("sz.replay.prepare-scene","");sceneCapture=capture.isBlank()?null:new NativeSceneCapture(Path.of(capture),demo);
        world.sceneCapture=sceneCapture;
        String values=System.getProperty("sz.replay.qa.scene-values","");sceneValuesQa=values.isBlank()?null:new NativeSceneValuesQa(Path.of(values),context.configDirectory().resolve("scene-values-qa.json"));
        String selectedScene=System.getProperty("sz.replay.scene","");
        Path scenePath=selectedScene.isBlank()?SceneTrackArchive.beside(demo.path):Path.of(selectedScene);
        if(sceneCapture!=null&&Boolean.getBoolean("sz.replay.prepare-scene.play")){
            sceneBootstrapping=true;sceneAudio=new NativeSceneAudio(world.audio,demo);
            if(Boolean.parseBoolean(System.getProperty("sz.replay.effects-index","true")))sceneEffects=new NativeSceneEffects(world,demo.path.resolveSibling(demo.path.getFileName()+".effect-tracks"));
            status="Подготовка офлайн-сцены, звука и эффектов…";
        }
        if(sceneCapture==null&&sceneValuesQa==null&&Files.isRegularFile(scenePath)){
            preparedScene=SceneTrackArchive.read(scenePath,demo);sceneBootstrapping=true;sceneAudio=new NativeSceneAudio(world.audio,demo);
            if(Boolean.parseBoolean(System.getProperty("sz.replay.effects-index","true")))sceneEffects=new NativeSceneEffects(world,demo.path.resolveSibling(demo.path.getFileName()+".effect-tracks"));
            if(sceneEffects==null||sceneEffects.cached){timeline.playing=false;timeline.seek(demo.durationNs);}status="Подготовка офлайн-сцены, звука и эффектов…";
        }
        if(sceneBootstrapping){sceneLoading=new SceneLoading(timeline);timeline=sceneLoading.intent;}
    }
    void activateScene()throws Exception {
        if(sceneCapture!=null){if(sceneCapture.aborted||!sceneCapture.written)throw new java.io.IOException("Scene preparation did not complete");preparedScene=SceneTrackArchive.read(sceneCapture.output,demo);sceneCapture=null;world.sceneCapture=null;}
        if(sceneEffects!=null)sceneEffects.finish();
        sceneAudio.finish(demo.durationNs);world.audio.finish();
        world.actorTimes.indexed=true;world.actorTimes.rendering=false;
        NativeScenePlayback candidate=new NativeScenePlayback(preparedScene,world,sceneAudio);candidate.effects=sceneEffects;scenePlayback=candidate;nativeUi.bind(Native.field(Native.type("1402131154"),"h"));preparedScene=null;sceneBootstrapping=false;
        if(ReplayRender.worldTicks==tickHandler)ReplayRender.worldTicks=null;tickLoop=null;
        long first=SceneInitialTime.choose(scenePlayback.tracks);
        if(sceneLoading!=null){timeline=sceneLoading.activate(SceneInitialTime.floor(scenePlayback.tracks),first,scenePlayback.tracks.durationNs);sceneLoading=null;}
        else {timeline=new Timeline(demo.events,SceneInitialTime.floor(scenePlayback.tracks),scenePlayback.tracks.durationNs);timeline.seek(first);timeline.playing=true;}
        playbackLastNs=System.nanoTime();status="Новая система: прямой выбор времени";
    }
    public void registerHud(HudRegistry r){r.register("timeline",(hud,w,h,p)->{if(panel)try{draw();}catch(Exception e){fail(e);}});}
    void closeIndexed()throws Exception {
        Exception failure=null;
        try{if(sceneEffects!=null)sceneEffects.close();}catch(Exception e){failure=e;}finally{sceneEffects=null;}
        try{if(sceneAudio!=null)sceneAudio.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{sceneAudio=null;}
        try{if(scenePlayback!=null)scenePlayback.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{scenePlayback=null;}
        try{if(preparedScene!=null)preparedScene.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{preparedScene=null;sceneBootstrapping=false;sceneLoading=null;}
        if(failure!=null)throw failure;
    }
    void libraryView(float height){
        if(imgui.ImGui.button("Выбрать файл…"))choose();imgui.ImGui.sameLine();
        if(imgui.ImGui.button("Обновить список"))library.refresh();
        if(!library.error.isEmpty())imgui.ImGui.textWrapped(library.error);
        imgui.ImGui.beginChild("replay-files",0,height,true);
        try{
            if(library.entries.isEmpty())imgui.ImGui.text(library.refreshing.get()?"Поиск реплеев…":"В этой папке пока нет реплеев .demo");
            for(ReplayLibrary.Entry entry:library.entries){
                if(!entry.error().isEmpty()){imgui.ImGui.textWrapped(entry.path().getFileName()+": "+entry.error());continue;}
                String label=entry.path().getFileName()+" · "+String.format(java.util.Locale.ROOT,"%.1f с",entry.durationNs()/1e9)+"###replay-file-"+entry.path();
                if(imgui.ImGui.selectable(label,demo!=null&&demo.path.equals(entry.path())))opens.add(new OpenRequest(entry.path(),true));
            }
        }finally{imgui.ImGui.endChild();}
    }
    static String time(long nanos){long s=Math.max(0,nanos)/1_000_000_000L;return String.format(java.util.Locale.ROOT,"%02d:%02d",s/60,s%60);}
    void draw()throws Exception {
        if(drawnFrame)return;drawnFrame=true;panelCapture=false;
        if(org.lwjgl.glfw.GLFW.glfwGetCurrentContext()==0)return;
        if(world!=null&&(boolean)Native.call(Native.field(Native.type("1546247182"),"k"),"transient","boolean"))return;
        if(world!=null&&Native.call(game,"float","622415611")!=null)return;
        Native.call(Native.call(Native.type("976516049"),"try","976516049"),"throw","void");
        if(!placedPanel){imgui.ImGui.setNextWindowSize(730,650,imgui.flag.ImGuiCond.Once);placedPanel=true;}
        if(!imgui.ImGui.begin("Реплеи###network-replay")){imgui.ImGui.end();return;}
        try{
            imgui.ImGui.textWrapped(lastError.isEmpty()?status:"Не удалось продолжить просмотр. Подробности — в диагностике.");
            if(imgui.ImGui.button("Скрыть (F12)"))panel=false;
            if(world!=null){imgui.ImGui.sameLine();if(imgui.ImGui.button("Другой реплей…"))browse=!browse;}
            if(world==null||browse)libraryView(world==null?150:110);
            if(demo==null){imgui.ImGui.text("Выберите реплей из списка или откройте файл .demo");return;}
            imgui.ImGui.separator();imgui.ImGui.text(demo.path.getFileName().toString());
            String blocker=demo.playbackBlocker();
            if(blocker!=null&&world==null){imgui.ImGui.textWrapped(blocker);}
            else{
                if(imgui.ImGui.button(world==null?"Открыть реплей":timeline.playing?"Пауза":"Продолжить"))actions.add(()->{if(world==null)start();else timeline.togglePlayback();});
                if(world!=null){
                    imgui.ImGui.sameLine();if(imgui.ImGui.button("Закрыть реплей"))actions.add(()->{stop();status="Реплей закрыт";writeState();});
                    if(scenePlayback!=null)imgui.ImGui.textWrapped(scenePlayback.limitations());
                    if(sceneBootstrapping)imgui.ImGui.text("Офлайн-сцена создаётся один раз при открытии; перемотка после этого не запускает пакеты.");
                    imgui.ImGui.text(time(timeline.position)+" / "+time(demo.durationNs));
                    if(!scrubbing)scrubSeconds=(float)(timeline.position/1e9);
                    float[] seconds={scrubSeconds};if(imgui.ImGui.sliderFloat("Время",seconds,(float)(timeline.start/1e9),(float)(timeline.duration/1e9))){scrubSeconds=seconds[0];scrubbing=true;if(scenePlayback!=null){long selected=(long)(scrubSeconds*1e9);actions.add(()->timeline.seek(selected));}}
                    if(scrubbing&&imgui.ImGui.isItemDeactivatedAfterEdit()){long selected=(long)(scrubSeconds*1e9);actions.add(()->timeline.seek(selected));scrubbing=false;}
                    imgui.ImGui.text(String.format(java.util.Locale.ROOT,"Скорость: %.2f×",timeline.speed));
                    for(double rate:new double[]{.1,.25,.5,1,2,4,8,16}){
                        imgui.ImGui.sameLine();if(imgui.ImGui.button(String.format(java.util.Locale.ROOT,"%s×",rate==Math.rint(rate)?Integer.toString((int)rate):Double.toString(rate))))timeline.speed(Math.copySign(rate,timeline.speed));
                    }
                    float[] speed={(float)Math.abs(timeline.speed)};if(imgui.ImGui.sliderFloat("Точная скорость",speed,.05f,16))timeline.speed(Math.copySign(speed[0],timeline.speed));
                    if(scenePlayback!=null){if(imgui.ImGui.button(timeline.speed<0?"Вперёд":"Назад"))timeline.speed(-timeline.speed);imgui.ImGui.sameLine();if(imgui.ImGui.button("Кадр назад")){timeline.playing=false;timeline.seek(timeline.position-16_666_667L);}imgui.ImGui.sameLine();if(imgui.ImGui.button("Кадр вперёд")){timeline.playing=false;timeline.seek(timeline.position+16_666_667L);}}
                    if(sceneAudio!=null&&scenePlayback!=null&&imgui.ImGui.checkbox("Звук",sceneAudio.enabled))sceneAudio.enabled=!sceneAudio.enabled;
                    if(timeline.seeking)imgui.ImGui.text(scenePlayback!=null?"Подготовка выбранного кадра…":"Восстановление мира…");
                    if(imgui.ImGui.button("−5 секунд"))timeline.seek(timeline.position-5_000_000_000L);imgui.ImGui.sameLine();if(imgui.ImGui.button("+5 секунд"))timeline.seek(timeline.position+5_000_000_000L);
                    imgui.ImGui.text("Камера: ПКМ + WASD, Q/E; Shift — быстрее");
                    if((camera.following!=-1||cameraSubject>=0||camera.externalView)&&imgui.ImGui.button("Свободная камера")){camera.following=-1;cameraSubject=-1;cameraFirstPerson=false;cameraSelection++;}
                    imgui.ImGui.textWrapped("Персонаж камеры — привязка кинематографической камеры; F6 редактор, F7 включить/выключить.");
                    if(cameraFirstPerson&&scenePlayback!=null)imgui.ImGui.textWrapped(scenePlayback.firstPerson.status);
                    Object filmedPlayer=scenePlayback==null?camera.player:scenePlayback.recorder();
                    if(filmedPlayer!=null){
                        if(imgui.ImGui.button("К снимающему игроку"))camera.visit(filmedPlayer);
                        imgui.ImGui.sameLine();if(imgui.ImGui.button("Следить за ним"))camera.follow(filmedPlayer);
                        imgui.ImGui.sameLine();if(imgui.ImGui.button("Персонаж камеры###director-self"))selectCameraActor(filmedPlayer);
                        imgui.ImGui.sameLine();if(imgui.ImGui.button("Вселиться / FP###inhabit-self"))inhabitCameraActor(filmedPlayer);
                    }
                    if(imgui.ImGui.collapsingHeader("Игроки")){
                        imgui.ImGui.beginChild("replay-players",0,100,true);
                        try{
                            Object nativeWorld=Native.call(game,"throws","1054393445");
                            if(nativeWorld!=null)for(Object entity:(java.util.Collection<?>)Native.call(nativeWorld,"volatile","java.util.Collection")){
                                if(!Native.type("200241649").isInstance(entity))continue;
                                int id=ReplayRender.sceneCameraTarget(entity);String name=(String)Native.call(entity,"return","java.lang.String");
                                if(imgui.ImGui.button("К игроку: "+name+"###camera-player-"+id))camera.visit(entity);
                                imgui.ImGui.sameLine();if(imgui.ImGui.button("Следить###follow-player-"+id))camera.follow(entity);
                                imgui.ImGui.sameLine();if(imgui.ImGui.button("Персонаж камеры###director-player-"+id))selectCameraActor(entity);
                                imgui.ImGui.sameLine();if(imgui.ImGui.button("Вселиться / FP###inhabit-player-"+id))inhabitCameraActor(entity);
                            }
                        }finally{imgui.ImGui.endChild();}
                    }
                }
            }
            if(imgui.ImGui.collapsingHeader("Диагностика")){
                imgui.ImGui.textWrapped(library.directory.toString());
                imgui.ImGui.text(demo.events.size()+" сетевых событий");
                if(!lastError.isEmpty())imgui.ImGui.textWrapped(lastError);
                if(world==null&&probeWorld==null){
                    if(auditing){imgui.ImGui.progressBar((float)auditCursor/demo.events.size());if(imgui.ImGui.button("Остановить проверку"))actions.add(()->auditing=false);}
                    else if(imgui.ImGui.button("Проверить декодеры"))actions.add(this::beginAudit);
                }
            }
        }finally{var io=imgui.ImGui.getIO();panelCapture=imgui.ImGui.isWindowHovered(imgui.flag.ImGuiHoveredFlags.RootAndChildWindows)||(imgui.ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows)&&imgui.ImGui.isAnyItemActive());if(Boolean.getBoolean("sz.replay.qa.input")){qaUiSample=new JsonObject();qaUiSample.addProperty("qa_mouse_x",io.getMousePosX());qaUiSample.addProperty("qa_mouse_y",io.getMousePosY());qaUiSample.addProperty("qa_button0",imgui.ImGui.isMouseDown(0));}imgui.ImGui.end();}
    }
    void stop()throws Exception {
        diagnosticWorld=null;frozenDiagnostics=null;nativeUi.close();closeIndexed();if(sceneValuesQa!=null){sceneValuesQa.close();sceneValuesQa=null;}sceneCapture=null;pendingStart=false;cameraSubject=-1;cameraFirstPerson=false;cameraSelection++;scrubbing=false;if(ReplayRender.worldTicks==tickHandler)ReplayRender.worldTicks=null;tickLoop=null;frameThread=null;auditing=false;ReplayClock.end();camera.reset();try{if(probeWorld!=null)probeWorld.close();if(world!=null)world.close();}finally{probeWorld=null;world=null;if(timeline!=null)timeline.playing=false;cursor.release();}}
    public void onInput(InputSnapshot input)throws Exception {
        if(world==null&&probeWorld==null)return;
        long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();if(window==0)return;
        boolean right=ReplayRender.testInput()?ReplayRender.testMouseDown(1):Boolean.TRUE.equals(input.mouseButtons().get(1));
        if(!ReplayRender.testInput()&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_FOCUSED)!=org.lwjgl.glfw.GLFW.GLFW_TRUE)right=false;
        boolean nativeScreen=ReplayRender.nativeUiCaptures(game);
        boolean panelVisible=panel&&!(boolean)Native.call(Native.field(Native.type("1546247182"),"k"),"transient","boolean");
        // Recompute on every input sample. A closed menu or hidden panel must
        // never leave the previous frame's global ImGui capture latched.
        camera.blockInput(nativeScreen,panelVisible&&panelCapture);
        if(nativeScreen)right=false;
        if(cursor.look(right&&!camera.mouseBlocked&&!camera.externalView))camera.dragging=false;
        double[] x={0},y={0};org.lwjgl.glfw.GLFW.glfwGetCursorPos(window,x,y);
        camera.input(input,x[0],y[0],right);
    }
    static Object directorHook(String hook)throws Exception {return Native.call(Native.type("szmods.runtime.ModLoader"),"clientHook","java.lang.Object",hook,(Object)new Object[0]);}
    void selectCameraActor(Object actor)throws Exception {cameraFirstPerson=false;camera.following=-1;cameraSubject=ReplayRender.sceneCameraTarget(actor);cameraSelection++;}
    void inhabitCameraActor(Object actor)throws Exception {selectCameraActor(actor);cameraFirstPerson=true;}
    Object selectedCameraActor()throws Exception {if(cameraSubject<0||world==null||!world.bootstrapped)return null;Object nativeWorld=Native.call(game,"throws","1054393445");return nativeWorld==null?null:Native.call(nativeWorld,"continue","1217489680",cameraSubject);}
    public Object onClientHook(String hook,Object...args)throws Exception {
        if(hook.equals("replay.ui-capture.v1"))return world==null?null:ReplayRender.nativeUiCaptures(game)||(panel&&panelCapture);
        if(hook.equals("replay.fp-subject.v1")){
            if(scenePlayback==null)return null;
            try{return scenePlayback.firstPerson.select(cameraFirstPerson?selectedCameraActor():null);}
            catch(Exception failure){String message="FP: "+failure;scenePlayback.firstPerson.status=message;if(!message.equals(lastError))context.log(message);lastError=message;return null;}
        }
        if(hook.equals("replay.fp-mode.v1"))return cameraFirstPerson;
        if(hook.equals("replay.fp-fov.v1"))return scenePlayback==null||!cameraFirstPerson?null:scenePlayback.firstPerson.fov();
        if(hook.equals("replay.fp-render-scope.v1"))return scenePlayback==null||args.length!=1?null:scenePlayback.firstPerson.renderScope(args[0]);
        if(hook.equals("replay.camera-selection.v1"))return world==null?null:new Object[]{selectedCameraActor(),cameraSelection};
        if(world==null&&probeWorld==null){
            return null;
        }
        if(hook.equals("video.before-post.v1")){if(sceneValuesQa!=null)try{sceneValuesQa.rendered(camera);}catch(Exception e){sceneValuesQa.close();sceneValuesQa=null;fail(e);}if(sceneCapture!=null&&world!=null&&!timeline.seeking&&timeline.position==timeline.target)try{sceneCapture.sample(world,timeline.position);}catch(Exception e){fail(e);sceneCapture=null;world.sceneCapture=null;}if(panel)draw();return null;}
        // Native loading can render before the next mod frame; attach the
        // spectator camera as soon as the recorded local entity exists.
        NativePackets.World current=world!=null?world:probeWorld;
        if(camera.player!=current.local.player||camera.sourceMotion!=current.local||camera.sourceEpoch!=current.local.cameraEpoch)
            camera.recorded(game,current.local);
        try{return camera.hook(hook,args);}catch(Exception e){
            fail(e);
            // Retain the offline world until the next explicit close. Disabling
            // this mod during native rendering would tear down the same world
            // that the rest of the current frame is still drawing.
            if(hook.equals("camera.native-view.v1")&&camera.pose!=null){camera.view(args[1]);return Boolean.TRUE;}
            return null;
        }
    }
    public void onUnload()throws Exception{
        if(stateWriter!=null)stateWriter.close();
        // JVM shutdown runs on a thread without the client's GL context.
        // GPU and engine owners are already retiring there; process exit frees
        // them. Explicit close/reload on the render thread still uses stop().
        if(org.lwjgl.glfw.GLFW.glfwGetCurrentContext()==0){ReplayClock.end();ReplayRender.scenePresentation=null;return;}
        stop();
    }
}

