package replay;

import java.util.*;

/** Immutable presentation tracks. Sampling never dispatches packets or steps simulation. */
public final class SceneTracks implements AutoCloseable {
    public enum Domain { LIFECYCLE, TRANSFORMS, POSES, APPEARANCE, MAP, ENVIRONMENT, EFFECTS, AUDIO, CAMERA_ATTACHMENTS }
    public enum Blend { HOLD, LINEAR, ANGLES, BONES }
    /** generation identifies an incarnation, independently of a recycled network ID. */
    public record ObjectId(int session,int networkId,int generation) implements Comparable<ObjectId> {
        public ObjectId {if(session<0||generation<0)throw new IllegalArgumentException("Invalid scene identity");}
        public int compareTo(ObjectId b){int n=Integer.compare(session,b.session);if(n==0)n=Integer.compare(networkId,b.networkId);return n==0?Integer.compare(generation,b.generation):n;}
    }
    public sealed interface Value permits Numbers,Bytes,Bones {}
    public static final class Numbers implements Value {
        private final double[] data;
        public Numbers(double...data){this.data=data.clone();if(data.length<1||data.length>65536)throw new IllegalArgumentException("Numeric track size");for(double n:data)if(!Double.isFinite(n))throw new IllegalArgumentException("Nonfinite track value");}
        public double[] data(){return data.clone();}
        public int size(){return data.length;}
        public boolean equals(Object o){return o instanceof Numbers n&&Arrays.equals(data,n.data);}
        public int hashCode(){return Arrays.hashCode(data);}
    }
    public static final class Bytes implements Value {
        private final byte[] data;
        private final int hash;
        private final int length;
        private final java.util.function.Supplier<byte[]> loader;
        public Bytes(byte[] data){if(data.length>32*1024*1024)throw new IllegalArgumentException("Binary track size");this.data=data.clone();hash=Arrays.hashCode(this.data);length=data.length;loader=null;}
        Bytes(int length,int hash,java.util.function.Supplier<byte[]> loader){if(length<0||length>32*1024*1024)throw new IllegalArgumentException("Binary track size");this.length=length;this.hash=hash;this.loader=Objects.requireNonNull(loader);data=null;}
        public byte[] data(){if(data!=null)return data.clone();byte[] result=loader.get();if(result.length!=length||Arrays.hashCode(result)!=hash)throw new IllegalStateException("Indexed binary value changed");return result;}
        public int size(){return length;}
        public boolean equals(Object o){if(this==o)return true;return o instanceof Bytes n&&length==n.length&&hash==n.hash&&Arrays.equals(data(),n.data());}
        public int hashCode(){return hash;}
    }
    /** Native bone positions, XYZW rotations, and curve values; no JNI/GPU handles. */
    public static final class Bones implements Value {
        private final float[] positions,rotations,curves;
        public Bones(float[] positions,float[] rotations,float[] curves){
            if(positions.length%3!=0||rotations.length/4!=positions.length/3||rotations.length%4!=0||positions.length>196608||curves.length>65536)throw new IllegalArgumentException("Skeletal track dimensions");
            this.positions=positions.clone();this.rotations=rotations.clone();this.curves=curves.clone();
            for(float[] a:new float[][]{positions,rotations,curves})for(float n:a)if(!Float.isFinite(n))throw new IllegalArgumentException("Nonfinite skeletal pose");
            for(int n=0;n<rotations.length;n+=4){double norm=0;for(int k=0;k<4;k++)norm+=(double)rotations[n+k]*rotations[n+k];if(norm<1e-12)throw new IllegalArgumentException("Zero bone rotation");}
        }
        private Bones(float[] positions,float[] rotations,float[] curves,boolean owned){this.positions=positions;this.rotations=rotations;this.curves=curves;}
        public float[] positions(){return positions.clone();} public float[] rotations(){return rotations.clone();} public float[] curves(){return curves.clone();}
        int boneCount(){return positions.length/3;}int curveCount(){return curves.length;}
        float position(int index){return positions[index];}float rotation(int index){return rotations[index];}
        void copyCurves(float[] target){System.arraycopy(curves,0,target,0,curves.length);}
        public long byteSize(){return 4L*(positions.length+rotations.length+curves.length);}
        public boolean equals(Object o){return o instanceof Bones b&&Arrays.equals(positions,b.positions)&&Arrays.equals(rotations,b.rotations)&&Arrays.equals(curves,b.curves);}
        public int hashCode(){return Arrays.hashCode(positions)*31+Arrays.hashCode(rotations)*17+Arrays.hashCode(curves);}
    }
    /** cut prevents interpolation INTO this key (teleport, rig change, discontinuity). */
    public record Key(long timeNs,Value value,boolean cut) {public Key {if(timeNs<0||value==null)throw new IllegalArgumentException("Invalid track key");}}
    public static final class Track {
        public final Domain domain;public final Blend blend;public final List<Key> keys;
        final long[] indexedTimes;
        final ScenePagedArchive.Keys pagedKeys;
        private volatile int selectedUpper=-1;
        private Value blendA,blendB;private boolean equalEndpoints;
        private BoneBlend boneBlend;
        private int upperBound(long time){
            int size=keys.size(),lo=selectedUpper;
            if(lo>=0&&(lo==0||time>=timeAt(lo-1))&&(lo==size||time<timeAt(lo)))return lo;
            lo=0;int hi=size;while(lo<hi){int mid=(lo+hi)>>>1;if(timeAt(mid)<=time)lo=mid+1;else hi=mid;}
            selectedUpper=lo;return lo;
        }
        // Only the two adjacent indexed keys survive between samples. This
        // keeps immutable endpoints resident without retaining track history.
        private int cachedA=-1,cachedB=-1;private Key keyA,keyB;
        private synchronized Key keyAt(int index){
            if(indexedTimes==null)return keys.get(index);
            if(index==cachedA)return keyA;if(index==cachedB)return keyB;
            Key key=keys.get(index);cachedB=cachedA;keyB=keyA;cachedA=index;keyA=key;return key;
        }
        public Track(Domain domain,Blend blend,List<Key> keys){
            this.domain=Objects.requireNonNull(domain);this.blend=Objects.requireNonNull(blend);this.keys=List.copyOf(keys);indexedTimes=null;pagedKeys=null;
            if(keys.isEmpty())throw new IllegalArgumentException("Empty scene track");long previous=-1;Value shape=keys.get(0).value();
            for(Key key:keys){if(key.timeNs()<=previous)throw new IllegalArgumentException("Unordered scene keys");previous=key.timeNs();validateShape(shape,key.value(),blend);}
        }
        Track(Domain domain,Blend blend,List<Key> keys,long[] times){
            this.domain=Objects.requireNonNull(domain);this.blend=Objects.requireNonNull(blend);this.keys=Collections.unmodifiableList(keys);indexedTimes=times.clone();pagedKeys=keys instanceof ScenePagedArchive.Keys p?p:null;
            if(times.length==0||times.length!=keys.size())throw new IllegalArgumentException("Invalid indexed track size");
            long previous=-1;for(long time:times){if(time<=previous)throw new IllegalArgumentException("Unordered indexed scene keys");previous=time;}
        }
        long timeAt(int index){return indexedTimes==null?keys.get(index).timeNs():indexedTimes[index];}
        boolean prepare(long time,long horizon,boolean urgent)throws java.io.IOException {
            if(pagedKeys==null)return true;
            int lo=upperBound(time);
            boolean ready=true;if(lo>0&&(pagedKeys.type[lo-1]==1||cachedA!=lo-1&&cachedB!=lo-1))ready&=pagedKeys.prepare(lo-1,urgent);
            if(lo<keys.size()&&lo>0&&blend!=Blend.HOLD&&time!=timeAt(lo-1)&&cachedA!=lo&&cachedB!=lo)ready&=pagedKeys.prepare(lo,urgent);
            if(horizon!=0){long end=time+horizon;int first=Math.max(0,lo-1),last=Math.min(keys.size()-1,lo);
                if(horizon>0){while(last+1<keys.size()&&timeAt(last)<=end)last++;}else {while(first>0&&timeAt(first)>=end)first--;}
                for(int n=first;n<=last;n++)pagedKeys.prepare(n,false);
            }return ready;
        }
        static void validateShape(Value a,Value b,Blend mode){
            if(mode==Blend.HOLD)return;
            if(mode==Blend.BONES){if(!(a instanceof Bones x)||!(b instanceof Bones y)||x.positions.length!=y.positions.length||x.curves.length!=y.curves.length)throw new IllegalArgumentException("Changing skeleton requires another track");}
            else if(!(a instanceof Numbers x)||!(b instanceof Numbers y)||x.data.length!=y.data.length)throw new IllegalArgumentException("Numeric track shape changed");
        }
        public Value sample(long time){
            int lo=upperBound(time);
            if(lo==0)return null;Key a=keyAt(lo-1);if(lo==keys.size()||time==a.timeNs()||blend==Blend.HOLD)return a.value();
            Key b=keyAt(lo);if(b.cut())return a.value();
            if(blendA!=a.value()||blendB!=b.value()){
                Track.validateShape(a.value(),b.value(),blend);blendA=a.value();blendB=b.value();equalEndpoints=blendA.equals(blendB);
                boneBlend=blend==Blend.BONES&&!equalEndpoints?new BoneBlend((Bones)blendA,(Bones)blendB):null;
            }
            double t=(double)(time-a.timeNs())/(b.timeNs()-a.timeNs());
            if(equalEndpoints)return a.value();
            if(blend==Blend.BONES){Bones x=(Bones)a.value(),y=(Bones)b.value();float[] p=new float[x.positions.length],q=new float[x.rotations.length],c=new float[x.curves.length];
                for(int n=0;n<p.length;n++)p[n]=(float)(x.positions[n]+((double)y.positions[n]-x.positions[n])*t);
                for(int n=0;n<c.length;n++)c[n]=(float)(x.curves[n]+((double)y.curves[n]-x.curves[n])*t);
                boneBlend.rotations(t,q);
                // Only locally allocated arrays enter this trusted constructor.
                // Endpoints and shape are validated; interpolation never exposes them.
                return new Bones(p,q,c,true);
            }
            double[] x=((Numbers)a.value()).data,y=((Numbers)b.value()).data,v=new double[x.length];
            for(int n=0;n<v.length;n++)v[n]=blend==Blend.ANGLES?x[n]+wrap(wrap(y[n])-wrap(x[n]))*t:x[n]*(1-t)+y[n]*t;
            return new Numbers(v);
        }
        /** Bracket-local quaternion constants. Results retain the canonical operation order. */
        static final class BoneBlend {
            final Bones a;final double[] values;final byte[] modes;
            BoneBlend(Bones a,Bones b){
                this.a=a;int count=a.rotations.length/4;values=new double[count*10];modes=new byte[count];
                for(int bone=0;bone<count;bone++){
                    int n=bone*4,k=bone*10;double ax=a.rotations[n],ay=a.rotations[n+1],az=a.rotations[n+2],aw=a.rotations[n+3],bx=b.rotations[n],by=b.rotations[n+1],bz=b.rotations[n+2],bw=b.rotations[n+3];
                    if(Float.floatToRawIntBits(a.rotations[n])==Float.floatToRawIntBits(b.rotations[n])&&Float.floatToRawIntBits(a.rotations[n+1])==Float.floatToRawIntBits(b.rotations[n+1])&&Float.floatToRawIntBits(a.rotations[n+2])==Float.floatToRawIntBits(b.rotations[n+2])&&Float.floatToRawIntBits(a.rotations[n+3])==Float.floatToRawIntBits(b.rotations[n+3])&&ax*ax+ay*ay+az*az+aw*aw==1){modes[bone]=2;continue;}
                    double length=Math.sqrt(ax*ax+ay*ay+az*az+aw*aw);ax/=length;ay/=length;az/=length;aw/=length;
                    length=Math.sqrt(bx*bx+by*by+bz*bz+bw*bw);bx/=length;by/=length;bz/=length;bw/=length;
                    double dot=ax*bx+ay*by+az*bz+aw*bw;if(dot<0){bx=-bx;by=-by;bz=-bz;bw=-bw;dot=-dot;}
                    values[k]=ax;values[k+1]=ay;values[k+2]=az;values[k+3]=aw;values[k+4]=bx;values[k+5]=by;values[k+6]=bz;values[k+7]=bw;
                    if(dot<.9995){modes[bone]=1;values[k+8]=Math.acos(Math.min(1,dot));values[k+9]=Math.sin(values[k+8]);}
                }
            }
            void rotations(double t,float[] out){
                for(int bone=0;bone<modes.length;bone++){
                    int n=bone*4,k=bone*10;if(modes[bone]==2){System.arraycopy(a.rotations,n,out,n,4);continue;}
                    double s0=1-t,s1=t;if(modes[bone]==1){double angle=values[k+8],s=values[k+9];s0=Math.sin((1-t)*angle)/s;s1=Math.sin(t*angle)/s;}
                    double x=values[k]*s0+values[k+4]*s1,y=values[k+1]*s0+values[k+5]*s1,z=values[k+2]*s0+values[k+6]*s1,w=values[k+3]*s0+values[k+7]*s1,length=Math.sqrt(x*x+y*y+z*z+w*w);
                    out[n]=(float)(x/length);out[n+1]=(float)(y/length);out[n+2]=(float)(z/length);out[n+3]=(float)(w/length);
                }
            }
        }
        static double wrap(double n){double v=n%360;return v>=180?v-360:v< -180?v+360:v;}
        static void blendRotation(float[] a,float[] b,int n,double t,float[] out){
            double ax=a[n],ay=a[n+1],az=a[n+2],aw=a[n+3],bx=b[n],by=b[n+1],bz=b[n+2],bw=b[n+3];
            // Exact unit endpoints need no trigonometry or normalization.
            // Common untouched bones carry the identity rotation in both keys.
            if(Float.floatToRawIntBits(a[n])==Float.floatToRawIntBits(b[n])&&Float.floatToRawIntBits(a[n+1])==Float.floatToRawIntBits(b[n+1])&&Float.floatToRawIntBits(a[n+2])==Float.floatToRawIntBits(b[n+2])&&Float.floatToRawIntBits(a[n+3])==Float.floatToRawIntBits(b[n+3])&&ax*ax+ay*ay+az*az+aw*aw==1){System.arraycopy(a,n,out,n,4);return;}
            double length=Math.sqrt(ax*ax+ay*ay+az*az+aw*aw);ax/=length;ay/=length;az/=length;aw/=length;
            length=Math.sqrt(bx*bx+by*by+bz*bz+bw*bw);bx/=length;by/=length;bz/=length;bw/=length;
            double dot=ax*bx+ay*by+az*bz+aw*bw;if(dot<0){bx=-bx;by=-by;bz=-bz;bw=-bw;dot=-dot;}
            double s0=1-t,s1=t;if(dot<.9995){double angle=Math.acos(Math.min(1,dot)),s=Math.sin(angle);s0=Math.sin((1-t)*angle)/s;s1=Math.sin(t*angle)/s;}
            double x=ax*s0+bx*s1,y=ay*s0+by*s1,z=az*s0+bz*s1,w=aw*s0+bw*s1;length=Math.sqrt(x*x+y*y+z*z+w*w);
            out[n]=(float)(x/length);out[n+1]=(float)(y/length);out[n+2]=(float)(z/length);out[n+3]=(float)(w/length);
        }
    }
    public record ObjectTrack(ObjectId id,String type,long bornNs,long deadNs,Map<String,Track> channels) {
        public ObjectTrack {
            Objects.requireNonNull(id);if(type==null||type.isBlank()||bornNs<0||deadNs< -1||deadNs>=0&&deadNs<=bornNs)throw new IllegalArgumentException("Invalid scene lifespan");channels=Map.copyOf(channels);
            for(var e:channels.entrySet()){if(e.getKey().isBlank()||e.getValue().timeAt(0)<bornNs||deadNs>=0&&e.getValue().timeAt(e.getValue().keys.size()-1)>=deadNs)throw new IllegalArgumentException("Track outside object lifespan");}
        }
        public boolean alive(long time){return time>=bornNs&&(deadNs<0||time<deadNs);}
    }
    public record ObjectState(ObjectId id,String type,Map<String,Value> channels,Map<String,Value> heldAttributes){
        public ObjectState(ObjectId id,String type,Map<String,Value> channels){this(id,type,channels,null);}
        public ObjectState {channels=channels instanceof ChannelRows?channels:Collections.unmodifiableMap(new HashMap<>(channels));heldAttributes=heldAttributes==null||heldAttributes instanceof AttributeRows?heldAttributes:Map.copyOf(heldAttributes);}
    }
    private static boolean attributeChannel(String name){return name.startsWith("attribute/")||name.startsWith("attribute-codec/");}
    static final class AttributeKey {
        final Track track;final int key;Value value;
        AttributeKey(Track track,int key){this.track=track;this.key=key;}
        Value value(){if(value==null)value=track.keyAt(key).value();return value;}
    }
    static final class AttributeRows extends AbstractMap<String,Value> {
        final SceneDomainMap<String,AttributeKey> rows;
        AttributeRows(SceneDomainMap<String,AttributeKey> rows){this.rows=rows;}
        public int size(){return rows.size();}
        public boolean containsKey(Object key){return rows.containsKey(key);}
        public Value get(Object key){AttributeKey value=rows.get(key);return value==null?null:value.value();}
        public Set<Entry<String,Value>> entrySet(){return new AbstractSet<>(){
            public int size(){return rows.size();}
            public Iterator<Entry<String,Value>> iterator(){var source=rows.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return source.hasNext();}public Entry<String,Value> next(){var row=source.next();return Map.entry(row.getKey(),row.getValue().value());}};}
        };}
    }
    static final class AttributeIndex {
        final long[] times;final List<Map<String,Value>> selections;
        AttributeIndex(ObjectTrack object){
            var changes=new TreeMap<Long,Map<String,AttributeKey>>();
            for(var channel:object.channels().entrySet())if(attributeChannel(channel.getKey()))for(int n=0;n<channel.getValue().keys.size();n++)changes.computeIfAbsent(channel.getValue().timeAt(n),unused->new HashMap<>()).put(channel.getKey(),new AttributeKey(channel.getValue(),n));
            times=changes.keySet().stream().mapToLong(Long::longValue).toArray();var roots=new ArrayList<Map<String,Value>>();SceneDomainMap<String,AttributeKey> selected=new SceneDomainMap<>();roots.add(new AttributeRows(selected));
            for(var change:changes.values()){var edit=selected.edit();change.forEach(edit::put);selected=edit.finish();roots.add(new AttributeRows(selected));}selections=List.copyOf(roots);
        }
        Map<String,Value> at(long time){int lo=0,hi=times.length;while(lo<hi){int mid=(lo+hi)>>>1;if(times[mid]<=time)lo=mid+1;else hi=mid;}return selections.get(lo);}
    }
    static final class ChannelRows extends AbstractMap<String,Value> {
        final Map<String,Value> attributes,dynamic;
        ChannelRows(Map<String,Value> attributes,Map<String,Value> dynamic){this.attributes=attributes;this.dynamic=Map.copyOf(dynamic);}
        public int size(){return attributes.size()+dynamic.size();}
        public boolean containsKey(Object key){return dynamic.containsKey(key)||attributes.containsKey(key);}
        public Value get(Object key){Value value=dynamic.get(key);return value==null?attributes.get(key):value;}
        public Set<Entry<String,Value>> entrySet(){return new AbstractSet<>(){
            public int size(){return ChannelRows.this.size();}
            public Iterator<Entry<String,Value>> iterator(){var a=attributes.entrySet().iterator();var b=dynamic.entrySet().iterator();return new Iterator<>(){public boolean hasNext(){return a.hasNext()||b.hasNext();}public Entry<String,Value> next(){return a.hasNext()?a.next():b.next();}};}
        };}
    }
    /** Immutable map rows are shared between their indexed change boundaries. */
    private static final class FrameObjects extends AbstractMap<ObjectId,ObjectState> {
        final Map<ObjectId,ObjectState> map,scene;
        final boolean indexed;
        FrameObjects(Map<ObjectId,ObjectState> map,Map<ObjectId,ObjectState> scene,boolean indexed){this.map=map;this.scene=Map.copyOf(scene);this.indexed=indexed;}
        public ObjectState get(Object key){ObjectState value=scene.get(key);return value!=null?value:map.get(key);}
        public int size(){return map.size()+scene.size();}
        public Set<Entry<ObjectId,ObjectState>> entrySet(){return new AbstractSet<>(){
            public int size(){return FrameObjects.this.size();}
            public Iterator<Entry<ObjectId,ObjectState>> iterator(){return new Iterator<>(){
                final Iterator<Entry<ObjectId,ObjectState>> first=map.entrySet().iterator(),second=scene.entrySet().iterator();
                public boolean hasNext(){return first.hasNext()||second.hasNext();}
                public Entry<ObjectId,ObjectState> next(){return first.hasNext()?first.next():second.next();}
            };}
        };}
    }
    public record Frame(long timeNs,Map<ObjectId,ObjectState> objects){
        public Frame {objects=objects instanceof FrameObjects?objects:Collections.unmodifiableMap(new HashMap<>(objects));}
        Collection<ObjectState> presentationObjects(){return objects instanceof FrameObjects rows?rows.scene.values():objects.values();}
        Map<ObjectId,ObjectState> indexedMapObjects(){return objects instanceof FrameObjects rows&&rows.indexed?rows.map:null;}
        Frame replacePresentation(Map<ObjectId,ObjectState> replacements){
            if(replacements.isEmpty())return this;
            if(objects instanceof FrameObjects rows){
                var scene=new HashMap<>(rows.scene);replacements.forEach((id,state)->{if(scene.containsKey(id))scene.put(id,state);});
                return new Frame(timeNs,new FrameObjects(rows.map,scene,rows.indexed));
            }
            var scene=new HashMap<>(objects);replacements.forEach((id,state)->{if(scene.containsKey(id))scene.put(id,state);});
            return new Frame(timeNs,scene);
        }
    }
    public final long durationNs;public final String eventHash,compatibilityFingerprint;
    public final Set<Domain> coverage;public final List<ObjectTrack> objects;
    private static final class Constant {
        final long readyNs;final ObjectTrack object;ObjectState selected;
        Constant(long ready,ObjectTrack object,ObjectState selected){readyNs=ready;this.object=object;this.selected=selected;}
        synchronized ObjectState state(){
            if(selected==null){Map<String,Value> values=new HashMap<>();object.channels().forEach((name,track)->values.put(name,track.keys.get(0).value()));selected=new ObjectState(object.id(),object.type(),values);}
            return selected;
        }
    }
    private final Map<ObjectId,Constant> constants;
    private final Map<ObjectId,AttributeIndex> attributeIndices;
    private final List<ObjectTrack> mapTracks,sceneTracks;
    private final boolean mapIndexed;
    private final long[] mapChanges;
    private Map<ObjectId,ObjectState> selectedMap=Map.of();
    private final List<Map<ObjectId,ObjectState>> mapSelections;
    /** Persistent pages of per-object HOLD intervals, containing no decoded meshes.
     * All times address the same index directly, including reverse/random seeks. */
    private final class MapValue {
        final ObjectTrack track;final long time;ObjectState decoded;
        MapValue(ObjectTrack track,long time){this.track=track;this.time=time;}
        synchronized ObjectState value(){if(decoded==null)decoded=state(track,time);return decoded;}
    }
    private final class MapRows extends AbstractMap<ObjectId,ObjectState> {
        final MapValue[][] pages;final Map<ObjectId,Integer> ordinals;final int count;
        MapRows(MapValue[][] pages,Map<ObjectId,Integer> ordinals,int count){this.pages=pages;this.ordinals=ordinals;this.count=count;}
        public int size(){return count;}
        public ObjectState get(Object key){Integer n=ordinals.get(key);if(n==null)return null;MapValue v=pages[n>>>6][n&63];return v==null?null:v.value();}
        public Set<Entry<ObjectId,ObjectState>> entrySet(){return new AbstractSet<>(){
            public int size(){return count;}
            public Iterator<Entry<ObjectId,ObjectState>> iterator(){return new Iterator<>(){
                int next=advance(0);private int advance(int n){while(n<mapTracks.size()&&pages[n>>>6][n&63]==null)n++;return n;}
                public boolean hasNext(){return next<mapTracks.size();}
                public Entry<ObjectId,ObjectState> next(){if(!hasNext())throw new NoSuchElementException();MapValue v=pages[next>>>6][next&63];next=advance(next+1);return Map.entry(v.track.id(),v.value());}
            };}
        };}
    }
    static Set<ObjectId> changedMapRows(Map<ObjectId,ObjectState> before,Map<ObjectId,ObjectState> after){
        Set<ObjectId> changed=new HashSet<>();
        if(before instanceof SceneTracks.MapRows a&&after instanceof SceneTracks.MapRows b&&a.ordinals==b.ordinals){
            for(int page=0;page<a.pages.length;page++)if(a.pages[page]!=b.pages[page])for(int slot=0;slot<64;slot++){
                var old=a.pages[page][slot];var value=b.pages[page][slot];if(old!=value)changed.add((value!=null?value:old).track.id());
            }
        }else {for(var e:before.entrySet())if(after.get(e.getKey())!=e.getValue())changed.add(e.getKey());for(var e:after.entrySet())if(before.get(e.getKey())!=e.getValue())changed.add(e.getKey());}
        return changed;
    }
    AutoCloseable storage;
    private boolean closed;
    public SceneTracks(long duration,String eventHash,String fingerprint,Set<Domain> coverage,List<ObjectTrack> objects){
        if(duration<0||eventHash==null||!eventHash.matches("[0-9a-f]{64}")||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid scene provenance");
        durationNs=duration;this.eventHash=eventHash;compatibilityFingerprint=fingerprint;this.coverage=Set.copyOf(coverage);
        List<ObjectTrack> sorted=new ArrayList<>(objects);sorted.sort(Comparator.comparing(ObjectTrack::id));Set<ObjectId> ids=new HashSet<>();
        for(ObjectTrack o:sorted){if(!ids.add(o.id())||o.bornNs()>duration||o.deadNs()>duration)throw new IllegalArgumentException("Invalid scene object");for(Track t:o.channels().values())if(t.timeAt(t.keys.size()-1)>duration)throw new IllegalArgumentException("Track exceeds recording");}
        this.objects=List.copyOf(sorted);
        Map<ObjectId,Constant> constantRows=new HashMap<>();
        for(ObjectTrack object:sorted){boolean fixed=true,lazy=false;long ready=object.bornNs();Map<String,Value> values=new HashMap<>();
            for(var channel:object.channels().entrySet()){
                Track track=channel.getValue();if(track.keys.size()!=1){fixed=false;break;}
                ready=Math.max(ready,track.timeAt(0));
                if(track.indexedTimes!=null)lazy=true;else values.put(channel.getKey(),track.keys.get(0).value());
            }
            if(fixed)constantRows.put(object.id(),new Constant(ready,object,lazy?null:new ObjectState(object.id(),object.type(),values)));
        }
        constants=Map.copyOf(constantRows);
        var attributeRows=new HashMap<ObjectId,AttributeIndex>();
        for(ObjectTrack object:sorted)if(!object.type().startsWith("world-map-")&&object.channels().entrySet().stream().anyMatch(e->attributeChannel(e.getKey()))&&object.channels().entrySet().stream().filter(e->attributeChannel(e.getKey())).allMatch(e->e.getValue().blend==Blend.HOLD))attributeRows.put(object.id(),new AttributeIndex(object));
        attributeIndices=Map.copyOf(attributeRows);
        List<ObjectTrack> mapRows=new ArrayList<>(),sceneRows=new ArrayList<>();TreeSet<Long> changes=new TreeSet<>();boolean indexedMap=true;
        for(ObjectTrack object:sorted)if(object.type().startsWith("world-map-")){
            if(object.channels().values().stream().anyMatch(t->t.blend!=Blend.HOLD))indexedMap=false;
            mapRows.add(object);changes.add(object.bornNs());if(object.deadNs()>=0)changes.add(object.deadNs());
            for(Track track:object.channels().values())for(int n=0;n<track.keys.size();n++)changes.add(track.timeAt(n));
        }else sceneRows.add(object);
        mapIndexed=indexedMap;mapTracks=indexedMap?List.copyOf(mapRows):List.of();sceneTracks=indexedMap?List.copyOf(sceneRows):this.objects;
        mapChanges=indexedMap?changes.stream().mapToLong(Long::longValue).toArray():new long[0];
        if(indexedMap){
            Map<ObjectId,Integer> ordinals=new HashMap<>();TreeMap<Long,Set<Integer>> edits=new TreeMap<>();
            for(int i=0;i<mapTracks.size();i++){
                ObjectTrack object=mapTracks.get(i);ordinals.put(object.id(),i);Set<Long> times=new HashSet<>();times.add(object.bornNs());if(object.deadNs()>=0)times.add(object.deadNs());
                for(Track track:object.channels().values())for(int k=0;k<track.keys.size();k++)times.add(track.timeAt(k));
                for(long time:times)edits.computeIfAbsent(time,unused->new HashSet<>()).add(i);
            }
            ordinals=Map.copyOf(ordinals);MapValue[][] pages=new MapValue[(mapTracks.size()+63)>>>6][64];int count=0;
            List<Map<ObjectId,ObjectState>> selections=new ArrayList<>();selections.add(new MapRows(pages,ordinals,0));
            for(var edit:edits.entrySet()){
                MapValue[][] next=pages.clone();BitSet copied=new BitSet();
                for(int n:edit.getValue()){
                    int page=n>>>6,slot=n&63;if(!copied.get(page)){next[page]=pages[page].clone();copied.set(page);}
                    ObjectTrack object=mapTracks.get(n);MapValue value=object.alive(edit.getKey())?new MapValue(object,edit.getKey()):null;
                    if(pages[page][slot]==null&&value!=null)count++;else if(pages[page][slot]!=null&&value==null)count--;
                    next[page][slot]=value;
                }
                pages=next;selections.add(new MapRows(pages,ordinals,count));
            }
            mapSelections=List.copyOf(selections);
        }else mapSelections=List.of();
    }
    public boolean complete(){return coverage.containsAll(EnumSet.allOf(Domain.class));}
    public void close()throws Exception {if(closed)return;closed=true;if(storage!=null){AutoCloseable value=storage;storage=null;value.close();}}
    private ObjectState state(ObjectTrack o,long time){
        Constant fixed=constants.get(o.id());if(fixed!=null&&time>=fixed.readyNs)return fixed.state();
        AttributeIndex attributes=attributeIndices.get(o.id());if(attributes!=null){
            Map<String,Value> values=new HashMap<>();for(var e:o.channels().entrySet())if(!attributeChannel(e.getKey())){Value v=e.getValue().sample(time);if(v!=null)values.put(e.getKey(),v);}
            Map<String,Value> held=attributes.at(time);return new ObjectState(o.id(),o.type(),new ChannelRows(held,values),held);
        }
        Map<String,Value> values=new HashMap<>();for(var e:o.channels().entrySet()){Value v=e.getValue().sample(time);if(v!=null)values.put(e.getKey(),v);}return new ObjectState(o.id(),o.type(),values);
    }
    synchronized Map<ObjectId,ObjectState> mapRowsAt(long time){
        if(!mapIndexed)return null;
        if(closed||time<0||time>durationNs)throw new IllegalStateException("Unavailable indexed map time");
        int lo=0,hi=mapChanges.length;while(lo<hi){int mid=(lo+hi)>>>1;if(mapChanges[mid]<=time)lo=mid+1;else hi=mid;}
        Map<ObjectId,ObjectState> rows=mapSelections.get(lo);selectedMap=rows;return rows;
    }
    List<Map<ObjectId,ObjectState>> mapIndexRows(){return mapSelections;}
    List<ObjectTrack> sceneIndexRows(){return sceneTracks;}
    long mapIndexTime(int row){return row==0?0:mapChanges[row-1];}
    synchronized boolean prepare(long time,long horizon,boolean urgent)throws java.io.IOException {
        boolean ready=true;long end=time+horizon;
        for(var object:sceneTracks)if(object.alive(time)||horizon!=0&&object.alive(end))for(var track:object.channels().values())ready&=track.prepare(time,horizon,urgent);
        return ready;
    }
    /** Resource anticipation needs creation/appearance data, never future skeletal poses. */
    synchronized boolean prepareResources(long time)throws java.io.IOException {
        boolean ready=true;
        for(var object:sceneTracks)if(!object.type().equals("world-environment")&&!object.type().startsWith("world-map-")&&object.alive(time))
            for(var track:object.channels().values())if(track.blend!=Blend.BONES)ready&=track.prepare(time,0,false);
        return ready;
    }
    synchronized Frame sampleResources(long time){
        if(closed)throw new IllegalStateException("Closed scene tracks");
        if(time<0||time>durationNs)throw new IllegalArgumentException("Time outside scene");
        Map<ObjectId,ObjectState> visible=new HashMap<>();
        for(var object:sceneTracks)if(!object.type().equals("world-environment")&&!object.type().startsWith("world-map-")&&object.alive(time)){
            Map<String,Value> values=new HashMap<>();AttributeIndex index=attributeIndices.get(object.id());
            for(var channel:object.channels().entrySet())if(channel.getValue().blend!=Blend.BONES&&(index==null||!attributeChannel(channel.getKey()))){
                Value value=channel.getValue().sample(time);if(value!=null)values.put(channel.getKey(),value);
            }
            Map<String,Value> attributes=index==null?Map.of():index.at(time);
            visible.put(object.id(),new ObjectState(object.id(),object.type(),new ChannelRows(attributes,values),attributes));
        }
        return new Frame(time,new FrameObjects(Map.of(),visible,false));
    }
    /** FP preparation samples only the selected actor; it never decodes world/TP poses. */
    synchronized ObjectState fpResources(ObjectTrack object,long time)throws java.io.IOException {
        return resourceState(object,time,false);
    }
    synchronized ObjectState resourceState(ObjectTrack object,long time,boolean includeBones)throws java.io.IOException {
        if(closed||!object.alive(time))throw new IllegalArgumentException("Unavailable FP actor time");
        boolean ready=true;for(var track:object.channels().values())if(includeBones||track.blend!=Blend.BONES)ready&=track.prepare(time,0,false);
        if(!ready)return null;
        var values=new HashMap<String,Value>();AttributeIndex index=attributeIndices.get(object.id());
        for(var channel:object.channels().entrySet())if((includeBones||channel.getValue().blend!=Blend.BONES)&&(index==null||!attributeChannel(channel.getKey()))){Value value=channel.getValue().sample(time);if(value!=null)values.put(channel.getKey(),value);}
        var attributes=index==null?Map.<String,Value>of():index.at(time);
        return new ObjectState(object.id(),object.type(),new ChannelRows(attributes,values),attributes);
    }
    public synchronized Frame sample(long time){
        if(closed)throw new IllegalStateException("Closed scene tracks");
        if(time<0||time>durationNs)throw new IllegalArgumentException("Time outside scene");Map<ObjectId,ObjectState> visible=new HashMap<>();
        if(mapIndexed)mapRowsAt(time);
        for(ObjectTrack o:sceneTracks)if(o.alive(time)){
            visible.put(o.id(),state(o,time));
        }
        return new Frame(time,new FrameObjects(selectedMap,visible,mapIndexed));
    }
}
