package szserver.net;

import szserver.client.FpvPackets;

/** Preserve native operator double precision; only FPV samples use the bridge's floats. */
public final class FpvView {
    public record Focus(int x,int z,boolean changed){}
    public static Focus update(ChunkWindow window,double[] operator,FpvPackets.State drone,boolean piloting){
        int ox=(int)Math.floor(operator[0]/16),oz=(int)Math.floor(operator[2]/16);
        int dx=drone==null?ox:(int)Math.floor(drone.position().x()/16d),dz=drone==null?oz:(int)Math.floor(drone.position().z()/16d);
        int x=drone!=null&&piloting?dx:ox,z=drone!=null&&piloting?dz:oz;
        Integer other=drone==null?null:piloting?ox:dx;int otherZ=drone==null?0:piloting?oz:dz;
        return new Focus(x,z,window.focus(x,z,other,otherZ,window.radius()));
    }
    private FpvView(){}
}
