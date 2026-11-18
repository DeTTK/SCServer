package cinema;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A preset stores bone names, never a transient index into a particular weapon. */
final class Rig {
    static final List<String> SOURCES=List.of("TP персонаж","FP руки","FP оружие");
    int source=0; String bone=""; boolean follow=true;
    double x=0,y=.1,z=2.5,yaw=0,pitch=0,roll=0,fov=70;
    Pose.V offset() { return new Pose.V(x,y,z); }
    Pose.Q rotation() { return Pose.Q.euler(yaw,pitch,roll); }
    void reset() { x=0;y=0;z=source==0?2.5:.25;yaw=pitch=roll=0;fov=70; }
    Properties properties() {
        Properties p=new Properties(); p.setProperty("format","1");p.setProperty("source",Integer.toString(source));
        p.setProperty("bone",bone);p.setProperty("follow",Boolean.toString(follow));
        String[] keys={"x","y","z","yaw","pitch","roll","fov"};double[] v={x,y,z,yaw,pitch,roll,fov};
        for(int i=0;i<keys.length;i++)p.setProperty(keys[i],Double.toString(v[i]));return p;
    }
    static Rig read(Path file) throws IOException {
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(file)){p.load(r);}return parse(p);
    }
    static Rig parse(Properties p) throws IOException {
        if(!"1".equals(p.getProperty("format")))throw new IOException("Unsupported preset version");
        Rig r=new Rig();double source=num(p,"source",0,0,2);if(source!=Math.rint(source))throw new IOException("Invalid source index");
        r.source=(int)source;r.bone=p.getProperty("bone","");
        if(r.bone.length()>256)throw new IOException("Bone name too long");
        String follow=p.getProperty("follow","true");if(!follow.equalsIgnoreCase("true")&&!follow.equalsIgnoreCase("false"))throw new IOException("Invalid follow flag");
        r.follow=Boolean.parseBoolean(follow);
        r.x=num(p,"x",0,-20,20);r.y=num(p,"y",0,-20,20);r.z=num(p,"z",2.5,-20,20);
        r.yaw=num(p,"yaw",0,-180,180);r.pitch=num(p,"pitch",0,-180,180);r.roll=num(p,"roll",0,-180,180);
        r.fov=num(p,"fov",70,20,120);return r;
    }
    static double num(Properties p,String key,double fallback,double low,double high) throws IOException {
        try { double v=Double.parseDouble(p.getProperty(key,""+fallback));
            if(!Double.isFinite(v)||v<low||v>high)throw new NumberFormatException();return v;
        }catch(NumberFormatException e){throw new IOException("Invalid preset field: "+key,e);}
    }
    void save(Path file) throws IOException {
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        try(Writer w=Files.newBufferedWriter(temp)){properties().store(w,"Cinematic camera preset v1; metres and degrees");}
        try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
