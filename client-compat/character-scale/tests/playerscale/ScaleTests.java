package playerscale;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class ScaleTests {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static void rejected(Runnable action){try{action.run();throw new AssertionError("invalid scale accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] ignored)throws Exception {
        for(float f:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1,0,.249f,4.001f})rejected(()->new ScaleValue(f));
        double[] feet={100,64,-50};
        for(float factor:new float[]{.25f,.5f,1,2,4}){
            ScaleValue size=new ScaleValue(factor);double[] body=size.body(feet,.6,1.8);
            check(body[1]==64,"feet stay fixed");check(Math.abs(body[4]-64-1.8*factor)<1e-6,"body height scaled");
            double[] eye=size.point(feet,new double[]{0,1.62,0});check(Math.abs(eye[1]-64-1.62*factor)<1e-6,"eye shares feet anchor");
            ScaleProtocol.Snapshot s=new ScaleProtocol.Snapshot(ScaleProtocol.SET,42,8,size);check(ScaleProtocol.decode(s.encode()).equals(s),"native envelope payload roundtrip");
        }
        check(ScaleValue.nativeMobSize(5).factor()==1&&ScaleValue.nativeMobSize(10).factor()==2,"native mob factor convention");
        ScaleProtocol.hello(ScaleProtocol.hello());rejected(()->ScaleProtocol.hello(new byte[5]));rejected(()->ScaleProtocol.decode(new byte[23]));
        byte[] invalid=new ScaleProtocol.Snapshot(1,0,1,ScaleValue.NORMAL).encode();ByteBuffer.wrap(invalid).putFloat(18,Float.NaN);rejected(()->ScaleProtocol.decode(invalid));
        rejected(()->new ScaleProtocol.Snapshot(1,1,1,ScaleValue.NORMAL));rejected(()->new ScaleProtocol.Snapshot(2,2,1,new ScaleValue(2)));
        try{ScaleProtocol.read(new DataInputStream(new ByteArrayInputStream(new byte[4])));throw new AssertionError("truncation accepted");}catch(EOFException expected){checks++;}
        ScaleReplica replica=new ScaleReplica();Object first=new Object(),second=new Object();replica.connection(first);
        var bigger=new ScaleProtocol.Snapshot(1,42,8,new ScaleValue(2));
        check(replica.receive(first,bigger),"first authoritative assignment");check(!replica.receive(first,bigger),"duplicate ignored");
        check(!replica.receive(first,new ScaleProtocol.Snapshot(1,42,7,new ScaleValue(.5f))),"reordered old assignment ignored");
        check(replica.receive(first,new ScaleProtocol.Snapshot(2,42,9,ScaleValue.NORMAL)),"remove applied");check(!replica.receive(first,bigger)&&replica.scale(42).factor()==1,"remove cannot be resurrected by delayed packet");
        replica.receive(first,new ScaleProtocol.Snapshot(1,0,10,new ScaleValue(.5f)));check(replica.scale(0).factor()==.5f&&replica.scale(42).factor()==1,"independent local and remote dimensions");
        replica.connection(second);check(replica.retained()==0&&!replica.receive(first,bigger),"reconnect clears state and rejects old connection");
        replica.receive(second,new ScaleProtocol.Snapshot(1,42,1,new ScaleValue(.5f)));check(replica.scale(42).factor()==.5f,"reused entity ID in new connection starts fresh revision");
        replica.connection(null);check(replica.scale(42).factor()==1,"disconnect restores normal size");
        replica.connection(first);
        for(int i=2;i<5000;i++){check(replica.receive(first,new ScaleProtocol.Snapshot(2,i,i,ScaleValue.NORMAL)),"bounded removal history accepts current reliable stream");}
        check(replica.retained()==2048&&!replica.receive(first,new ScaleProtocol.Snapshot(1,2,2,new ScaleValue(2))),"expired tombstone never restores stale scale");
        System.out.println("Player scale core: "+checks+" PASS");
    }
}
