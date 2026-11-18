package szserver.net;

import szserver.client.ClientPackets;

/** Accepted client samples only. Estimates visual velocity without simulating player physics. */
public final class PlayerMotion {
    private double[] position,velocity=new double[3];
    private int tick,flags;
    private boolean ground=true;
    public double[] velocity(){return velocity.clone();}
    public double speed(){return Math.hypot(velocity[0],velocity[2]);}
    public boolean ground(){return ground;}
    public void input(ClientPackets.Input input,boolean acceptedPosition){
        if(input.motion().snapshot())ground=input.ground();
        else if(input.controlsPresent()&&flags!=input.controlFlags()&&(input.flag(1)||input.flag(5)))ground=false;
        if(input.controlsPresent())flags=input.controlFlags();
        // Controls without a position are not another stationary interpolation sample.
        if(!acceptedPosition)return;
        double[] next=input.position();
        if(input.velocity()!=null)velocity=input.velocity().clone();
        else if(position==null)velocity=new double[3];
        else{
            double seconds=Math.max(1,Math.min(20,(long)input.tick()-tick))/20.0;
            velocity=new double[]{(next[0]-position[0])/seconds,(next[1]-position[1])/seconds,(next[2]-position[2])/seconds};
        }
        position=next.clone();tick=input.tick();
    }
    public void reset(){position=null;velocity=new double[3];tick=flags=0;ground=true;}
}
