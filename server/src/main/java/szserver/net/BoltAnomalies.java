package szserver.net;

import szserver.world.AnomalyZone;
import szserver.world.World;
import java.util.*;
import java.util.function.Predicate;

/** Thrown bolts trigger the first anomaly whose native shape contains the bolt point. */
public final class BoltAnomalies {
    private static final double WIDTH=.15,HEIGHT=.4;
    private static final double BOLT_RADIUS=.125;
    public record Impact(AnomalyZone zone,double time,World.Vec point,World.Vec rebound){}
    private BoltAnomalies(){}
    public static Impact funnelImpact(List<AnomalyZone> zones,World.Vec from,World.Vec to){
        double sx=to.x()-from.x(),sy=to.y()-from.y(),sz=to.z()-from.z();
        double length=sx*sx+sy*sy+sz*sz;
        if(length<1e-12)return null;
        Impact first=null;
        for(AnomalyZone zone:zones){
            if(!zone.kind().equals("funnel"))continue;
            World.Vec center=zone.center();
            double dx=from.x()-center.x(),dy=from.y()-center.y(),dz=from.z()-center.z();
            double radius=zone.radius()+BOLT_RADIUS;
            double approach=dx*sx+dy*sy+dz*sz;
            double outside=dx*dx+dy*dy+dz*dz-radius*radius;
            if(approach>=0)continue;
            double discriminant=approach*approach-length*outside;
            if(discriminant<0)continue;
            double time=Math.max(0,(-approach-Math.sqrt(discriminant))/length);
            if(time>1||first!=null&&first.time()<=time)continue;
            World.Vec point=new World.Vec(from.x()+sx*time,from.y()+sy*time,from.z()+sz*time);
            // The native funnel kicks outwards from its centre and adds 0.2 blocks/tick upwards.
            World.Vec rebound=new World.Vec((point.x()-center.x())*5,(point.y()-center.y())*5+4,(point.z()-center.z())*5);
            first=new Impact(zone,time,point,rebound);
        }
        return first;
    }
    public static Impact trampolineImpact(List<AnomalyZone> zones,World.Vec from,World.Vec to,
                                           World.Vec velocity,Predicate<AnomalyZone> ready){
        if(from.equals(to))return null;
        World.Vec start=new World.Vec(from.x(),from.y()-BOLT_RADIUS,from.z());
        World.Vec finish=new World.Vec(to.x(),to.y()-BOLT_RADIUS,to.z());
        double height=2*BOLT_RADIUS;
        Impact first=null;
        for(var zone:zones){
            if(!zone.kind().equals("tramp")||!ready.test(zone)
                    ||!zone.nearby(start,finish,BOLT_RADIUS,height)
                    ||!zone.touches(start,finish,BOLT_RADIUS,height))continue;
            double low=0,high=0;
            if(!zone.touches(start,start,BOLT_RADIUS,height)){
                high=1;
                for(int i=0;i<14;i++){
                    double mid=(low+high)/2;
                    if(zone.touches(start,interpolate(start,finish,mid),BOLT_RADIUS,height))high=mid;
                    else low=mid;
                }
            }
            if(first!=null&&first.time()<=high)continue;
            // The native trampoline applies its transformed 0.74-block/tick impulse
            // according to the instance's replace/add velocity setting.
            double[] impulse=zone.impulse();
            World.Vec rebound=zone.settings().setVelocity()
                ?new World.Vec(impulse[0],impulse[1],impulse[2])
                :new World.Vec(velocity.x()+impulse[0],velocity.y()+impulse[1],velocity.z()+impulse[2]);
            first=new Impact(zone,high,interpolate(from,to,high),rebound);
        }
        return first;
    }

    private static World.Vec interpolate(World.Vec from,World.Vec to,double time){
        return new World.Vec(from.x()+(to.x()-from.x())*time,from.y()+(to.y()-from.y())*time,
            from.z()+(to.z()-from.z())*time);
    }

    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec point){return containing(zones,point,point);}
    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec from,World.Vec to){
        World.Vec low=new World.Vec(from.x(),from.y()-HEIGHT,from.z()),high=new World.Vec(to.x(),to.y()+HEIGHT,to.z());
        for(AnomalyZone zone:zones)if(!zone.kind().equals("tramp")&&zone.touches(low,high,WIDTH,HEIGHT))return zone;return null;
    }
}
