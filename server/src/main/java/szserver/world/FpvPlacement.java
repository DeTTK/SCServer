package szserver.world;

/** Placement authorization only. Subsequent flight/collisions remain owned by the pilot client. */
public final class FpvPlacement {
    public static void validate(MapObstruction map,World.Vec operator,World.Vec position)throws InterruptedException{
        SceneCollision.worker();finite(operator);finite(position);double x=position.x()-operator.x(),y=position.y()-operator.y(),z=position.z()-operator.z();
        if(x*x+y*y+z*z>5.5*5.5)throw new IllegalArgumentException("fpv_placement_too_far");
        World.Vec eye=new World.Vec(operator.x(),operator.y()+1.5,operator.z());World.Vec delta=new World.Vec(position.x()-eye.x(),position.y()-eye.y(),position.z()-eye.z());double length=Math.sqrt(delta.x()*delta.x()+delta.y()*delta.y()+delta.z()*delta.z());
        if(length>0&&map.ray(eye,new World.Vec(delta.x()/length,delta.y()/length,delta.z()/length),length)!=MapObstruction.Hit.CLEAR)throw new IllegalArgumentException("fpv_placement_occluded");
        var support=map.ray(position,new World.Vec(0,-1,0),.42);if(support!=MapObstruction.Hit.SCENE&&support!=MapObstruction.Hit.BLOCK)throw new IllegalArgumentException("fpv_placement_unsupported");
        for(var direction:new World.Vec[]{new World.Vec(1,0,0),new World.Vec(-1,0,0),new World.Vec(0,1,0),new World.Vec(0,0,-1)})if(map.ray(position,direction,.23)!=MapObstruction.Hit.CLEAR)throw new IllegalArgumentException("fpv_placement_blocked");
        map.checkCurrent();
    }
    private static void finite(World.Vec v){if(v==null||!Double.isFinite(v.x())||!Double.isFinite(v.y())||!Double.isFinite(v.z()))throw new IllegalArgumentException("fpv_invalid_position");}
    private FpvPlacement(){}
}
