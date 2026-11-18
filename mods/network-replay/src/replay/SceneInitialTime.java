package replay;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Skip the native placeholder position before the recording first locates its player. */
final class SceneInitialTime {
    static long floor(SceneTracks tracks){return tracks.objects.stream().filter(o->o.type().equals("world-environment")).mapToLong(SceneTracks.ObjectTrack::bornNs).min().orElse(0);}
    static long choose(SceneTracks tracks){
        long floor=floor(tracks);var columns=new HashMap<String,List<SceneTracks.ObjectTrack>>();
        for(var object:tracks.objects)if(object.type().equals("world-map-chunk")){
            var keys=object.channels().get("map-key");if(keys!=null&&keys.keys.get(0).value() instanceof SceneTracks.Bytes key)columns.computeIfAbsent(new String(key.data(),StandardCharsets.UTF_8),unused->new ArrayList<>()).add(object);
        }
        for(var recorder:tracks.objects)if(recorder.type().equals("961798934")){
            var positions=recorder.channels().get("position");var rig=recorder.channels().get("rig-layout");if(positions==null||rig==null)continue;
            long ready=Math.max(floor,rig.keys.get(0).timeNs());
            for(var key:positions.keys){if(key.timeNs()<ready)continue;double[] xyz=((SceneTracks.Numbers)key.value()).data();String coordinate=(int)Math.floor(xyz[0]/16)+","+(int)Math.floor(xyz[2]/16);
                var known=columns.get(coordinate);if(columns.isEmpty()||known!=null&&known.stream().anyMatch(o->o.alive(key.timeNs())))return key.timeNs();
            }
            return ready;
        }
        return floor;
    }
}
