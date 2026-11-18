package replay;

import java.io.IOException;
import java.util.*;

/** Sample recorder transforms at the timestamps of the original local controls.
 * The native 20-Hz render alpha is not the interpolation phase of these packets.
 * Sampling is stateless: forward, reverse and arbitrary seeks have identical poses. */
final class SceneRecorderMotion {
    static final long MAX_GAP_NS=250_000_000L;
    record Sample(long timeNs,int connection,double[] position,float[] look){}
    record Transform(SceneTracks.Track position,SceneTracks.Track look){}
    final Map<SceneTracks.ObjectId,Transform> tracks=new HashMap<>();
    final int samples;
    SceneRecorderMotion(SceneTracks scene,DemoFile demo)throws IOException {this(scene,read(demo));}
    SceneRecorderMotion(SceneTracks scene,List<Sample> source){
        samples=source.size();
        for(var object:scene.objects)if(object.type().equals("961798934")||object.channels().containsKey("recorder-template")){
            var positions=new TreeMap<Long,double[]>();var looks=new TreeMap<Long,double[]>();
            Integer connection=null;
            for(var point:source)if(object.alive(point.timeNs())){
                if(connection!=null&&connection!=point.connection())break;
                connection=point.connection();
                if(point.position()!=null)positions.put(point.timeNs(),point.position());
                if(point.look()!=null)looks.put(point.timeNs(),new double[]{point.look()[0],point.look()[1]});
            }
            SceneTracks.Track position=track(positions,SceneTracks.Blend.LINEAR),look=track(looks,SceneTracks.Blend.ANGLES);
            if(position!=null||look!=null)tracks.put(object.id(),new Transform(position,look));
        }
    }
    private static List<Sample> read(DemoFile demo)throws IOException {
        var rows=new ArrayList<Sample>();
        for(var event:demo.events){
            if(event.incoming())continue;var mapping=demo.mapping(event);
            if(mapping==null||!mapping.has("adapter")||!mapping.get("adapter").getAsString().equals("source_local_controls_v1"))continue;
            var controls=SelfMotion.read(event.payload());
            if(controls.position()!=null||controls.look()!=null)rows.add(new Sample(event.timeNs(),event.connection(),controls.position(),controls.look()));
        }
        return List.copyOf(rows);
    }
    private static SceneTracks.Track track(NavigableMap<Long,double[]> points,SceneTracks.Blend blend){
        if(points.isEmpty())return null;
        var keys=new ArrayList<SceneTracks.Key>();long before=-1;double[] previous=null;
        for(var entry:points.entrySet()){
            long time=entry.getKey();double[] value=entry.getValue();boolean cut=before>=0&&time-before>MAX_GAP_NS;
            if(previous!=null&&blend==SceneTracks.Blend.LINEAR){
                double distance=0;for(int i=0;i<3;i++){double d=value[i]-previous[i];distance+=d*d;}
                double elapsed=(time-before)/1e9;
                cut|=distance>64&&distance>10_000*elapsed*elapsed;
            }
            keys.add(new SceneTracks.Key(time,new SceneTracks.Numbers(value),cut));before=time;previous=value;
        }
        return new SceneTracks.Track(SceneTracks.Domain.TRANSFORMS,blend,keys);
    }
    SceneTracks.Frame sample(SceneTracks.Frame frame){
        var changes=new HashMap<SceneTracks.ObjectId,SceneTracks.ObjectState>();
        for(var entry:tracks.entrySet()){
            var object=frame.objects().get(entry.getKey());if(object==null)continue;
            // Correct only the dynamic transform overlay. Copying the complete
            // channel map would materialize every lazy recorder attribute per frame.
            var indexed=object.channels() instanceof SceneTracks.ChannelRows rows?rows:null;
            var values=new HashMap<>(indexed==null?object.channels():indexed.dynamic);var transform=entry.getValue();boolean changed=false;
            if(transform.position()!=null){var value=transform.position().sample(frame.timeNs());if(value!=null){values.put("position",value);changed=true;}}
            if(transform.look()!=null){var value=transform.look().sample(frame.timeNs());if(value!=null){values.put("look",value);changed=true;}}
            if(changed)changes.put(object.id(),new SceneTracks.ObjectState(object.id(),object.type(),indexed==null?values:new SceneTracks.ChannelRows(indexed.attributes,values),object.heldAttributes()));
        }
        return frame.replacePresentation(changes);
    }
}
