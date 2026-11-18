package replay;

import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;

/** Explicit hidden-client probe of indexed presentation. Never a production player. */
final class NativeSceneValuesQa implements AutoCloseable {
    final Path tracksPath,reportPath;final NativeScenePresenter presenter=new NativeScenePresenter();
    SceneTracks tracks;SceneTracks.ObjectTrack recorder;NativeScenePresenter.Binding binding;
    final List<Map<String,Object>> frames=new ArrayList<>();final List<Long> times=new ArrayList<>();
    int cursor,hold,renderPasses;boolean initialized,finished;long positions,poses,environments,attributes;
    double[] originalPosition;float originalYaw,originalPitch;
    Object originalEntity;
    long mapReads,mapMeshes,terrainMeshes;
    Map<String,Object> factoryReport=Map.of();
    NativeSceneSelection selection;
    NativeSceneMapValuesQa mapValues;
    final boolean lifecycle=Boolean.getBoolean("sz.replay.qa.scene-lifecycle");
    final List<Long> selectionNanos=new ArrayList<>();boolean continuous;
    final List<Map<String,Long>> phaseNanos=new ArrayList<>();
    final List<Map<String,Long>> mapPhaseNanos=new ArrayList<>();
    SceneTracks.Frame selectedFrame;
    NativeSceneValuesQa(Path tracks,Path report){tracksPath=tracks;reportPath=report;ReplayRender.sceneSubmissionAudit=true;}
    Map<String,Object> diagnostic(){var result=new TreeMap<String,Object>();result.put("initialized",initialized);result.put("finished",finished);result.put("cursor",cursor);result.put("waiting",selection==null?presenter.waitingReason:selection.presenter.waitingReason);if(selection!=null)result.put("selection",selection.diagnostic());return result;}
    void step(DemoFile demo,NativePackets.World world,Timeline timeline)throws Exception {
        try{stepSelection(demo,world,timeline);}catch(Exception failure){
            finished=true;try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));Files.writeString(reportPath.resolveSibling("scene-values-failure.txt"),text.toString());throw failure;
        }
    }
    void stepSelection(DemoFile demo,NativePackets.World world,Timeline timeline)throws Exception {
        if(finished||!world.bootstrapped||timeline.playing||timeline.seeking||timeline.position!=demo.durationNs)return;
        if(!initialized){
            tracks=SceneTrackArchive.read(tracksPath,demo);
            if(lifecycle)selection=new NativeSceneSelection(world);
            if(Boolean.getBoolean("sz.replay.qa.scene-map-values"))mapValues=new NativeSceneMapValuesQa(Native.call(world.connection,"instanceof","1054393445"));
            if(Boolean.getBoolean("sz.replay.qa.scene-factory")){
                SceneTracks.ObjectTrack template=tracks.objects.stream().filter(o->o.channels().containsKey("spawn-event")).findFirst().orElseThrow();
                SceneTracks.ObjectState state=tracks.sample(template.bornNs()).objects().get(template.id());
                Object nativeWorld=Native.call(world.connection,"instanceof","1054393445");int before=((List<?>)Native.call(nativeWorld,"const","java.util.List")).size();Object original=Native.call(nativeWorld,"continue","1217489680",template.id().networkId());
                Object created=new NativeSceneEntityFactory(world.packets).create(nativeWorld,state,-1_800_000_000);
                if(((List<?>)Native.call(nativeWorld,"const","java.util.List")).size()!=before||Native.call(nativeWorld,"continue","1217489680",template.id().networkId())!=original||Native.call(nativeWorld,"continue","1217489680",-1_800_000_000)!=null)throw new IllegalStateException("Quarantined factory modified world admission");
                factoryReport=Map.of("verified",true,"class",created.getClass().getName(),"runtime_id",Native.call(created,"for","int"),"world_admission_unchanged",true,"packet_prefix_dispatched",false);
            }
            recorder=tracks.objects.stream().filter(o->o.type().equals("961798934")).findFirst().orElseThrow();
            Object entity=world.local.player;if(entity==null||((Number)Native.call(entity,"for","int")).intValue()!=recorder.id().networkId())throw new IllegalStateException("QA recorder incarnation unavailable");
            originalEntity=entity;
            binding=NativeScenePresenter.bindExisting(entity);if(binding==null)return;
            Object original=Native.call(Native.type("576005245"),"char","153304200",entity,1f);
            originalPosition=new double[]{NativeSceneCapture.number(original,"case"),NativeSceneCapture.number(original,"void"),NativeSceneCapture.number(original,"import")};
            originalYaw=((Number)Native.call(entity,"catch","float")).floatValue();originalPitch=((Number)Native.call(entity,"volatile","float")).floatValue();
            long start=recorder.channels().get("rig-layout").keys.get(0).timeNs();long end=recorder.deadNs()<0?demo.durationNs:recorder.deadNs()-1;
            for(int n=0;n<24;n++)times.add(start+(end-start)*n/23);
            Collections.shuffle(times,new Random(87654));List<Long> backwards=new ArrayList<>(times);Collections.reverse(backwards);times.addAll(backwards);
            if(lifecycle){long endPoint=end;for(int n=0;n<120;n++)times.add(endPoint-n*16_666_667L);for(int n=119;n>=0;n--)times.add(endPoint-n*16_666_667L);}
            initialized=true;
        }
        if(hold>0){hold--;return;}
        if(cursor>=times.size()){finished=true;close();write();return;}
        long time=times.get(cursor);SceneTracks.Frame all=tracks.sample(time);Map<SceneTracks.ObjectId,SceneTracks.ObjectState> selected=new TreeMap<>();
        for(var s:all.objects().values())if(s.id().equals(recorder.id())||s.type().equals("world-environment"))selected.put(s.id(),s);
        if(!selected.containsKey(recorder.id()))throw new IllegalStateException("QA sample outside recorder lifetime");
        if(lifecycle){long began=System.nanoTime();if(!selection.present(all,(s,c)->{if(mapValues!=null)mapValues.select(s,c);}))return;selectionNanos.add(System.nanoTime()-began);if(!selection.lastPhases.isEmpty())phaseNanos.add(selection.lastPhases);if(mapValues!=null&&mapValues.models!=null&&!mapValues.models.lastPhases.isEmpty())mapPhaseNanos.add(mapValues.models.lastPhases);}
        else {
            var candidate=presenter.prepareValues(new SceneTracks.Frame(time,selected),s->binding);
            if(candidate==null)throw new IllegalStateException("QA selection unexpectedly pending");
            if(mapValues!=null)mapValues.select(all,candidate);presenter.publish(candidate);
        }
        if(lifecycle)binding=selection.pool.selected.get(recorder.id()).binding;
        if(mapValues!=null)mapValues.publishQueries(visible());
        cursor++;
        selectedFrame=all;
        // Probe the spatial admission separately from immutable graph publication.
        // The canonical setter updates entity/bounds coordinates without a physics tick.
        double[] p=visible().positions.get(binding.entity());Native.call(binding.entity(),"throw","void",p[0],p[1],p[2]);
        SceneTracks.Value look=selected.get(recorder.id()).channels().get("look");if(look instanceof SceneTracks.Numbers n){double[] a=n.data();Native.call(binding.entity(),"class","void",(float)a[0],(float)a[1]);}
        positions=ReplayRender.scenePositionReads;poses=ReplayRender.scenePoseReads;environments=ReplayRender.sceneEnvironmentReads;attributes=ReplayRender.sceneAttributeReads;
        mapReads=ReplayRender.sceneMapManagerReads;mapMeshes=ReplayRender.sceneMapMeshSubmissions.get();terrainMeshes=ReplayRender.sceneTerrainMeshSubmissions.get();
        continuous=lifecycle&&cursor>48;hold=continuous?1:4;
    }
    void rendered(FreeCamera camera)throws Exception {
        try{renderedFrame(camera);}catch(Exception failure){var text=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(text));Files.writeString(reportPath.resolveSibling("scene-values-failure.txt"),text.toString());throw failure;}
    }
    void renderedFrame(FreeCamera camera)throws Exception {
        if(finished||visible()==null)return;renderPasses++;
        if(hold!=(continuous?0:1))return;
        NativeScenePresenter.Frame frame=visible();
        Map<String,Object> row=new TreeMap<>();row.put("selected_ns",frame.timeNs);row.put("native_position_reads_during_render",ReplayRender.scenePositionReads-positions);row.put("native_pose_reads_during_render",ReplayRender.scenePoseReads-poses);row.put("native_environment_reads_during_render",ReplayRender.sceneEnvironmentReads-environments);row.put("native_attribute_reads_during_render",ReplayRender.sceneAttributeReads-attributes);
        if(mapValues!=null&&mapValues.models!=null){
            row.put("native_map_manager_reads_during_render",ReplayRender.sceneMapManagerReads-mapReads);row.put("selected_map_mesh_submissions_during_render",ReplayRender.sceneMapMeshSubmissions.get()-mapMeshes);
            row.put("selected_terrain_mesh_submissions_during_render",ReplayRender.sceneTerrainMeshSubmissions.get()-terrainMeshes);
            if(frame.terrain!=null){Object original=Native.field(frame.terrainRenderer,"strictfp");if(ReplayRender.sceneTerrainGrid(frame.terrainRenderer,original)!=frame.terrain.grid)throw new IllegalStateException("Selected terrain draw grid changed after render");row.put("selected_terrain_cells",frame.terrain.selected.size());row.put("selected_terrain_view_updates",frame.terrain.updates);}
            if(Native.call(frame.mapWorld,"do","493563411")!=frame.mapManager)throw new IllegalStateException("Renderer map manager selection changed");
            Map<UUID,Object> registry=(Map<UUID,Object>)Native.call(frame.mapManager,"long","java.util.Map");
            if(registry.size()!=mapValues.models.visible.size())throw new IllegalStateException("Selected map registry changed after render");
            for(var entry:mapValues.models.visible.entrySet())if(registry.get(entry.getKey())!=entry.getValue())throw new IllegalStateException("Selected map object replaced after render");
            mapValues.assertOriginal();row.put("native_selected_map_registry_verified_after_render",true);
            if(camera.pose!=null)row.put("camera_pose",new double[]{camera.pose.p().x(),camera.pose.p().y(),camera.pose.p().z(),camera.yaw,camera.pitch});
            if(Boolean.getBoolean("sz.replay.qa.scene-capture")&&(cursor==1||cursor==49||cursor==169||cursor==288)){
                Object view=Native.call(Native.type("495112732"),"byte","353488815");List<Object> culled=new ArrayList<>();
                Native.call(Native.call(frame.mapManager,"float","1581944406"),"synchronized","void",culled,Native.call(view,"private","1598224942"));row.put("native_map_frustum_entries",culled.size());
                sceneScreenshot(reportPath.resolveSibling("scene-map-"+cursor+".png"));
            }
        }
        // Validate actual transformed getters only after counting renderer reads.
        Object position=Native.call(Native.type("576005245"),"char","153304200",binding.entity(),.37f);double[] expected=frame.positions.get(binding.entity());
        for(int n=0;n<3;n++)if(NativeSceneCapture.number(position,new String[]{"case","void","import"}[n])!=expected[n])throw new IllegalStateException("Native scene position getter disagrees");
        Object pose=Native.call(binding.graph(),"final","1118769848");if(pose!=frame.pose(binding.graph()))throw new IllegalStateException("Native scene pose getter disagrees");
        if(Native.call(Native.type("569545768"),"final","648055939")!=frame.environment())throw new IllegalStateException("Native scene environment getter disagrees");
        for(var e:frame.attributes.entrySet())if(Native.call(e.getKey(),"continue","java.lang.Object")!=e.getValue().decoded())throw new IllegalStateException("Native scene attribute getter disagrees");
        if(lifecycle){
            Collection<?> rendered=(Collection<?>)Native.call(selection.world,"volatile","java.util.Collection");
            if(rendered.size()!=frame.entities.size())throw new IllegalStateException("Native render collection does not match selected lifetime");
            for(var e:selection.pool.selected.entrySet()){
                Object entity=e.getValue().entity;
                NativeScenePresenter.Binding shown=frame.bindings.get(entity);if(shown==null)throw new IllegalStateException("Selected actor binding absent from published frame");
                if(!rendered.contains(entity)||Native.call(selection.world,"continue","1217489680",e.getKey().networkId())!=entity)throw new IllegalStateException("Selected incarnation lookup mismatch");
                double[] xyz=frame.positions.get(entity);Object actual=Native.call(Native.type("576005245"),"char","153304200",entity,.37f);
                for(int n=0;n<3;n++)if(NativeSceneCapture.number(actual,new String[]{"case","void","import"}[n])!=xyz[n])throw new IllegalStateException("Selected actor spatial mismatch");
                if(shown.graph()!=null&&frame.poses.containsKey(shown.graph())&&Native.call(shown.graph(),SceneNativeTemplate.supports(entity.getClass().getName())?"try":"final","1118769848")!=frame.pose(shown.graph()))throw new IllegalStateException("Selected actor pose mismatch");
                SceneTracks.ObjectState recorded=selectedFrame.objects().get(e.getKey());
                if(recorded.channels().get("rig-layout") instanceof SceneTracks.Bytes layout){
                    String key=new String(layout.data(),java.nio.charset.StandardCharsets.UTF_8);
                    if(!ScenePoseCodec.read(frame.pose(shown.graph())).equals(recorded.channels().get("bones/"+key)))throw new IllegalStateException("Native renderer modified selected bone arrays");
                }
                for(String role:SceneSecondaryRigs.ROLES){
                    String prefix="attachment/"+role+"/";
                    if(!(recorded.channels().get(prefix+"rig-layout") instanceof SceneTracks.Bytes layout)||layout.size()==0)continue;
                    String key=new String(layout.data(),java.nio.charset.StandardCharsets.UTF_8);
                    SceneSecondaryRigs.Rig rig=shown.secondary().get(role);
                    Object actualGraph=SceneSecondaryRigs.graphs(entity,NativeScenePresenter.graphIdentity(entity)).get(role);
                    if(rig==null||actualGraph==null||Native.call(actualGraph,"final","1118769848")!=frame.pose(actualGraph))throw new IllegalStateException("Selected attachment getter mismatch: "+role+" actor="+e.getKey()+" selected_ns="+frame.timeNs+" cursor="+cursor+" mapped_pose="+(actualGraph!=null&&frame.pose(actualGraph)!=null)+" published_graph="+(rig==null?"absent":graphDescription(rig.graph()))+" actual_graph="+graphDescription(actualGraph)+" expected="+key);
                    if(!ScenePoseCodec.read(frame.pose(actualGraph)).equals(recorded.channels().get(prefix+"bones/"+key)))throw new IllegalStateException("Native renderer modified attachment arrays: "+role);
                }
            }
            Set<Integer> aliveIds=new HashSet<>();for(var id:selection.pool.selected.keySet())aliveIds.add(id.networkId());
            for(var track:tracks.objects)if(!track.type().equals("world-environment")&&!aliveIds.contains(track.id().networkId())&&Native.call(selection.world,"continue","1217489680",track.id().networkId())!=null)throw new IllegalStateException("Absent incarnation remained visible");
            row.put("selected_incarnations",selection.pool.selected.keySet().stream().map(Object::toString).sorted().toList());row.put("native_lifecycle_and_actor_values_verified",true);
            row.put("native_bone_values_preserved_after_render",true);
        }
        row.put("canonical_getters_verified",true);frames.add(row);write();
    }
    void write()throws Exception {
        // This probe certifies native attachment round trips separately from bone poses.
        Map<String,Object> report=new TreeMap<>();report.put("finished",finished);report.put("native_full_scene_playback_verified",false);report.put("scope",lifecycle?"recorded actor lifetimes, primary and available attachment poses, positions, attributes and shader environment; optional map probes are reported separately; effects and audio are outside this probe":"recorder and environment presentation values only; lifecycle, map, effects and audio are outside this probe");report.put("recording",tracksPath.toString());report.put("selected_frames",frames);report.put("render_passes",renderPasses);report.put("packet_prefix_replayed_for_selection",false);report.put("visible_scene_renderer_complete",false);
        if(selection!=null)report.put("scene_selection",selection.diagnostic());
        report.put("canonical_equipment",Map.of("installed",SceneEquipmentRead.installed,"roundtrip_audited",SceneEquipmentRead.audited,"attachment_values",SceneEquipmentRead.attachmentValues));
        if(!phaseNanos.isEmpty())report.put("native_selection_phase_ns",phaseNanos);
        if(!mapPhaseNanos.isEmpty())report.put("native_map_phase_ns",mapPhaseNanos);
        if(mapValues!=null)report.put("map_values",mapValues.diagnostic());
        if(!selectionNanos.isEmpty()){
            List<Long> measured=new ArrayList<>(selectionNanos.subList(Math.min(48,selectionNanos.size()),selectionNanos.size()));
            if(!measured.isEmpty()){Collections.sort(measured);report.put("continuous_selection_count",measured.size());report.put("continuous_native_selection_p50_ms",measured.get(measured.size()/2)/1e6);report.put("continuous_native_selection_p99_ms",measured.get(Math.min(measured.size()-1,measured.size()*99/100))/1e6);report.put("selection_metrics_include_native_presentation",true);report.put("selection_metrics_include_map_qa_export_checks",mapValues!=null&&!mapValues.playbackProfile);report.put("selection_metrics_include_full_frame_render",false);}
        }
        report.put("quarantined_entity_factory",factoryReport);
        Files.writeString(reportPath,new Gson().toJson(report));
    }
    static String graphDescription(Object graph)throws Exception {
        if(graph==null)return "absent";
        Object skeleton=Native.call(graph,"strictfp","1612003613");Object pose=Native.call(graph,"szreplay$original$final","1118769848");
        return graph.getClass().getName()+"@"+System.identityHashCode(graph)+" skeleton="+(skeleton==null?"absent":NativeScenePresenter.sha(NativeSceneCapture.skeletonDefinition(skeleton)))+" bones="+(pose==null?-1:((Object[])Native.field(pose,"for")).length)+" curves="+(pose==null?-1:((float[])Native.field(pose,"strictfp")).length);
    }
    public void close()throws Exception {
        ReplayRender.sceneSubmissionAudit=false;
        presenter.close();try{if(selection!=null)selection.close();if(mapValues!=null)mapValues.close();if(originalPosition!=null){Native.call(originalEntity,"throw","void",originalPosition[0],originalPosition[1],originalPosition[2]);Native.call(originalEntity,"class","void",originalYaw,originalPitch);originalPosition=null;}}finally{if(tracks!=null)tracks.close();}
    }
    NativeScenePresenter.Frame visible(){return selection==null?presenter.current:selection.presenter.current;}
    static void sceneScreenshot(Path path)throws Exception {
        long window=((Number)Native.field(Native.type("StandardJvmBootstrap"),"controlWindow")).longValue();if(window==0)throw new IllegalStateException("QA window unavailable");int[] width={0},height={0};
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window,width,height);if(width[0]<=0||height[0]<=0)throw new IllegalStateException("QA framebuffer unavailable");
        var pixels=java.nio.ByteBuffer.allocateDirect(width[0]*height[0]*4);int previousFramebuffer=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING),previousBuffer=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_READ_BUFFER);
        try{org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,0);org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_FRONT);org.lwjgl.opengl.GL11.glReadPixels(0,0,width[0],height[0],org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,pixels);}
        finally{org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,previousFramebuffer);org.lwjgl.opengl.GL11.glReadBuffer(previousBuffer);}
        var image=new java.awt.image.BufferedImage(width[0],height[0],java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height[0];y++)for(int x=0;x<width[0];x++){int offset=((height[0]-1-y)*width[0]+x)*4;image.setRGB(x,y,0xff000000|((pixels.get(offset)&255)<<16)|((pixels.get(offset+1)&255)<<8)|(pixels.get(offset+2)&255));}
        javax.imageio.ImageIO.write(image,"png",path.toFile());
    }
}

