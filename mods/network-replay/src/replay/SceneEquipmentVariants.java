package replay;

import java.util.*;

/** Old captures can pair a pending item swap with the previously visible rig.
 * Try that actor's interval first, then resource recipes recorded with the
 * same rig signature. No model is admitted without matching its skeleton. */
final class SceneEquipmentVariants {
    record Key(SceneTracks.ObjectId actor,long interval,SceneTracks.Value layout){}
    final Map<Key,Set<SceneTracks.Bytes>> values;
    final Map<SceneTracks.ObjectId,SceneTracks.Track> rigs;
    final Map<SceneTracks.Value,List<SceneTracks.Bytes>> recipes;
    SceneEquipmentVariants(SceneTracks tracks){
        Map<Key,Set<SceneTracks.Bytes>> index=new HashMap<>();
        Map<SceneTracks.ObjectId,SceneTracks.Track> layouts=new HashMap<>();
        for(var object:tracks.objects){
            var items=object.channels().get("equipment-held-item");var layout=object.channels().get("attachment/equipment/rig-layout");
            if(items==null||layout==null)continue;layouts.put(object.id(),layout);
            for(var key:items.keys)add(index,object.id(),interval(layout,key.timeNs()),layout.sample(key.timeNs()),key.value());
            for(var key:layout.keys)add(index,object.id(),key.timeNs(),key.value(),items.sample(key.timeNs()));
        }
        Map<Key,Set<SceneTracks.Bytes>> frozen=new HashMap<>();index.forEach((key,set)->frozen.put(key,Set.copyOf(set)));values=Map.copyOf(frozen);rigs=Map.copyOf(layouts);
        Map<SceneTracks.Value,Set<SceneTracks.Bytes>> catalog=new HashMap<>();
        index.forEach((key,set)->catalog.computeIfAbsent(key.layout(),ignored->new HashSet<>()).addAll(set));
        Map<SceneTracks.Value,List<SceneTracks.Bytes>> selected=new HashMap<>();catalog.forEach((key,set)->selected.put(key,List.copyOf(set)));recipes=Map.copyOf(selected);
    }
    private static long interval(SceneTracks.Track track,long time){
        int low=0,high=track.keys.size()-1;while(low<high){int mid=(low+high+1)>>>1;if(track.timeAt(mid)<=time)low=mid;else high=mid-1;}return track.timeAt(low);
    }
    private static void add(Map<Key,Set<SceneTracks.Bytes>> index,SceneTracks.ObjectId actor,long interval,SceneTracks.Value layout,SceneTracks.Value item){
        if(layout instanceof SceneTracks.Bytes b&&b.size()>0&&item instanceof SceneTracks.Bytes value)
            index.computeIfAbsent(new Key(actor,interval,layout),k->new HashSet<>()).add(value);
    }
    SceneTracks.Bytes alternate(SceneTracks.ObjectState state,SceneTracks.Value requested,long time){
        var layout=rigs.get(state.id());if(layout==null)return null;
        var candidates=values.getOrDefault(new Key(state.id(),interval(layout,time),state.channels().get("attachment/equipment/rig-layout")),Set.of());
        SceneTracks.Bytes result=null;
        for(var candidate:candidates)if(!candidate.equals(requested)){if(result!=null)return null;result=candidate;}
        return result;
    }
    /** Recorded resource recipes are candidates only. The caller must certify
     * the exact native skeleton before admitting any of them to presentation. */
    List<SceneTracks.Bytes> candidates(SceneTracks.ObjectState state,SceneTracks.Value requested,long time){
        var layout=rigs.get(state.id());if(layout==null)return List.of();
        SceneTracks.Value signature=state.channels().get("attachment/equipment/rig-layout");
        LinkedHashSet<SceneTracks.Bytes> selected=new LinkedHashSet<>(values.getOrDefault(new Key(state.id(),interval(layout,time),signature),Set.of()));
        selected.addAll(recipes.getOrDefault(signature,List.of()));selected.remove(requested);return List.copyOf(selected);
    }
}
