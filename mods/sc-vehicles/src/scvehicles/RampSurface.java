package scvehicles;

import java.util.*;

/** Symmetric convolution of connected upright wedges. No temporal pose correction. */
final class RampSurface {
    static final double RADIUS=1/(2*Math.tan(Math.toRadians(22.5))),LIMIT=.30;
    record Patch(int x,int y,int z,int axis,int sign) {
        double start(){return axis==0?x:z;}
        int lane(){return axis==0?z:x;}
        double left(){return y+(sign<0?1:0);}
        double right(){return y+(sign>0?1:0);}
        double coordinate(double x,double z){double t=(axis==0?x:z)-start();return sign>0?t:1-t;}
        double height(double x,double z){return y+clamp(coordinate(x,z),0,1);}
        double delta(double x,double z){double r=coordinate(x,z);return (integral(r+RADIUS)-integral(r-RADIUS))/(2*RADIUS)-clamp(r,0,1);}
        double distance(double x,double z){double t=(axis==0?x:z)-start();return t<0?-t:t>1?t-1:0;}
    }
    private final List<List<Patch>> groups=new ArrayList<>();
    private final RampIndex<List<Patch>> index=new RampIndex<>();
    private final RampCornerSurface corners=new RampCornerSurface();
    void corners(Collection<RampCornerSurface.Patch> patches){corners.set(patches);}
    int cornerCount(){return corners.size();}
    Map<Object,RampProfileDependencies.Region> dependencies(){
        Map<Object,RampProfileDependencies.Region> out=new HashMap<>();double r=RADIUS+.00001;
        for(List<Patch> group:groups){
            double x=Double.POSITIVE_INFINITY,z=x,X=Double.NEGATIVE_INFINITY,Z=X;
            for(Patch p:group){x=Math.min(x,p.x);z=Math.min(z,p.z);X=Math.max(X,p.x+1);Z=Math.max(Z,p.z+1);}
            // Changing a connected straight group can also change its special
            // two-block crest treatment, so invalidate the entire group.
            out.put(Set.copyOf(group),new RampProfileDependencies.Region(x-r,z-r,X+r,Z+r));
        }
        corners.dependencies(out);return out;
    }
    void set(Collection<Patch> patches){
        groups.clear();index.clear();corners.set(List.of());Set<Patch> todo=new LinkedHashSet<>(patches);
        while(!todo.isEmpty()){
            Patch first=todo.iterator().next();todo.remove(first);List<Patch> group=new ArrayList<>();group.add(first);
            for(int i=0;i<group.size();i++)for(var it=todo.iterator();it.hasNext();){Patch b=it.next(),a=group.get(i);
                if(a.axis==b.axis&&a.lane()==b.lane()&&(Math.abs(a.start()+1-b.start())<.01&&Math.abs(a.right()-b.left())<.01||Math.abs(b.start()+1-a.start())<.01&&Math.abs(b.right()-a.left())<.01)){group.add(b);it.remove();}
            }
            groups.add(group);
            double min=group.stream().mapToDouble(Patch::start).min().orElseThrow()-RADIUS-.00001,max=group.stream().mapToDouble(Patch::start).max().orElseThrow()+1+RADIUS+.00001;
            if(first.axis==0)index.add(group,min,first.lane()-.00001,max,first.lane()+1.00001);else index.add(group,first.lane()-.00001,min,first.lane()+1.00001,max);
        }
    }
    boolean empty(){return groups.isEmpty()&&corners.size()==0;}
    int size(){return groups.stream().mapToInt(List::size).sum()+corners.size();}
    double shift(double x,double z,double top){
        double bestDistance=Double.POSITIVE_INFINITY,result=0;
        for(List<Patch> group:index.at(x,z)){
            Patch first=group.get(0);double across=first.axis==0?z:x;
            if(across<first.lane()-1e-7||across>first.lane()+1+1e-7)continue;
            double nearest=Double.POSITIVE_INFINITY;Patch source=null;
            for(Patch p:group){double d=p.distance(x,z);if(d<nearest){nearest=d;source=p;}}
            // A wall above this road, a lower floor, and a separate bridge remain solid.
            double original=source.height(x,z);
            boolean rampColumn=nearest<1e-7&&top>source.y+.001&&top<=original+.16;
            if(nearest>RADIUS||!rampColumn&&Math.abs(top-original)>.16||nearest>=bestDistance)continue;
            // Bring the native 1/8m treads to the same continuous support plane.
            // Lower layers stay below that plane and cannot punch through a buried crest.
            double virtual=groupHeight(group,x,z);
            result=clamp(virtual-top,-LIMIT,LIMIT);bestDistance=nearest;
        }
        return corners.shift(x,z,top,result);
    }
    boolean near(double x,double z,double top){return Math.abs(shift(x,z,top))>1e-6;}
    boolean supports(double x,double z,double height){
        for(List<Patch> group:index.at(x,z)){
            Patch first=group.get(0);double across=first.axis==0?z:x;
            if(across<first.lane()-1e-7||across>first.lane()+1+1e-7)continue;
            double distance=Double.POSITIVE_INFINITY;
            for(Patch p:group)distance=Math.min(distance,p.distance(x,z));
            if(distance<=RADIUS&&Math.abs(height-groupHeight(group,x,z))<.18)return true;
        }
        return corners.contact(x,z,height,null)!=null;
    }
    record Contact(double height,double nx,double ny,double nz){}
    Contact contact(double x,double z,double bottom){
        return corners.contact(x,z,bottom,axialContact(x,z,bottom));
    }
    private Contact axialContact(double x,double z,double bottom){
        for(List<Patch> group:index.at(x,z)){
            Patch first=group.get(0);double across=first.axis==0?z:x;
            if(across<first.lane()-1e-7||across>first.lane()+1+1e-7)continue;
            double distance=Double.POSITIVE_INFINITY;for(Patch p:group)distance=Math.min(distance,p.distance(x,z));
            if(distance>RADIUS)continue;
            double height=groupHeight(group,x,z);if(Math.abs(height-bottom)>.24)continue;
            double epsilon=.01,gradient=first.axis==0?(groupHeight(group,x+epsilon,z)-groupHeight(group,x-epsilon,z))/(2*epsilon):(groupHeight(group,x,z+epsilon)-groupHeight(group,x,z-epsilon))/(2*epsilon);
            double length=Math.sqrt(1+gradient*gradient);
            return new Contact(height,first.axis==0?-gradient/length:0,1/length,first.axis==1?-gradient/length:0);
        }
        return null;
    }
    private static double groupHeight(List<Patch> group,double x,double z){
        double distance=Double.POSITIVE_INFINITY,delta=0;Patch source=null;
        for(Patch p:group){double d=p.distance(x,z);if(d<distance){distance=d;source=p;}delta+=p.delta(x,z);}
        double height=source.height(x,z)+clamp(delta,-LIMIT,LIMIT);
        if(group.size()==2){
            Patch a=group.get(0),b=group.get(1);
            if(a.start()>b.start()){Patch t=a;a=b;b=t;}
            if(a.sign!=b.sign&&a.y==b.y){
                // Clamping a convolved one-block crest to 30cm penetration
                // otherwise restores a sharp 45-degree face near its tip.
                // The cone extends that bounded tip at the same 22.5-degree
                // angle, without increasing the allowed burial or toe lift.
                double distanceFromTip=Math.abs((a.axis==0?x:z)-(a.start()+1));
                double slope=Math.tan(Math.PI/8);
                if(a.sign>0)height=Math.max(height,a.y+1-LIMIT-slope*distanceFromTip);
                else height=Math.min(height,a.y+LIMIT+slope*distanceFromTip);
            }
        }
        return height;
    }
    private static double integral(double r){return r<0?0:r<1?r*r*.5:r-.5;}
    private static double clamp(double n,double low,double high){return Math.max(low,Math.min(high,n));}
}
