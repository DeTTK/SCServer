package szserver.client;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import szserver.net.Wire;
import szserver.world.World;

/** Native primary explosive-ammunition shot. Its empty body carries no client hit report. */
public final class LauncherPackets {
    public static final int ID=1788;
    public static final String REQUEST="612627356";
    public static void verifyRelease(Path root,Path runtime)throws Exception{
        verifyManifest(root,runtime,"native-launcher.json");
    }
    public static void verifyManifest(Path root,Path runtime,String fixture)throws Exception{
        var pins=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(root.resolve("fixtures/"+fixture))).getAsJsonObject();
        for(var entry:pins.getAsJsonObject("classHashes").entrySet()){
            String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IOException("launcher_release_class");
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(name+".class"))));
            if(!hash.equals(entry.getValue().getAsString()))throw new IOException("launcher_native_release_changed:"+name);
        }
    }
    public static void read(byte[] body)throws IOException{if(body.length!=0)throw new IOException("launcher_request_size");}
    /** Native launcher controller uses full start speed, in metres per native 20 Hz tick. */
    public static World.Vec velocity(GrenadePackets.Profile p,float yaw,float pitch){
        double y=Math.toRadians(yaw),a=Math.toRadians(Math.max(-90,p.absolute()?p.absolutePitch():pitch+p.pitchOffset())),speed=p.speed()*20;
        return new World.Vec(-Math.sin(y)*Math.cos(a)*speed,-Math.sin(a)*speed,Math.cos(y)*Math.cos(a)*speed);
    }
    public static byte[] fired(int entity,double rpm){return Wire.dynamic("1176115659",Wire.bytes(out->{out.writeInt(entity);out.writeBoolean(true);out.writeFloat((float)(60/rpm));}));}
    private LauncherPackets(){}
}
