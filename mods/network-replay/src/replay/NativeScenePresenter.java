package replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Publishes selected character/environment values, without editing live graph worker buffers.
 * This is a presentation component, not a complete scene player: map, entity lifecycle,
 * attachments, effects and audio must be published by the enclosing scene renderer. */
final class NativeScenePresenter implements AutoCloseable {
    record Binding(Object entity,Object graph,SceneTracks.Bytes skeleton,Map<Integer,Object> attributes,Map<String,SceneSecondaryRigs.Rig> secondary) {
        Binding(Object entity,Object graph,SceneTracks.Bytes skeleton,Map<Integer,Object> attributes){this(entity,graph,skeleton,attributes,Map.of());}
        Binding {Objects.requireNonNull(entity);attributes=Collections.unmodifiableMap(new HashMap<>(attributes));secondary=Map.copyOf(secondary);}
    }
    interface Resolver {
        /** null means the selected incarnation's resources are still being prepared. */
        Binding resolve(SceneTracks.ObjectState state)throws Exception;
    }
    record AttributeValue(SceneTracks.Bytes encoded,String codec,Class<?> type,SceneAttributeValues.Value value){Object decoded(){return value.get();}}
    record AttributeBundle(Binding binding,Map<String,SceneTracks.Value> source,Map<Object,AttributeValue> values){}
    static final class AttributeFrameMap extends AbstractMap<Object,AttributeValue> {
        final SceneDomainMap<Object,AttributeValue> root;
        final IdentityHashMap<Object,AttributeValue> materialized=new IdentityHashMap<>();
        AttributeFrameMap(SceneDomainMap<Object,AttributeValue> root){this.root=root;}
        public int size(){return root.size();}
        public AttributeValue get(Object key){
            AttributeValue template=root.get(key);if(template==null||template.value.reusable())return template;
            synchronized(materialized){return materialized.computeIfAbsent(key,unused->new AttributeValue(template.encoded,template.codec,template.type,new SceneAttributeValues.Value(template.value.source,null,false)));}
        }
        public boolean containsKey(Object key){return root.containsKey(key);}
        public Set<Entry<Object,AttributeValue>> entrySet(){return new AbstractSet<>(){
            public int size(){return root.size();}
            public Iterator<Entry<Object,AttributeValue>> iterator(){var entries=root.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return entries.hasNext();}public Entry<Object,AttributeValue> next(){var entry=entries.next();return Map.entry(entry.getKey(),get(entry.getKey()));}};}
        };}
    }
    interface PoseAliases {Object resolve(Object graph)throws Exception;}
    static final class Frame implements ReplayRender.ScenePresentation {
        final long timeNs;
        final IdentityHashMap<Object,double[]> positions;
        final IdentityHashMap<Object,Object> poses;
        final Map<Object,Object> poseAliases=Collections.synchronizedMap(new IdentityHashMap<>());
        long aliasEpoch=-1;
        PoseAliases aliasResolver=graph->null;
        final Map<Object,AttributeValue> attributes;
        Map<SceneTracks.ObjectId,AttributeBundle> attributeBundles=Map.of();
        final Object environment;
        Object world;
        List<Object> entities;
        Map<Integer,Object> entityIds;
        final IdentityHashMap<Object,Integer> sourceIds=new IdentityHashMap<>();
        final IdentityHashMap<Object,Binding> bindings=new IdentityHashMap<>();
        final IdentityHashMap<Object,Object> bodyRenderers=new IdentityHashMap<>();
        Object chunkProvider,emptyChunk;
        Map<SceneMapIndex.Coordinate,Object> chunks;
        Object mapWorld,mapManager;
        Object terrainRenderer;NativeSceneTerrainSelection terrain;
        SceneClockCodec.Clock clocks;
        Long day;
        boolean lightingReady;
        Set<Object> mapObjects=Set.of();
        Map<Object,SceneNativeMapViews.Spatial> mapSpatial=Map.of();
        final IdentityHashMap<Object,double[]> looks=new IdentityHashMap<>();
        Frame(long time,IdentityHashMap<Object,double[]> positions,IdentityHashMap<Object,Object> poses,
              Map<Object,AttributeValue> attributes,Object environment){
            timeNs=time;this.positions=positions;this.poses=poses;this.attributes=attributes;this.environment=environment;
        }
        public Object position(Object entity){
            double[] p=positions.get(entity);if(p==null)return null;
            // Native position helpers return a caller-owned vector; callers may modify it.
            try{return Native.type("153304200").getConstructor(double.class,double.class,double.class).newInstance(p[0],p[1],p[2]);}
            catch(Exception failure){throw new IllegalStateException("Native scene position layout changed",failure);}
        }
        public Object pose(Object graph){
            Object pose=poses.get(graph);if(pose!=null)return pose;
            synchronized(poseAliases){
                if(aliasEpoch!=ReplayRender.sceneRenderEpoch){poseAliases.clear();aliasEpoch=ReplayRender.sceneRenderEpoch;}
                if(poseAliases.containsKey(graph))return poseAliases.get(graph);
                try{pose=aliasResolver.resolve(graph);}catch(Exception failure){throw new IllegalStateException("Cannot resolve selected scene graph replacement",failure);}
                poseAliases.put(graph,pose);return pose;
            }
        }
        public Object bodyPose(Object renderer){return bodyRenderers.get(renderer);}
        public Object environment(){return environment;}
        public Object attribute(Object attribute,Object original){AttributeValue selected=attributes.get(attribute);return selected==null?original:selected.decoded();}
        public Collection<?> entities(Object owner,Collection<?> original){return owner==world&&entities!=null?entities:original;}
        public Object entity(Object owner,int id,Object original){return owner==world&&entityIds!=null?entityIds.get(id):original;}
        public Integer sourceId(Object entity){return sourceIds.get(entity);}
        public boolean actorVisible(Object renderer,Object entity){return renderer==terrainRenderer&&sourceIds.containsKey(entity);}
        public Float look(Object entity,int axis){double[] look=looks.get(entity);return look==null?null:(float)look[axis];}
        public boolean simulationFrozen(){return world!=null;}
        public Object chunk(Object provider,int x,int z){return provider==chunkProvider&&chunks!=null?chunks.getOrDefault(new SceneMapIndex.Coordinate(x,z),emptyChunk):null;}
        public Object mapManager(Object owner){return owner==mapWorld?mapManager:null;}
        public boolean ownsMapObject(Object prefab){return mapObjects.contains(prefab);}
        public Object prefabSpatial(Object prefab,int slot,Object original){var selected=mapSpatial.get(prefab);return selected==null?original:selected.field(slot);}
        public Object terrainGrid(Object renderer,Object original){return terrain==null?original:terrain.grid(renderer,original);}
        public boolean updateTerrain(Object grid,Object view)throws Exception {return terrain!=null&&terrain.update(grid,view);}
        public boolean ownsTerrainCell(Object cell){return terrain!=null&&terrain.selected.contains(cell);}
        public Integer drawTerrain(Object grid,Object view,int pass)throws Exception {return terrain==null?null:terrain.draw(grid,view,pass);}
        public long timeNs(){return timeNs;}
        public Long visualNanos(){return clocks==null?null:clocks.visual(timeNs);}
        public Long environmentNanos(){return clocks==null?null:clocks.environment(timeNs);}
        public Long wallMillis(){return clocks==null?null:clocks.wall(timeNs);}
        public Long dayTicks(){return day;}
        public void beginRender()throws Exception {
            if(lightingReady||day==null||world==null)return;
            Object shader=Native.field(Native.type("569545768"),"assert");
            // The native sun calculation is retained, but its input is the selected
            // track frame rather than a character's world or a live weather zone.
            Native.call(shader,"long","void",Native.field(shader,"catch"),((day+46666.66666666667)/160000.0)%1.0);
            Object sun=Native.field(shader,"catch"),direction=Native.field(shader,"finally");
            Native.call(direction,"try","1987590071",sun);
            if(((Number)Native.field(direction,"byte")).doubleValue()<=0)Native.call(direction,"static","1987590071");
            Native.call(direction,"double","1987590071");lightingReady=true;

        }
    }
    record RigLayout(String key,String[] identity) {}
    final SceneIdentityAliases<RigLayout> layouts=new SceneIdentityAliases<>(2048);
    final SceneIdentityAliases<String> attributeCodecs=new SceneIdentityAliases<>(2048);
    final SceneAttributeValues attributeValues=new SceneAttributeValues();
    RigLayout layout(SceneTracks.Bytes bytes)throws IOException {
        RigLayout cached=layouts.get(bytes);if(cached!=null)return cached;
        String key=new String(bytes.data(),StandardCharsets.UTF_8);String[] identity=key.split(":",-1);
        if(identity.length!=2||!identity[0].matches("[0-9a-f]{64}")||!identity[1].matches("[0-9]+"))throw new IOException("Invalid scene rig layout");
        try{Integer.parseInt(identity[1]);}catch(NumberFormatException failure){throw new IOException("Invalid scene rig curve count",failure);}
        RigLayout parsed=new RigLayout(key,identity);layouts.put(bytes,parsed);return parsed;
    }
    Frame current;long publications,waiting;String waitingReason="";final SceneSkeletonIdentity skeletonIdentity=new SceneSkeletonIdentity(2048);
    /** Validate and build everything off-screen; a failed selection preserves the published frame. */
    synchronized boolean presentValues(SceneTracks.Frame selected,Resolver resolver)throws Exception {
        Frame candidate=prepareValues(selected,resolver);if(candidate==null)return false;
        publish(candidate);return true;
    }
    synchronized Frame prepareValues(SceneTracks.Frame selected,Resolver resolver)throws Exception {
        waitingReason="";
        IdentityHashMap<Object,double[]> positions=new IdentityHashMap<>();IdentityHashMap<Object,Object> poses=new IdentityHashMap<>();
        SceneDomainMap<Object,AttributeValue> attributeRoot=current!=null&&current.attributes instanceof AttributeFrameMap map?map.root:SceneDomainMap.identityKeys();
        var attributeEdit=attributeRoot.edit();Map<SceneTracks.ObjectId,AttributeBundle> oldBundles=current==null?Map.of():current.attributeBundles;
        var bundles=new HashMap<SceneTracks.ObjectId,AttributeBundle>();var liveIds=new HashSet<SceneTracks.ObjectId>();for(var state:selected.presentationObjects())liveIds.add(state.id());
        for(var entry:oldBundles.entrySet())if(!liveIds.contains(entry.getKey()))for(Object attribute:entry.getValue().values.keySet())attributeEdit.remove(attribute);
        IdentityHashMap<Object,double[]> looks=new IdentityHashMap<>();Object environment=null;
        SceneClockCodec.Clock clocks=null;Long day=null;
        List<Binding> owners=new ArrayList<>();
        IdentityHashMap<Object,Object> bodyRenderers=new IdentityHashMap<>();
        for(SceneTracks.ObjectState state:selected.presentationObjects()){
            if(state.type().startsWith("world-map-"))continue;
            if(state.type().equals("world-environment")){
                if(environment!=null)throw new IOException("Two environments in one selected scene");
                if(state.channels().containsKey("clocks"))clocks=SceneClockCodec.decode(state.channels().get("clocks"));
                if(state.channels().containsKey("day-ticks"))day=(long)SceneEnvironmentCodec.numbers(state.channels().get("day-ticks"),1)[0];
                Map<String,SceneTracks.Value> shader=new TreeMap<>();for(var e:state.channels().entrySet())if(e.getKey().startsWith("shader/"))shader.put(e.getKey().substring(7),e.getValue());
                ReplayPerformance.enter("scene-weather-values");try{environment=SceneEnvironmentCodec.compose(shader);}finally{ReplayPerformance.exit("scene-weather-values");}continue;
            }
            Binding binding=resolver.resolve(state);if(binding==null){waiting++;waitingReason="actor "+state.id();return null;}
            owners.add(binding);
            if(positions.containsKey(binding.entity()))throw new IOException("Two scene incarnations share one native entity");
            double[] position=SceneEnvironmentCodec.numbers(state.channels().get("position"),3);positions.put(binding.entity(),position);
            if(state.channels().containsKey("look"))looks.put(binding.entity(),SceneEnvironmentCodec.numbers(state.channels().get("look"),2));
            SceneTracks.Value layout=state.channels().get("rig-layout");
            if(layout!=null){
                if(!(layout instanceof SceneTracks.Bytes bytes)||binding.graph()==null||binding.skeleton()==null)throw new IOException("Missing selected native rig binding");
                RigLayout parsed=layout(bytes);String key=parsed.key;String[] identity=parsed.identity;

                SceneTracks.Value definition=state.channels().get("skeleton/"+identity[0]);
                if(!skeletonIdentity.matches(binding.skeleton(),definition,identity[0]))throw new IOException("Selected/native skeleton identity mismatch");
                if(!(state.channels().get("bones/"+key) instanceof SceneTracks.Bones bones))throw new IOException("Selected scene pose is missing");
                if(bones.curveCount()!=Integer.parseInt(identity[1]))throw new IOException("Selected rig curve count mismatch");
                Object pose=ScenePoseCodec.create(bones);
                ScenePoseCodec.write(pose,bones);
                if(poses.put(binding.graph(),pose)!=null)throw new IOException("Two scene entities share one native rig");
                Object renderer=SceneRemains.renderer(binding.entity());if(renderer!=null){
                    bodyRenderers.put(renderer,SceneRemains.view(pose));
                    // Human remains render the model graph, while their physical
                    // getter owns a separate scaled bone source. Publish both.
                    Object model=Native.call(renderer,"do","111523829");
                    if(Native.type("387875964").isInstance(model)){
                        Object renderPose=ScenePoseCodec.create(bones);ScenePoseCodec.write(renderPose,SceneRemains.renderBones(bones,((Number)Native.call(binding.entity(),"strictfp","float")).floatValue()));
                        if(poses.put(model,renderPose)!=null)throw new IOException("Shared corpse model graph");
                    }
                }
            }
            for(String role:SceneSecondaryRigs.ROLES){
                String prefix="attachment/"+role+"/";SceneTracks.Value descriptor=state.channels().get(prefix+"rig-layout");
                if(descriptor==null)continue;
                if(!(descriptor instanceof SceneTracks.Bytes bytes))throw new IOException("Invalid attachment rig descriptor");
                if(bytes.size()==0)continue;RigLayout parsed=layout(bytes);String key=parsed.key;String[] identity=parsed.identity;

                SceneSecondaryRigs.Rig rig=binding.secondary().get(role);if(rig==null){waiting++;waitingReason="attachment "+role+" actor "+state.id()+" available="+binding.secondary().keySet();return null;}
                if(!skeletonIdentity.matches(rig.skeleton(),state.channels().get(prefix+"skeleton/"+identity[0]),identity[0]))throw new IOException("Selected/native attachment skeleton identity mismatch: "+role+" actor="+state.id()+" time="+selected.timeNs()+" expected="+identity[0]+" actual="+sha(rig.skeleton()));
                if(!(state.channels().get(prefix+"bones/"+key) instanceof SceneTracks.Bones bones)||bones.curveCount()!=Integer.parseInt(identity[1]))throw new IOException("Missing attachment pose: "+role);
                Object pose=ScenePoseCodec.create(bones);ScenePoseCodec.write(pose,bones);
                if(poses.put(rig.graph(),pose)!=null)throw new IOException("Shared native attachment rig: "+role);
            }
            Map<String,SceneTracks.Value> source=state.heldAttributes()==null?state.channels():state.heldAttributes();AttributeBundle previous=oldBundles.get(state.id());
            if(previous!=null&&previous.binding==binding&&previous.source==source){bundles.put(state.id(),previous);continue;}
            boolean delta=previous!=null&&previous.binding==binding&&previous.source instanceof SceneTracks.AttributeRows&&source instanceof SceneTracks.AttributeRows&&previous.values instanceof SceneDomainMap;
            SceneDomainMap<Object,AttributeValue> prior=delta?(SceneDomainMap<Object,AttributeValue>)previous.values:SceneDomainMap.identityKeys();
            var values=prior.edit();var names=new LinkedHashSet<String>();
            if(delta){for(var change:SceneDomainMap.changes(((SceneTracks.AttributeRows)previous.source).rows,((SceneTracks.AttributeRows)source).rows)){
                String name=change.key();names.add(name.startsWith("attribute-codec/")?"attribute/"+name.substring(16):name);
            }}else{
                if(previous!=null)for(Object attribute:previous.values.keySet())attributeEdit.remove(attribute);
                for(String name:source.keySet())if(name.startsWith("attribute/"))names.add(name);
            }
            ReplayPerformance.enter("scene-attribute-values");try{for(String name:names){
                int id;try{id=Integer.parseInt(name.substring(10));}catch(NumberFormatException failure){throw new IOException("Invalid scene attribute ID",failure);}
                Object attribute=binding.attributes().get(id);if(attribute==null)throw new IOException("Native attribute missing: "+id);
                if(delta){attributeEdit.remove(attribute);values.remove(attribute);}
                SceneTracks.Value selectedAttribute=source.get(name);if(selectedAttribute==null)continue;
                if(!(selectedAttribute instanceof SceneTracks.Bytes encoded))throw new IOException("Invalid scene attribute encoding");
                String codec="native-v1";SceneTracks.Value descriptor=source.get("attribute-codec/"+id);
                if(descriptor!=null){if(!(descriptor instanceof SceneTracks.Bytes b))throw new IOException("Invalid scene attribute codec");codec=attributeCodecs.get(b);if(codec==null){codec=new String(b.data(),StandardCharsets.US_ASCII);attributeCodecs.put(b,codec);}}
                AttributeValue cached=previous==null?null:previous.values.get(attribute);
                Class<?> type=cached==null?(Class<?>)Native.field(attribute,"return"):cached.type();
                // The root stores a private validated template. AttributeFrameMap
                // creates a fresh mutable decoder result for each selected frame.
                boolean reusable=cached!=null&&cached.encoded().equals(encoded)&&cached.codec().equals(codec);
                AttributeValue value;
                if(reusable)value=cached;else {var prepared=attributeValues.select(type,codec,encoded);value=new AttributeValue(encoded,codec,type,prepared.reusable()?prepared:new SceneAttributeValues.Value(prepared.source,null,false));}
                if(attributeEdit.put(attribute,value)!=null||values.put(attribute,value)!=null)throw new IOException("Shared native attribute binding");
            }}finally{ReplayPerformance.exit("scene-attribute-values");}
            bundles.put(state.id(),new AttributeBundle(binding,source,values.finish()));
        }
        Frame candidate=new Frame(selected.timeNs(),positions,poses,new AttributeFrameMap(attributeEdit.finish()),environment);candidate.attributeBundles=Map.copyOf(bundles);
        candidate.bodyRenderers.putAll(bodyRenderers);
        candidate.clocks=clocks;
        candidate.day=day;
        candidate.looks.putAll(looks);
        for(Binding owner:owners)candidate.bindings.put(owner.entity(),owner);
        candidate.aliasResolver=new AliasIndex(owners,poses);
        return candidate;
    }
    record Alias(SceneTracks.Bytes skeleton,Object pose,Object owner) {}
    /** Build current ownership once per rendered frame, including negative lookup caching.
     * The next render refreshes membership while paused, so later asset completion is visible. */
    static final class AliasIndex implements PoseAliases {
        final List<Binding> owners;final IdentityHashMap<Object,Object> poses;
        final IdentityHashMap<Object,Alias> aliases=new IdentityHashMap<>();long epoch=-1;
        AliasIndex(List<Binding> owners,IdentityHashMap<Object,Object> poses){this.owners=List.copyOf(owners);this.poses=poses;}
        void add(Object graph,SceneTracks.Bytes skeleton,Object pose,Object owner)throws IOException {
            if(graph==null||skeleton==null||pose==null)return;
            Alias previous=aliases.put(graph,new Alias(skeleton,pose,owner));
            if(previous!=null&&previous.owner!=owner)throw new IOException("Replacement graph is shared by two scene actors");
        }
        public Object resolve(Object graph)throws Exception {
            if(epoch!=ReplayRender.sceneRenderEpoch){
                aliases.clear();
                for(Binding owner:owners){
                    Object primary=graphIdentity(owner.entity());
                    add(primary,owner.skeleton(),poses.get(owner.graph()),owner.entity());
                    Map<String,Object> current=SceneSecondaryRigs.graphs(owner.entity(),primary);
                    for(var entry:owner.secondary().entrySet())add(current.get(entry.getKey()),entry.getValue().skeleton(),poses.get(entry.getValue().graph()),owner.entity());
                }
                epoch=ReplayRender.sceneRenderEpoch;
            }
            Alias selected=aliases.get(graph);return selected==null?null:replacement(graph,selected.skeleton,selected.pose);
        }
    }
    /** Native asset/LOD completion can replace a graph after frame publication.
     * Resolve only a current model of the same selected actor and exact skeleton;
     * an unrelated graph with identical bone names must never inherit its pose. */
    static Object replacementPose(Object graph,List<Binding> owners,IdentityHashMap<Object,Object> poses)throws Exception {
        for(Binding owner:owners){
            Object primary=graphIdentity(owner.entity());
            if(primary==graph&&owner.skeleton()!=null&&poses.containsKey(owner.graph()))return replacement(graph,owner.skeleton(),poses.get(owner.graph()));
            Map<String,Object> current=SceneSecondaryRigs.graphs(owner.entity(),primary);
            for(var role:owner.secondary().entrySet())if(current.get(role.getKey())==graph&&poses.containsKey(role.getValue().graph()))return replacement(graph,role.getValue().skeleton(),poses.get(role.getValue().graph()));
        }
        return null;
    }
    static Object replacement(Object graph,SceneTracks.Bytes expected,Object pose)throws Exception {
        Object skeleton=Native.call(graph,"strictfp","1612003613");
        if(skeleton==null||!NativeSceneCapture.skeletonDefinition(skeleton).equals(expected))return null;
        // Inspect the already available native buffer, without evaluating a graph.
        Object buffer=Native.call(graph,"szreplay$original$final","1118769848");
        // A new graph has no evaluated buffer: indexed playback supplies its first
        // pose directly. Exact skeleton identity was checked above; never tick it.
        if(buffer!=null&&(((Object[])Native.field(buffer,"for")).length!=((Object[])Native.field(pose,"for")).length||((float[])Native.field(buffer,"strictfp")).length!=((float[])Native.field(pose,"strictfp")).length))return null;
        return pose;
    }
    synchronized void publish(Frame candidate)throws Exception {
        Frame previous=current;ReplayRender.ScenePresentation old=ReplayRender.scenePresentation;
        ReplayRender.scenePresentation=candidate;
        try{candidate.beginRender();current=candidate;publications++;}
        catch(Exception failure){current=previous;ReplayRender.scenePresentation=old;throw failure;}
    }
    static String sha(SceneTracks.Bytes bytes)throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.data()));}
    /** Resolve only a currently registered incarnation; lifecycle loading is a separate renderer step. */
    static Object graphIdentity(Object entity)throws Exception {
        if(Native.type("200241649").isInstance(entity)){
            Object component=Native.call(Native.type("1478690128"),"new","1579323378",entity);if(component==null)return null;
            Object controller=Native.call(component,"static","1805188833");return Native.field(controller,"short")==null?null:Native.call(controller,"super","387875964");
        }
        if(Native.type("1848416318").isInstance(entity)){Object rig=Native.field(entity,"native");if(rig==null)return null;Object controller=Native.call(rig,"short","1805188833");return Native.field(controller,"short")==null?null:Native.call(controller,"super","387875964");}
        if(SceneRemains.is(entity)){Object renderer=SceneRemains.renderer(entity);return renderer!=null?Native.field(renderer,"double"):null;}
        return null;
    }
    static Binding bindExisting(Object entity)throws Exception {
        Object graph=null;SceneTracks.Bytes definition=null;
        if(Native.type("200241649").isInstance(entity)){
            Object component=Native.call(Native.type("1478690128"),"new","1579323378",entity);if(component==null)return null;
            Object controller=Native.call(component,"static","1805188833");if(!(boolean)Native.call(controller,"private","boolean"))return null;
            graph=Native.call(controller,"super","387875964");
        }else if(Native.type("1848416318").isInstance(entity)){
            Object rig=Native.field(entity,"native");if(rig==null||!Native.type("859153379").isInstance(rig))return null;
            Object controller=Native.call(rig,"short","1805188833");if(!(boolean)Native.call(controller,"private","boolean"))return null;
            graph=Native.call(controller,"super","387875964");
        }else if(SceneRemains.is(entity)){
            Object renderer=Native.field(entity,"long");
            if(renderer!=null&&renderer.getClass().getName().equals("1326794892")){
                Object model=Native.call(renderer,"do","111523829");if(model==null)return null;Object skeleton=Native.call(model,"strictfp","1612003613");if(skeleton==null)return null;
                graph=Native.field(renderer,"double");definition=NativeSceneCapture.skeletonDefinition(skeleton);
            }
        }
        if(graph!=null&&definition==null)definition=NativeSceneCapture.skeletonDefinition(Native.call(graph,"strictfp","1612003613"));
        Map<Integer,Object> attributes=new TreeMap<>();Object nativeAttributes=Native.call(entity,"float","1536302723");
        for(Object a:(Iterable<?>)Native.call(Native.field(nativeAttributes,"this"),"while","1018845839")){
            int id=((Number)Native.field(a,"throw")).intValue();if(attributes.put(id,a)!=null)throw new IOException("Duplicate native attribute ID");
        }
        return new Binding(entity,graph,definition,attributes,SceneSecondaryRigs.bind(SceneSecondaryRigs.graphs(entity,graph)));
    }
    public synchronized void close(){if(ReplayRender.scenePresentation==current)ReplayRender.scenePresentation=null;current=null;skeletonIdentity.clear();layouts.clear();attributeCodecs.clear();attributeValues.clear();}
}

