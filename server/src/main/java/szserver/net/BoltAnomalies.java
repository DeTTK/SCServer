package szserver.net;

import szserver.world.AnomalyZone;
import szserver.world.World;
import java.util.*;

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
    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec point){return containing(zones,point,point);}
    public static AnomalyZone containing(List<AnomalyZone> zones,World.Vec from,World.Vec to){
        World.Vec low=new World.Vec(from.x(),from.y()-HEIGHT,from.z()),high=new World.Vec(to.x(),to.y()+HEIGHT,to.z());
        for(AnomalyZone zone:zones)if(zone.touches(low,high,WIDTH,HEIGHT))return zone;return null;
    }
}
