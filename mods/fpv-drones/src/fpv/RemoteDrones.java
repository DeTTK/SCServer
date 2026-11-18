package fpv;
import java.nio.*;
import java.util.*;
import szmods.api.ModContext;
import fpv.FpvDroneMod.Vec3;
import fpv.FpvDroneMod.Quat;

/** Observer replicas cannot capture input or become the local control target. */
final class RemoteDrones {
    static final class Entity {
        Vec3 position,previous; Quat rotation,previousRotation;
        final double[] motors=new double[4];
        final float[] propellerAngles=new float[4]; long lastRender;
        void animate(long now){double dt=lastRender==0?0:Math.min(.1,(now-lastRender)/1e9);lastRender=now;for(int i=0;i<4;i++)propellerAngles[i]=(float)((propellerAngles[i]+dt*motors[i]*1400*(i%2==0?1:-1))%(Math.PI*2));}
        long received; int sequence=-1; double interpolationNanos=50_000_000.; final DroneAudio audio=new DroneAudio();
        Vec3 at(long now) {double t=Math.min(1,Math.max(0,now-received)/interpolationNanos);return previous.add(position.subtract(previous).scale(t));}
        Quat attitude(long now) {
            double t=Math.min(1,Math.max(0,now-received)/interpolationNanos);
            Quat a=previousRotation,b=rotation;
            double sign=a.w()*b.w()+a.x()*b.x()+a.y()*b.y()+a.z()*b.z()<0?-1:1;
            return new Quat(a.w()*(1-t)+b.w()*t*sign,a.x()*(1-t)+b.x()*t*sign,a.y()*(1-t)+b.y()*t*sign,a.z()*(1-t)+b.z()*t*sign).normalized();
        }
    }
    final Map<String,Entity> entities=new LinkedHashMap<>();
    void accept(byte[] payload) {
        ByteBuffer b=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);b.position(5);
        String id=new UUID(b.getLong(),b.getLong()).toString();int sequence=b.getInt();
        Quat q=new Quat(b.getFloat(),b.getFloat(),b.getFloat(),b.getFloat()).normalized();
        Vec3 p=new Vec3(b.getFloat(),b.getFloat(),b.getFloat());
        b.position(71);int n=b.get()&255;byte[] event=new byte[n];b.get(event);
        String name=new String(event,java.nio.charset.StandardCharsets.US_ASCII);
        if(name.equals("exploded")||name.equals("recalled")){Entity old=entities.remove(id);if(old!=null)old.audio.stop();return;}
        long now=System.nanoTime();Entity e=entities.get(id);
        if(e==null){if(entities.size()>=32)return;e=new Entity();e.position=e.previous=p;e.rotation=e.previousRotation=q;entities.put(id,e);}
        else {if(sequence<=e.sequence)return;e.previous=e.at(now);e.previousRotation=e.attitude(now);e.interpolationNanos=Math.max(50_000_000.,Math.min(150_000_000.,e.interpolationNanos*.75+(now-e.received)*.25));}
        e.position=p;e.rotation=q;e.received=now;e.sequence=sequence;
        for(int i=0;i<4;i++)e.motors[i]=b.getFloat();
    }
    void update(Vec3 listener,double volume,ModContext log) {
        long now=System.nanoTime();
        var it=entities.values().iterator();
        while(it.hasNext()) {
            Entity e=it.next();
            if(now-e.received>2_000_000_000L){e.audio.stop();it.remove();continue;}
            double distance=e.at(now).subtract(listener).length();
            double gain=distance>=90?0:volume/(1+distance*distance/16);
            if(Arrays.stream(e.motors).max().orElse(0)<.001)gain=0;
            e.audio.update(e.motors,0,gain,log);
        }
    }
    void clear(){for(Entity e:entities.values())e.audio.stop();entities.clear();}
}
