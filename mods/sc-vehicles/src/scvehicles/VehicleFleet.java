package scvehicles;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import scvehicles.protocol.VehicleTypes;
import scvehicles.protocol.VehicleSeats;

/** Small measured profiles; models and textures are always read from SC resources. */
final class VehicleFleet {
    static final class Extra {String asset;double[] offset;Map<String,String> textures;}
    static final class Door {double x;double[] hinge,sideX,rightHinge;double[][] outline;boolean left=true,right=true;}
    private static final class Seating {Door rearDoor;}
    static final class Type {
        private transient double[][] sweepCache;private transient VehicleHull.Box[] hullCache;
        int id,direction,seatIndex;String name,asset,kind;double scale,width,height,length,cg,maxSpeed,acceleration,footwellFloor,seatRecline;
        double[] origin,seat,steering;Door door,rearDoor;double[][] wheels,boxes,glassWindows;Map<String,String> textures;Extra[] extras;
        boolean tracked(){return kind.equals("tracked");}
        double arcade(){return switch(kind){case "car"->1;case "van"->.75;case "toy"->.6;case "armored"->.35;case "truck","bus"->.25;case "tractor"->.15;default->0;};}
        double wheelbase(){return Arrays.stream(wheels).mapToDouble(w->w[2]).max().orElse(1)-Arrays.stream(wheels).mapToDouble(w->w[2]).min().orElse(-1);}
        double track(){return Arrays.stream(wheels).mapToDouble(w->w[0]).max().orElse(.65)-Arrays.stream(wheels).mapToDouble(w->w[0]).min().orElse(-.65);}
        double[] inertia(){if(id==0){double f=nivaUnits();return new double[]{1.05*f*f,1.28*f*f,.43*f*f};}return new double[]{(length*length+height*height)/12,(length*length+width*width)/12,(width*width+height*height)/12};}
        double steering(double steer,double vx,double vz){double grip=6+4*arcade();return steer*Math.atan(wheelbase()*grip/(vx*vx+vz*vz+wheelbase()*grip/Math.tan(Math.toRadians(40))));}
        VehicleHull.Box[] hull(){if(hullCache!=null)return hullCache;return hullCache=Arrays.stream(boxes).map(b->new VehicleHull.Box(b[0],b[1],b[2],b[3],b[4],b[5],b[6])).toArray(VehicleHull.Box[]::new);}
        double[][] points(){
            if(id==0)return VehiclePhysics.POINTS;
            List<double[]> out=new ArrayList<>();for(double[] w:wheels)out.add(new double[]{w[0],0,w[2]});
            for(double[] sample:sweepHull())out.add(new double[]{sample[0],sample[1],sample[2]});
            return out.toArray(double[][]::new);
        }
        double pointRadius(int index){return id==0||index<wheels.length?0:sweepHull()[index-wheels.length][3];}
        double[][] sweepHull(){
            if(sweepCache!=null)return sweepCache;
            List<double[]> out=new ArrayList<>();
            for(VehicleHull.Box b:hull()){
                double r=Math.min(.23,Math.min(b.hx(),b.hz()));
                out.add(new double[]{b.x(),b.y()-b.hy()+r,b.z(),r});
                if(b.hy()*2>r*2.5)out.add(new double[]{b.x(),b.y()+b.hy()-r,b.z(),r});
            }return sweepCache=out.toArray(double[][]::new);
        }
    }
    static final Type[] ALL=load();
    private static Type[] load(){try(InputStream in=VehicleFleet.class.getResourceAsStream("/scvehicles/fleet.json")){
        if(in==null)throw new FileNotFoundException("fleet.json");
        Type[] list=new Gson().fromJson(new InputStreamReader(in,StandardCharsets.UTF_8),Type[].class);
        if(list.length!=VehicleTypes.COUNT)throw new IllegalStateException("fleet wire registry mismatch");
        for(int i=0;i<list.length;i++)if(list[i].id!=i||list[i].scale!=1||list[i].wheels.length<4||list[i].boxes.length==0)throw new IllegalStateException("invalid native-scale vehicle profile "+i);
        try(var seats=VehicleFleet.class.getResourceAsStream("/scvehicles/seating.json")){if(seats!=null){Seating[] profiles=new Gson().fromJson(new InputStreamReader(seats,StandardCharsets.UTF_8),Seating[].class);if(profiles.length!=list.length)throw new IOException("seat profile count");for(int i=0;i<list.length;i++)list[i].rearDoor=profiles[i].rearDoor;}}
        return list;
    }catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static Type get(int id){if(id<0||id>=ALL.length)throw new IllegalArgumentException("vehicle type");return ALL[id];}
    private static final Type[][] SEATED=new Type[VehicleTypes.COUNT][];
    static Type forSeat(int model,int seat){
        if(!VehicleSeats.valid(model,seat))throw new IllegalArgumentException("vehicle_seat");if(seat==0)return get(model);
        if(SEATED[model]==null)SEATED[model]=new Type[VehicleSeats.COUNT[model]];
        if(SEATED[model][seat]!=null)return SEATED[model][seat];
        Type base=get(model),copy=new Type();try{for(var field:Type.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())&&!java.lang.reflect.Modifier.isTransient(field.getModifiers()))field.set(copy,field.get(base));}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        copy.seatIndex=seat;copy.seat=VehicleSeats.seat(model,seat);copy.steering=null;if(VehicleSeats.PANEL[model][seat]>=2)copy.door=base.rearDoor;return SEATED[model][seat]=copy;
    }
    // Convert the authored Niva contact/door coordinates once to native asset units.
    static double nivaUnits(){return get(0).length/3.74;}
    static double[][] nivaPoints(double[][] points){double f=nivaUnits();for(double[] p:points)for(int i=0;i<p.length;i++)p[i]*=f;return points;}
    private VehicleFleet(){}
}
