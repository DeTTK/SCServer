package vr;
import static vr.Math3.*;

/** Native movement request; displacement remains owned by the game's collision solver. */
public final class ControllerIntent {
    public record Move(float direction,String gait,boolean jump,boolean crouchPressed,boolean crouch){}
    private boolean jumpHeld,crouchHeld,turnHeld;
    public Move movement(Tracking t,Pose worldHead,Q actorHeading,boolean menu){
        return movement(t,worldHead,actorHeading,menu,false);
    }
    public Move movement(Tracking t,Pose worldHead,Q actorHeading,boolean menu,boolean physicalCrouch){
        boolean live=t!=null&&t.focused()&&!menu;
        boolean jump=live&&t.right().valid()&&t.right().primary();
        boolean crouch=live&&(physicalCrouch||t.right().valid()&&t.right().stickY()<-.7);
        Move result;
        if(live&&t.left().valid()){
            double x=t.left().stickX(),y=t.left().stickY(),m=Math.hypot(x,y);
            V forward=worldHead.forward();forward=new V(forward.x(),0,forward.z()).unit();V right=forward.cross(new V(0,1,0));
            V local=actorHeading.inverse().rotate(right.mul(x).add(forward.mul(y)));
            String gait=m<.18?"NONE":m<.5?"WALK":t.left().stickClick()&&y>.6?"SPRINT":"JOG";
            result=new Move((float)Math.toDegrees(Math.atan2(local.x(),-local.z())),gait,jump&&!jumpHeld,crouch&&!crouchHeld,crouch);
        }else result=new Move(0,"NONE",jump&&!jumpHeld,crouch&&!crouchHeld,crouch);
        jumpHeld=jump;crouchHeld=crouch;return result;
    }
    public double turn(Tracking t,boolean menu,double degrees){
        double x=t!=null&&t.focused()&&!menu&&t.right().valid()?t.right().stickX():0;
        if(Math.abs(x)<.25)turnHeld=false;
        if(Math.abs(x)>.7&&!turnHeld){turnHeld=true;return -Math.copySign(Math.toRadians(degrees),x);}return 0;
    }
    public void reset(){jumpHeld=crouchHeld=turnHeld=false;}
}
