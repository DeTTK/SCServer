package szserver.net;

import java.util.*;

/** Network-owner state. Baselines describe enqueued reliable frames, never unsent candidates. */
public final class PlayerReplication {
    public static final class State {
        public final int entity;
        private final double[] position;
        private final float yaw,pitch;
        private final byte[] animation;
        public State(int entity,double[] position,float yaw,float pitch,byte[] animation){
            if(entity<2||position.length!=3||animation.length>256)throw new IllegalArgumentException("replication_state");
            this.entity=entity;this.position=position.clone();this.yaw=yaw;this.pitch=pitch;this.animation=animation.clone();
        }
    }
    public record Delivery(byte[] frame,int records,Runnable accepted){}
    private final Map<Integer,State> delivered=new HashMap<>();
    private final Map<Integer,Integer> absoluteTicks=new HashMap<>();
    private int cursor;
    private long epoch;
    public void forget(int entity){delivered.remove(entity);absoluteTicks.remove(entity);epoch++;}
    public void reset(){delivered.clear();absoluteTicks.clear();cursor=0;epoch++;}
    public int retained(){return delivered.size();}
    private static boolean positionChanged(State a,State b){for(int i=0;i<3;i++)if((int)Math.ceil(a.position[i]*32)!=(int)Math.ceil(b.position[i]*32))return true;return false;}
    private static int angle(float v){return (int)(v*256d/360)&255;}
    public Delivery prepare(int tick,int self,List<State> states){
        List<State> visible=states.stream().filter(s->s.entity!=self).toList();if(visible.isEmpty())return null;
        Set<Integer> present=new HashSet<>();for(State state:visible)present.add(state.entity);delivered.keySet().retainAll(present);absoluteTicks.keySet().retainAll(present);
        int begin=Math.floorMod(cursor,visible.size()),count=Math.min(64,visible.size());
        List<byte[]> records=new ArrayList<>(count);List<State> updates=new ArrayList<>(count);Set<Integer> absolute=new HashSet<>();
        for(int n=0;n<count;n++){
            State state=visible.get((begin+n)%visible.size()),previous=delivered.get(state.entity);
            // Native actors bind/rebind their interpolation component asynchronously
            // around spawn and world-ready. Reliable transport alone cannot ensure
            // that the first absolute baseline was applied. Bound recovery to one
            // second with complete remote transforms; never correct the local pilot.
            Integer lastAbsolute=absoluteTicks.get(state.entity);
            boolean keyframe=previous==null||lastAbsolute==null||Integer.compareUnsigned(tick-lastAbsolute,20)>=0;
            boolean moved=keyframe||positionChanged(state,previous),turned=keyframe||angle(state.yaw)!=angle(previous.yaw)||angle(state.pitch)!=angle(previous.pitch);
            boolean animationChanged=keyframe||!Arrays.equals(previous.animation,state.animation);
            if(!moved&&!turned&&!animationChanged)continue;
            records.add(GamePackets.movementRecord(state.entity,moved?state.position:null,keyframe?null:previous.position,turned?state.yaw:null,turned?state.pitch:null,state.animation));
            if(keyframe)absolute.add(state.entity);
            updates.add(state);
        }
        int next=(begin+count)%visible.size();
        if(records.isEmpty()){cursor=next;return null;}
        long expected=epoch;
        return new Delivery(GamePackets.movements(tick,records),count,()->{
            if(epoch!=expected)throw new IllegalStateException("replication_delivery_expired");
            for(State state:updates)delivered.put(state.entity,state);for(int entity:absolute)absoluteTicks.put(entity,tick);cursor=next;epoch++;
        });
    }
}
