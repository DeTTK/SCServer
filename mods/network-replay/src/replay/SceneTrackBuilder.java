package replay;

import java.util.*;

/** Preparation-only collector. Constant runs retain their ending key to avoid early interpolation. */
final class SceneTrackBuilder {
    static final class Channel {
        final SceneTracks.Domain domain;final SceneTracks.Blend blend;final List<SceneTracks.Key> keys=new ArrayList<>();
        SceneTracks.Value last;long lastTime=-1;
        Channel(SceneTracks.Domain domain,SceneTracks.Blend blend){this.domain=domain;this.blend=blend;}
        void put(long time,SceneTracks.Value value,boolean cut){
            if(time<lastTime)throw new IllegalArgumentException("Scene preparation went backwards");
            if(time==lastTime){if(!Objects.equals(value,last))throw new IllegalArgumentException("Conflicting scene samples at the same time");return;}
            if(last!=null)SceneTracks.Track.validateShape(last,value,blend);
            if(last==null)keys.add(new SceneTracks.Key(time,value,cut));
            else if(!last.equals(value)||cut){
                if(blend!=SceneTracks.Blend.HOLD&&lastTime>keys.get(keys.size()-1).timeNs())keys.add(new SceneTracks.Key(lastTime,last,false));
                keys.add(new SceneTracks.Key(time,value,cut));
            }
            last=value;lastTime=time;
        }
        SceneTracks.Track finish(){
            if(blend!=SceneTracks.Blend.HOLD&&lastTime>keys.get(keys.size()-1).timeNs())keys.add(new SceneTracks.Key(lastTime,last,false));
            return new SceneTracks.Track(domain,blend,keys);
        }
    }
    static final class ObjectRow {
        final SceneTracks.ObjectId id;final String type;final long born;long dead=-1;
        final Map<String,Channel> channels=new TreeMap<>();
        ObjectRow(SceneTracks.ObjectId id,String type,long born){this.id=id;this.type=type;this.born=born;}
        void put(String name,SceneTracks.Domain domain,SceneTracks.Blend blend,long time,SceneTracks.Value value,boolean cut){
            if(time<born||dead>=0&&time>=dead)throw new IllegalArgumentException("Write outside scene object lifetime");
            Channel channel=channels.computeIfAbsent(name,k->new Channel(domain,blend));if(channel.domain!=domain||channel.blend!=blend)throw new IllegalArgumentException("Changing channel codec");channel.put(time,value,cut);
        }
    }
    final List<ObjectRow> rows=new ArrayList<>();final Map<String,Integer> generations=new HashMap<>();
    ObjectRow born(int session,int id,String type,long time){String key=session+":"+id;int generation=generations.merge(key,1,Integer::sum)-1;ObjectRow row=new ObjectRow(new SceneTracks.ObjectId(session,id,generation),type,time);rows.add(row);return row;}
    void died(ObjectRow row,long time){if(time<=row.born)throw new IllegalArgumentException("Empty observed scene lifetime");row.dead=time;}
    SceneTracks finish(DemoFile demo,Set<SceneTracks.Domain> coverage){
        List<SceneTracks.ObjectTrack> objects=new ArrayList<>();
        for(ObjectRow row:rows){Map<String,SceneTracks.Track> channels=new TreeMap<>();row.channels.forEach((k,c)->channels.put(k,c.finish()));objects.add(new SceneTracks.ObjectTrack(row.id,row.type,row.born,row.dead,channels));}
        return new SceneTracks(demo.durationNs,demo.manifest.get("event_stream_sha256").getAsString(),demo.compatibilityFingerprint,coverage,objects);
    }
}
