package replay;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Direct resource recipes at every recorded appearance boundary. No world
 * snapshots, skeletal playback, network dispatch or simulation are involved. */
final class SceneActorCatalog implements AutoCloseable {
    record Recipe(SceneTracks.ObjectTrack object,long time){}
    record Prepared(Recipe recipe,SceneTracks.ObjectState state){}
    interface Binder {boolean ready(Prepared value)throws Exception;}
    static boolean boundary(String name){
        return name.equals("rig-layout")||name.equals("controller-held-item")||name.equals("hands-held-item")
                ||name.equals("equipment-held-item")||name.equals("recorder-template")
                ||name.startsWith("attachment/")&&name.endsWith("/rig-layout");
    }
    static List<Recipe> recipes(SceneTracks tracks){
        List<Recipe> result=new ArrayList<>();
        for(var object:tracks.objects){
            if(object.type().startsWith("world-"))continue;
            long first=object.bornNs();
            for(String name:List.of("position","look","spawn-event")){
                var track=object.channels().get(name);if(track!=null)first=Math.max(first,track.timeAt(0));
            }
            if(!object.alive(first))continue;
            TreeSet<Long> times=new TreeSet<>();times.add(first);
            for(var channel:object.channels().entrySet())if(boundary(channel.getKey()))
                for(int i=0;i<channel.getValue().keys.size();i++){long time=channel.getValue().timeAt(i);if(time>=first&&object.alive(time))times.add(time);}
            for(long time:times)result.add(new Recipe(object,time));
        }
        result.sort(Comparator.comparingLong(Recipe::time).thenComparing(r->r.object().id()));return List.copyOf(result);
    }
    final List<Recipe> recipes;
    final ArrayBlockingQueue<Prepared> queue=new ArrayBlockingQueue<>(16);
    final List<Prepared> pending=new ArrayList<>(16);
    final Thread worker;
    volatile boolean closed,decoded;
    volatile Exception failure;
    volatile int prepared;
    int admitted;
    boolean ready;
    SceneActorCatalog(SceneTracks tracks,Set<String> physicalTypes){
        recipes=recipes(tracks);
        worker=new Thread(()->{
            try{
                for(var recipe:recipes){
                    if(closed)return;SceneTracks.ObjectState state;
                    while((state=tracks.resourceState(recipe.object(),recipe.time(),physicalTypes.contains(recipe.object().type())))==null){if(closed)return;Thread.sleep(1);}
                    // Resolve lazy binary payloads on this worker. Keep the indexed
                    // value identities, so later selections reuse their native owner.
                    var channels=new HashMap<String,SceneTracks.Value>();
                    for(var entry:state.channels().entrySet()){if(entry.getValue() instanceof SceneTracks.Bytes bytes)bytes.data();channels.put(entry.getKey(),entry.getValue());}
                    queue.put(new Prepared(recipe,new SceneTracks.ObjectState(state.id(),state.type(),channels)));prepared++;
                }
                decoded=true;
            }catch(InterruptedException stopped){if(!closed)failure=stopped;}
            catch(Exception error){if(!closed)failure=error;}
        },"Replay actor resource catalog");worker.setDaemon(true);worker.start();
    }
    boolean step(Binder binder,long deadline,LongSupplier clock)throws Exception {
        if(closed)throw new IllegalStateException("Closed actor catalog");if(failure!=null)throw failure;if(ready)return true;
        for(var iterator=pending.iterator();iterator.hasNext();){
            if(clock.getAsLong()>=deadline)return false;
            if(binder.ready(iterator.next())){iterator.remove();admitted++;}
        }
        while(pending.size()<16&&clock.getAsLong()<deadline){
            Prepared value=queue.poll();if(value==null)break;
            if(binder.ready(value))admitted++;else pending.add(value);
        }
        return ready=decoded&&queue.isEmpty()&&pending.isEmpty();
    }
    Map<String,Object> diagnostic(){return Map.of("recipes",recipes.size(),"decoded",prepared,"admitted",admitted,"pending",pending.size(),"ready",ready,"packet_dispatch",false,"simulation_ticks",false);}
    public void close()throws InterruptedException {if(closed)return;closed=true;worker.interrupt();worker.join();queue.clear();pending.clear();}
}
