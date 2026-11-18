package szserver.net;

import szserver.client.ClientPackets;

/** Native locomotion owns body geometry; custom animation names are presentation only. */
public final class PlayerPosture {
    private int locomotion,previous;
    private boolean sampled;
    public int locomotion(){return locomotion;}
    public int previous(){return previous;}
    public void input(ClientPackets.Input input){input(input,input.ground());}
    public void input(ClientPackets.Input input,boolean ground){
        if(input.motion().snapshot()){
            int next=input.locomotion(),prior=input.previous();
            set(next,prior);sampled=true;
        }else if(input.controlsPresent()){
            var actions=input.locomotionActions();
            int next=actions.contains("zipline")?12:
                locomotion==12&&!actions.contains("zipline_destruct")&&!actions.contains("spawn-barrier-stop")?12:
                input.flag(5)?10:input.flag(4)?7:input.flag(2)?1:input.flag(1)||!ground?6:0;
            int prior=sampled?(next==locomotion?previous:locomotion):next;set(next,prior);sampled=true;
        }
    }
    public void set(int locomotion,int previous){
        height(locomotion);height(previous);this.locomotion=locomotion;this.previous=previous;
    }
    public void reset(){set(0,0);sampled=false;}
    public static double height(int locomotion){
        if(locomotion<0||locomotion>12)throw new IllegalArgumentException("player_locomotion_invalid");
        return switch(locomotion){case 1->1.25;case 7,9->.55;default->1.8;};
    }
    public static double[] eye(double[] position,int locomotion){
        return new double[]{position[0],position[1]+height(locomotion)-.18,position[2]};
    }
    public static double[] closestPoint(double[] eye,double[] position,int locomotion){
        return new double[]{Math.max(position[0]-.3,Math.min(position[0]+.3,eye[0])),
            Math.max(position[1],Math.min(position[1]+height(locomotion),eye[1])),
            Math.max(position[2]-.3,Math.min(position[2]+.3,eye[2]))};
    }
    public static double[] closestPoint(double[] eye,szserver.world.SceneCollision.Box body){return new double[]{Math.max(body.x(),Math.min(body.xx(),eye[0])),Math.max(body.y(),Math.min(body.yy(),eye[1])),Math.max(body.z(),Math.min(body.zz(),eye[2]))};}
    /** Nearest reachable segment in the attack arc; an invalid near segment must not hide another. */
    public static double[] meleePoint(double[] eye,java.util.List<szserver.world.SceneCollision.Box> parts,double[] forward,MeleeProfile profile){
        double nearest=Double.POSITIVE_INFINITY;double[] result=null;
        for(var part:parts){double[] point=closestPoint(eye,part);double distance=meleeDistance(eye,point,forward,profile);if(distance>=0&&distance<nearest){nearest=distance;result=point;}}
        return result;
    }
    public static double meleeDistance(double[] eye,double[] point,double[] forward,MeleeProfile profile){
        double squared=0;for(int i=0;i<3;i++)squared+=(point[i]-eye[i])*(point[i]-eye[i]);
        double distance=Math.sqrt(squared);if(distance<.0001||distance>profile.reach())return -1;
        double facing=0;for(int i=0;i<3;i++)facing+=forward[i]*(point[i]-eye[i])/distance;
        return facing<Math.cos(profile.angle())?-1:distance;
    }
}
