package szserver.world;

import java.io.IOException;

/** Worker-side extraction of reviewed native AABB feedback, separate from solid geometry. */
final class SceneFeedback {
    static void add(CollisionTree.Builder target,int x,int z,boolean container,int occurrence,
                    SceneObject object,SceneRegistry.Definition definition,CollisionBoxes boxes)throws IOException{
        // Other behavior families need their own state/shape rules; do not guess from property names.
        var effect=effect(object,definition,boxes);if(effect==null)return;
        var shape=boxes.feedbackShape(object,definition);
        if(shape!=null)target.add(new CollisionTree.Item(x,z,container,occurrence,shape,effect));
    }
    static ActorContacts.Obstacle effect(SceneObject object,SceneRegistry.Definition definition){
        if(definition==null||!definition.behaviour().equals("obstacle")||!object.feedbackActive())return null;
        var properties=definition.definition();return new ActorContacts.Obstacle(properties.has("deceleration")?properties.get("deceleration").getAsFloat():0);
    }
    static ActorContacts.Obstacle effect(SceneObject object,SceneRegistry.Definition definition,CollisionBoxes boxes){
        if(definition!=null&&definition.behaviour().equals("obstacle_living")&&object.feedbackActive())return boxes.livingObstacle(object,definition);
        return effect(object,definition);
    }
    private SceneFeedback(){}
}
