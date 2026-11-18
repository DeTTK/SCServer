package szserver.net;
import java.util.*;
import szserver.client.FpvPackets;
import scvehicles.protocol.VehicleWire;

/** Keep native terrain available for dismounted client physics without moving the player view. */
public final class VehicleCoastView {
    public static FpvView.Focus update(ChunkWindow window,double[] operator,FpvPackets.State drone,boolean piloting,Collection<VehicleWire> coast){
        if(coast.isEmpty())return FpvView.update(window,operator,drone,piloting);
        int ox=(int)Math.floor(operator[0]/16),oz=(int)Math.floor(operator[2]/16),dx=drone==null?ox:(int)Math.floor(drone.position().x()/16d),dz=drone==null?oz:(int)Math.floor(drone.position().z()/16d);
        int x=drone!=null&&piloting?dx:ox,z=drone!=null&&piloting?dz:oz,r=window.radius();
        Integer other=drone==null?null:piloting?ox:dx;int otherZ=drone==null?0:piloting?oz:dz;
        int minX=other==null?Integer.MAX_VALUE:other-r,maxX=other==null?Integer.MIN_VALUE:other+r,minZ=other==null?Integer.MAX_VALUE:otherZ-r,maxZ=other==null?Integer.MIN_VALUE:otherZ+r;
        for(VehicleWire car:coast){int cx=(int)Math.floor(car.x/16),cz=(int)Math.floor(car.z/16),pad=Math.min(3,r);minX=Math.min(minX,cx-pad);maxX=Math.max(maxX,cx+pad);minZ=Math.min(minZ,cz-pad);maxZ=Math.max(maxZ,cz+pad);}
        int cx=(int)(((long)minX+maxX)/2),cz=(int)(((long)minZ+maxZ)/2),radius=Math.max(Math.max(maxX-cx,cx-minX),Math.max(maxZ-cz,cz-minZ));
        if(radius>Math.max(32,r))return FpvView.update(window,operator,drone,piloting); // Preserve long-range FPV priority and bounded auxiliary coverage.
        return new FpvView.Focus(x,z,window.focus(x,z,cx,cz,Math.max(1,radius)));
    }
    private VehicleCoastView(){}
}
