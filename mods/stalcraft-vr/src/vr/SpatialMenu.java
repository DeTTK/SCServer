package vr;
import static vr.Math3.*;

/** A stationary world panel; opening the menu never makes it chase the HMD. */
final class SpatialMenu {
    static final double WIDTH=.72,HEIGHT=.432;
    record Hit(double x,double y,double distance){}
    Pose panel=Pose.ID;
    boolean open,recenter,inventory;
    static final int ROW_Y=104,ROW_STEP=48,ROW_HEIGHT=40,ROW_COUNT=4;
    int hovered=-1;
    Hit hit;
    private boolean menuHeld,triggerHeld;
    void update(Tracking t,GameSession game){
        inventory=false;
        boolean down=t.focused()&&t.left().valid()&&(t.left().secondary()||t.left().menu());
        if(down&&!menuHeld){open=!open;if(open)panel=t.head().compose(new Pose(new V(0,-.04,-.85),Q.ID));}
        menuHeld=down;
        if(!t.focused())open=false;
        game.menu=open;hit=null;hovered=-1;
        boolean click=t.focused()&&t.right().valid()&&t.right().trigger()>.65;
        if(open&&t.right().valid()){
            hit=intersect(panel,t.right().aim());
            if(hit!=null&&hit.x()>=32&&hit.x()<=608){int row=(int)((hit.y()-ROW_Y)/ROW_STEP);if(hit.y()>=ROW_Y&&row<ROW_COUNT&&hit.y()<ROW_Y+row*ROW_STEP+ROW_HEIGHT)hovered=row;}
            if(click&&!triggerHeld)switch(hovered){
                case 0->open=false;
                case 1->{recenter=true;open=false;}
                case 2->game.turnDegrees=game.turnDegrees==30?45:game.turnDegrees==45?60:30;
                case 3->{inventory=true;open=false;}
                default->{}
            }
        }
        triggerHeld=click;game.menu=open;
    }
    static Hit intersect(Pose panel,Pose ray){
        Pose local=panel.inverse().compose(ray);V o=local.p(),d=local.forward();
        if(d.z()>=-1e-5)return null;double distance=-o.z()/d.z();
        if(distance<.02||distance>5)return null;V p=o.add(d.mul(distance));
        if(Math.abs(p.x())>WIDTH/2||Math.abs(p.y())>HEIGHT/2)return null;
        return new Hit((p.x()/WIDTH+.5)*640,(.5-p.y()/HEIGHT)*384,distance);
    }
}
