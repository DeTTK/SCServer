package replay;

import java.io.*;
import java.util.*;

/** Actor-owned FP cues. Projectile packets identify the shooter, never the listener. */
final class SceneFpActions {
    enum Kind { SHOT, RELOAD }
    record Cue(long timeNs,int actor,Kind kind,DemoFile.Event event) {}
    final Map<Integer,List<Cue>> actors;
    SceneFpActions(DemoFile demo)throws IOException {
        this(demo.events,id->{var info=demo.packets.getAsJsonObject(Integer.toString(id));return info==null||info.get("target_class").isJsonNull()?"":info.get("target_class").getAsString();});
    }
    SceneFpActions(List<DemoFile.Event> events,java.util.function.IntFunction<String> mapping)throws IOException {
        var rows=new HashMap<Integer,List<Cue>>();
        for(var event:events){
            if(!event.incoming())continue;
            String type=mapping.apply(event.sourceId());Kind kind;
            if(type.equals("1103215333"))kind=Kind.SHOT;
            else if(type.equals("1072250159"))kind=Kind.RELOAD;else continue;
            try(var in=new DataInputStream(new ByteArrayInputStream(event.payload()))){
                if(kind==Kind.SHOT)in.readUnsignedByte();int actor=in.readInt();
                var cues=rows.computeIfAbsent(actor,unused->new ArrayList<>());
                // One shotgun discharge may contain multiple projectiles at precisely the same instant.
                if(kind==Kind.SHOT&&!cues.isEmpty()){var last=cues.get(cues.size()-1);if(last.kind()==kind&&last.timeNs()==event.timeNs())continue;}
                cues.add(new Cue(event.timeNs(),actor,kind,event));
            }
        }
        var frozen=new HashMap<Integer,List<Cue>>();rows.forEach((actor,cues)->frozen.put(actor,List.copyOf(cues)));actors=Map.copyOf(frozen);
    }
    List<Cue> between(SceneTracks.ObjectTrack actor,long after,long through){
        var cues=actors.getOrDefault(actor.id().networkId(),List.of());int first=upper(cues,Math.max(after,actor.bornNs()-1)),end=upper(cues,Math.min(through,actor.deadNs()<0?Long.MAX_VALUE:actor.deadNs()-1));
        return end<=first?List.of():cues.subList(first,end);
    }
    static int upper(List<Cue> cues,long time){int lo=0,hi=cues.size();while(lo<hi){int mid=(lo+hi)>>>1;if(cues.get(mid).timeNs()<=time)lo=mid+1;else hi=mid;}return lo;}
}
