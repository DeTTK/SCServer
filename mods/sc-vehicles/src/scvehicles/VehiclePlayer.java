package scvehicles;
import static scvehicles.NativeAccess.*;

/** Commit the rider position to both the actor and native locomotion prediction.
 * Moving only the rendered entity lets the controller restore its old foot position. */
final class VehiclePlayer {
    static Object state(Object player)throws Exception{return call(call(Class.forName("1704556748"),"return","1704556748",player),"if","1943200621");}
    static double[] feet(Object state)throws Exception{return new double[]{(Double)call(state,"continue","double"),(Double)call(state,"protected","double"),(Double)call(state,"public","double")};}
    static double[] actorFeet(Object player)throws Exception {
        double[] coordinates=(double[])call(Class.forName("scvehicles.NativePlayerCoordinates"),"read","[D",player);
        return new double[]{coordinates[3],coordinates[4],coordinates[5]};
    }
    /** Observer rendering only: no local prediction, mouse look or network buffer writes. */
    static void remotePosition(Object actor,double[] target)throws Exception {
        Class<?> codec=Class.forName("scvehicles.NativePlayerCoordinates");
        double[] coordinates=(double[])call(codec,"read","[D",actor);
        double dx=target[0]-coordinates[3],dy=target[1]-coordinates[4],dz=target[2]-coordinates[5];
        for(int i=0;i<6;i++)coordinates[i]=target[i%3];
        call(codec,"write","void",actor,coordinates);
        call(call(actor,"static","242639986"),"default","242639986",dx,dy,dz);
    }
    /** Translate the native prediction without overwriting look, stance or walking velocity. */
    static double[] carry(Object player,Object state,double[] movement)throws Exception {
        if(movement[0]==0&&movement[1]==0&&movement[2]==0)return feet(state);
        Object box=call(call(state,"continue","242639986"),"long","242639986");
        double[] before={(Double)call(box,"implements","double"),(Double)call(box,"switch","double"),(Double)call(box,"goto","double")};
        call(Class.forName("251809992"),"else","int",player,box,movement[0],movement[1],movement[2],0f,false);
        double[] after={(Double)call(box,"implements","double"),(Double)call(box,"switch","double"),(Double)call(box,"goto","double")};
        double[] accepted=new double[3];
        for(int i=0;i<3;i++)accepted[i]=after[i]-before[i];
        translate(player,state,accepted);return feet(state);
    }
    /** Add platform displacement to both interpolation endpoints, independently.
     * A full prediction commit also restores stale mouse angles and collapses walking. */
    static void translate(Object player,Object state,double[] d)throws Exception {
        if(d[0]==0&&d[1]==0&&d[2]==0)return;
        Class<?> codec=Class.forName("scvehicles.NativePlayerCoordinates");
        double[] actor=(double[])call(codec,"read","[D",player);
        for(int i=0;i<6;i++)actor[i]+=d[i%3];
        call(codec,"write","void",player,actor);
        Class<?> motion=Class.forName("1943200621");
        String[] fields={"s","t","u","o","p","q"};
        for(int i=0;i<6;i++){
            var field=motion.getField(fields[i]);field.setDouble(state,field.getDouble(state)+d[i%3]);
        }
        // Translate existing capsules in place; rebuilding the prediction capsule
        // clears its native contact list, which walking still needs this frame.
        Object prediction=call(state,"continue","242639986"),rendered=call(player,"static","242639986");
        call(prediction,"default","242639986",d[0],d[1],d[2]);
        if(rendered!=prediction)call(rendered,"default","242639986",d[0],d[1],d[2]);
    }
    static void position(Object player,double[] p,double yaw)throws Exception{
        position(player,p,(float)-Math.toDegrees(yaw),0f);
    }
    static void position(Object player,double[] p,float yaw,float pitch)throws Exception{
        call(player,"public","void",p[0],p[1],p[2],yaw,pitch);
        Object controller=call(Class.forName("1704556748"),"return","1704556748",player);
        Object state=call(controller,"if","1943200621");
        placeState(state,p,yaw,pitch);
        resetVelocity(state);
        call(state,"do","void",player);
        // Native interpolation reads separate previous look endpoints. Leaving
        // them at the pre-seat angles pulls free look back towards the old view.
        call(player,"package","void",yaw);
        call(player,"super","void",pitch);
    }
    static void placeState(Object state,double[] p,float yaw)throws Exception{
        placeState(state,p,yaw,0f);
    }
    static void placeState(Object state,double[] p,float yaw,float pitch)throws Exception{
        // goto updates both position endpoints and rebuilds the native capsule.
        call(state,"goto","void",p[0],p[1],p[2]);
        call(state,"int","void",yaw,pitch);
    }
    /** Absolute placement must not carry accumulated walking/falling velocity. */
    static void resetVelocity(Object state)throws Exception{for(String name:new String[]{"av","aw","ax"})state.getClass().getField(name).setDouble(state,0);}
    static void release(Object player,double[] feet,double[] velocity)throws Exception{position(player,feet,(Float)call(player,"catch","float"),(Float)call(player,"volatile","float"));Object motion=state(player);String[] axes={"av","aw","ax"};for(int i=0;i<3;i++)motion.getClass().getField(axes[i]).setDouble(motion,velocity[i]);call(motion,"do","void",player);}
    private VehiclePlayer(){}
}
