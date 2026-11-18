package szserver.world;
import java.util.*;
/** Ordinary route endpoints read from the game's cable builder, never a replacement flight model. */
public record ActorZipline(UUID identity,World.Vec first,World.Vec last,boolean forward,boolean backward,boolean vertical,double bodyOffset,double endOffset) {
    public ActorZipline(UUID identity,World.Vec first,World.Vec last,boolean forward,boolean backward){this(identity,first,last,forward,backward,false,ActorLadders.EYE,0);}
    public ActorZipline{Objects.requireNonNull(identity);Objects.requireNonNull(first);Objects.requireNonNull(last);if(!Double.isFinite(bodyOffset)||!Double.isFinite(endOffset)||endOffset<0)throw new IllegalArgumentException("actor_cable_offsets");}
    public record Route(UUID object,World.Vec grab,World.Vec finish,float yaw,World.Vec release,boolean vertical){
        public Route(UUID object,World.Vec grab,World.Vec finish,float yaw){this(object,grab,finish,yaw,new World.Vec(finish.x(),finish.y()-ActorLadders.EYE,finish.z()),false);}
        public float pitch(World.Vec feet){return (float)-Math.toDegrees(Math.atan2(grab.y()-feet.y()-ActorLadders.EYE,Math.hypot(grab.x()-feet.x(),grab.z()-feet.z())));}
    }
    public Route route(int direction){
        if(direction>0&&!forward||direction<0&&!backward)return null;
        var from=direction>0?first:last;var to=direction>0?last:first;
        double dx=to.x()-from.x(),dy=to.y()-from.y(),dz=to.z()-from.z(),length=Math.sqrt(dx*dx+dy*dy+dz*dz),inset=vertical?Math.min(endOffset,length*.49):0,ratio=length>1e-9?inset/length:0;
        var release=new World.Vec(to.x()-dx*ratio,to.y()-dy*ratio-bodyOffset,to.z()-dz*ratio);
        return new Route(identity,from,to,(float)Math.toDegrees(Math.atan2(from.x()-to.x(),to.z()-from.z())),release,vertical);
    }
    public List<Route> departures(World.Vec feet){
        var result=new ArrayList<Route>();for(int direction:new int[]{1,-1}){var route=route(direction);if(route!=null&&Math.sqrt(Math.pow(route.grab().x()-feet.x(),2)+Math.pow(route.grab().y()-feet.y()-ActorLadders.EYE,2)+Math.pow(route.grab().z()-feet.z(),2))<1.25)result.add(route);}return List.copyOf(result);
    }
}
