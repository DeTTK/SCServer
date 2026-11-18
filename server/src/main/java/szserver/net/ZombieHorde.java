package szserver.net;

import szserver.commands.CommandService;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One optional, bounded mini-game. Called only on the native network owner. */
public final class ZombieHorde {
    public record Target(UUID character,World.Vec position,double eyeHeight,boolean alive){}
    private static final String PREFIX="7a6f6d62-6965-4000-";
    private final WorldLoop world;private final CommandService commands;private final MapObstructionAccess maps;
    private final AtomicLong generation=new AtomicLong();
    private final Map<String,CompletableFuture<Void>> spawning=new LinkedHashMap<>();
    private final Map<String,CompletableFuture<Boolean>> removing=new LinkedHashMap<>();
    private final Set<String> owned=new LinkedHashSet<>();
    private UUID target;private boolean initialized;private long tick,nextQuery,spawned,retired,failures;
    private CompletableFuture<MapObstructionAccess.HordeSpawns> query;private long queryGeneration;
    private String lastError="";
    public ZombieHorde(WorldLoop world,CommandService commands,MapObstructionAccess maps){this.world=world;this.commands=commands;this.maps=maps;}
    public static boolean owns(String id){return id.startsWith(PREFIX);}
    public UUID target(){return target;}
    public String start(UUID character){generation.incrementAndGet();target=Objects.requireNonNull(character);initialized=false;nextQuery=0;return "Зомби включены: до 20, спавн за укрытиями. Остановить: /zombies stop";}
    public String stop(){generation.incrementAndGet();target=null;return "Зомби выключены.";}
    public Map<String,Object> status(){return Map.of("active",target!=null,"target",target==null?"":target.toString(),"maximum",20,"spawning",spawning.size(),"spawned",spawned,"retired",retired,"failures",failures,"lastError",lastError);}
    private void remove(String id){
        if(removing.containsKey(id))return;
        removing.put(id,world.submit(w->{if(w.contains(id))w.remove(id);return true;}));retired++;
    }
    private void fail(Throwable error){failures++;Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();lastError=Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());}
    public void tick(List<Target> targets){
        tick++;
        for(var iterator=spawning.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!entry.getValue().isDone())continue;try{entry.getValue().join();spawned++;}catch(RuntimeException error){owned.remove(entry.getKey());fail(error);}iterator.remove();}
        for(var iterator=removing.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!entry.getValue().isDone())continue;try{entry.getValue().join();owned.remove(entry.getKey());}catch(RuntimeException error){fail(error);}iterator.remove();}
        var snapshot=world.snapshot();
        if(!initialized){initialized=true;for(var actor:snapshot.entities())if(owns(actor.id())){owned.add(actor.id());remove(actor.id());}}
        Target player=targets.stream().filter(p->p.character().equals(target)&&p.alive()).findFirst().orElse(null);
        int alive=0;
        for(var actor:snapshot.entities())if(owns(actor.id())){
            boolean distant=player!=null&&Math.hypot(Math.hypot(actor.x()-player.position().x(),actor.z()-player.position().z()),actor.y()-player.position().y())>=100;
            if(player==null||actor.health()<=0||distant){remove(actor.id());continue;}
            if(!removing.containsKey(actor.id()))alive++;
        }
        if(query!=null&&query.isDone()){
            try{
                var batch=query.join();
                if(player!=null&&queryGeneration==generation.get()&&distance(player.position(),batch.player())<.75){
                    for(var position:batch.positions()){
                        if(owned.size()>=20)break;
                        String id=new UUID(0x7a6f6d6269654000L,ThreadLocalRandom.current().nextLong()).toString();
                        long epoch=generation.get();UUID character=player.character();World.Vec expected=player.position();
                        owned.add(id);spawning.put(id,commands.spawnTransientFollower(id,position,character,w->{
                            if(generation.get()!=epoch)throw new IllegalArgumentException("horde_replaced");
                            batch.authorization().checkCurrent();
                            World.Vec latest=w.playerPosition(character);
                            if(latest==null||distance(latest,expected)>.75)throw new IllegalArgumentException("horde_player_moved");
                        }));
                    }
                }
            }catch(RuntimeException error){fail(error);}finally{query=null;}
        }
        if(player!=null&&query==null&&spawning.isEmpty()&&removing.isEmpty()&&owned.size()<20&&alive<20&&tick>=nextQuery&&maps.ready()){
            queryGeneration=generation.get();query=maps.hordeSpawns(player.position(),player.eyeHeight(),ThreadLocalRandom.current().nextLong(),Math.min(3,20-alive));nextQuery=tick+20;
        }
    }
    private static double distance(World.Vec a,World.Vec b){return Math.hypot(Math.hypot(a.x()-b.x(),a.z()-b.z()),a.y()-b.y());}
}
