package szserver.world;

import java.io.IOException;
import java.util.*;

/** Single collision worker owns this cache. Every query captures the current voxel publication. */
public final class CurrentSceneCollision {
    private record Placement(int x,int z,UUID id){}
    private final Map<String,SceneRegistry.Definition> definitions;private final CollisionBoxes boxes;private final long limit;
    private Object generation;private CollisionTree edited=CollisionTree.empty();private Set<Placement> replaced=Set.of();private long rebuilds;
    public CurrentSceneCollision(Map<String,SceneRegistry.Definition> definitions,CollisionBoxes boxes,long limit){this.definitions=Map.copyOf(definitions);this.boxes=Objects.requireNonNull(boxes);this.limit=limit;}
    public View capture(CollisionTree base,BlockReadView blocks)throws IOException{
        SceneCollision.worker();blocks.checkCurrent();var token=blocks.sceneGeneration();
        if(!token.equals(generation)){
            CollisionTree.Builder builder=new CollisionTree.Builder(limit);Set<Placement> masks=new HashSet<>();
            for(var change:blocks.sceneChanges()){
                builder.reserve(160);masks.add(new Placement(change.x(),change.z(),change.id()));if(change.value()==null)continue;int occurrence=0;
                for(var physical:change.value().occurrences()){
                    SceneObject object=physical.object();var asset=boxes.get(definitions.get(object.asset()));var shape=SceneCollision.shape(object,asset);
                    if(shape!=null)builder.add(new CollisionTree.Item(change.x(),change.z(),physical.container(),occurrence,shape));
                    boxes.ladder(builder,change.x(),change.z(),physical.container(),occurrence,object,definitions.get(object.asset()));
                    boxes.mapObject(builder,change.x(),change.z(),physical.container(),occurrence,object,definitions.get(object.asset()));
                    SceneFeedback.add(builder,change.x(),change.z(),physical.container(),occurrence,object,definitions.get(object.asset()),boxes);occurrence++;
                }
            }
            CollisionTree ready=builder.finish();Set<Placement> readyMasks=Set.copyOf(masks);blocks.checkCurrent();edited=ready;replaced=readyMasks;generation=token;rebuilds++;
        }
        blocks.checkCurrent();return new View(Objects.requireNonNull(base),edited,replaced,blocks);
    }
    public Map<String,Object> metrics(){SceneCollision.worker();return Map.of("rebuilds",rebuilds,"shapes",edited.size(),"maskedHomes",replaced.size(),"retainedBytes",edited.retainedBytes(),"assetCache",boxes.metrics());}
    public static final class View {
        private final CollisionTree base,edited;private final Set<Placement> replaced;private final BlockReadView blocks;
        private View(CollisionTree base,CollisionTree edited,Set<Placement> replaced,BlockReadView blocks){this.base=base;this.edited=edited;this.replaced=replaced;this.blocks=blocks;}
        public void checkCurrent(){blocks.checkCurrent();}
        public ActorScene actors(SceneCollision.Box area){
            SceneCollision.worker();checkCurrent();List<ActorGeometry> cells=new ArrayList<>(),ladders=new ArrayList<>();List<ActorContacts.Volume> feedback=new ArrayList<>();List<ActorSceneObject> objects=new ArrayList<>();java.util.function.Consumer<CollisionTree.Item> collect=item->{
                if(item.object()!=null){if(objects.size()>=ActorScene.MAX_CELLS)throw new IllegalArgumentException("actor_scene_capacity");objects.add(item.object());return;}
                int index=0;for(var cell:item.shape().actorGeometry()){
                    if(ActorScene.intersects(cell.bounds(),area)){if(cells.size()+ladders.size()+feedback.size()>=ActorScene.MAX_CELLS)throw new IllegalArgumentException("actor_scene_capacity");
                        if(item.ladder())ladders.add(cell);else if(item.feedback()==null)cells.add(cell);
                        else feedback.add(new ActorContacts.Volume(new ActorContacts.Key(item.homeX(),item.homeZ(),item.identity(),item.container(),item.occurrence(),index),cell.bounds(),item.feedback()));
                    }index++;
                }
            };
            edited.select(area,item->true,collect);base.select(area,item->!replaced.contains(new Placement(item.homeX(),item.homeZ(),item.identity())),collect);
            checkCurrent();return new ActorScene(area,cells,feedback,ladders,objects,this::checkCurrent);
        }
        public boolean hit(World.Vec origin,World.Vec direction,double distance){
            SceneCollision.worker();checkCurrent();boolean hit=edited.hit(origin,direction,distance,item->true)||base.hit(origin,direction,distance,item->!replaced.contains(new Placement(item.homeX(),item.homeZ(),item.identity())));checkCurrent();return hit;
        }
        BlockReadView blocks(){return blocks;}
    }
}
