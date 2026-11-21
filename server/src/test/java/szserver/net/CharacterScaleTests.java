package szserver.net;

import com.google.gson.*;
import playerscale.*;
import szserver.world.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class CharacterScaleTests {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static void rejected(Runnable run){try{run.run();throw new AssertionError("bad command accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        check(CharacterScale.command("/scale .5").scale().factor()==.5f,"self command");
        check(CharacterScale.command("/scale \"two words\" 2").target().equals("two words"),"quoted target");
        check(CharacterScale.command("/scale reset").scale().factor()==1,"normal reset");
        for(String command:List.of("/scale","/scale NaN","/scale Infinity","/scale 0","/scale -1","/scale 5","/scale a b c"))rejected(()->CharacterScale.command(command));
        check(CharacterScale.saved(new JsonObject()).factor()==1,"legacy saves normal");
        JsonObject bad=new JsonObject();bad.addProperty(CharacterScale.SETTING,"broken");check(CharacterScale.saved(bad).factor()==1,"bad optional setting never blocks login");bad.add(CharacterScale.SETTING,JsonNull.INSTANCE);check(CharacterScale.saved(bad).factor()==1,"null optional setting never blocks login");
        check(CharacterScale.saved(CharacterScale.progress(new ScaleValue(2))).factor()==2,"saved scale roundtrip");
        byte[] movement=new byte[32];ByteBuffer.wrap(movement).putFloat(28,.8f);
        byte[] projected=PlayerMovementConfiguration.project(movement,Map.of("speed_modifier",20f,"sprint_speed_modifier",30f),1.4f),original=projected.clone();
        check(Arrays.equals(projected,PlayerMovementConfiguration.withCharacterScale(projected,1)),"normal speed config unchanged");
        for(float scale:new float[]{.25f,.5f,2,4}){
            byte[] scaled=PlayerMovementConfiguration.withCharacterScale(projected,scale);ByteBuffer m=ByteBuffer.wrap(scaled);
            check(Math.abs(m.getFloat(28)-.8f*scale)<1e-6,"native final speed coefficient scales");
            check(m.getFloat(1)==1.2f&&m.getFloat(24)==.3f&&m.getFloat(6)==1.4f,"equipment speed, sprint and stamina stay independent");
            for(int i=0;i<28;i++)check(scaled[i]==original[i],"unrelated native configuration bytes retained");
            check(Arrays.equals(scaled,PlayerMovementConfiguration.withCharacterScale(projected,scale)),"repeated snapshots do not compound scale");
        }
        check(Arrays.equals(projected,original),"base movement projection never mutated");
        var animation=new PlayerAnimation();animation.aim(true);animation.swing(1);
        byte[] ordinaryGraph=szserver.client.PlayerAnimationPackets.component(animation.snapshot(0,0,5,7.84,true,20,false));
        for(float scale:new float[]{.25f,.5f,1,2,4}){
            double physical=7.84*scale,graph=CharacterScale.animationSpeed(physical,scale);
            check(Math.abs(graph-7.84)<1e-8,"graph speed measured relative to character size");
            check(Arrays.equals(ordinaryGraph,szserver.client.PlayerAnimationPackets.component(animation.snapshot(0,0,5,graph,true,20,false))),"same movement emits identical graph packet without giant speed saturation");
        }
        Path directory=Files.createTempDirectory(Path.of(args[0]),"scale-wire-");
        try{
            byte[] template=new byte[220];ByteBuffer.wrap(template).putInt(8,162);Path file=directory.resolve("ready.b64");Files.writeString(file,Base64.getEncoder().encodeToString(template));
            GamePackets packets=new GamePackets(file);double[] pos={10,64,-15};byte[] config=new byte[32];
            byte[] normal=packets.ready(pos,17,30,10,config);check(Arrays.equals(normal,packets.ready(pos,17,30,10,config,1)),"normal native ready is byte-identical");
            for(float scale:new float[]{.25f,.5f,2,4}){
                byte[] frame=packets.ready(pos,17,30,10,config,scale);ByteBuffer b=ByteBuffer.wrap(frame);Wire.varint(b);Wire.varint(b);int name=b.getShort()&65535;b.position(b.position()+name);int base=b.position();
                check(b.getDouble(base+76)==64,"native ready feet anchor");check(Math.abs(b.getDouble(base+100)-b.getDouble(base+76)-1.8*scale)<1e-8,"native ready height");
                check(Math.abs(b.getDouble(base+92)-b.getDouble(base+68)-.6*scale)<1e-8,"native ready width");
                for(int i=0;i<normal.length;i++)if(i<base+68||i>=base+116)check(normal[i]==frame[i],"only native bounds changed");
            }
        }finally{try(var paths=Files.list(directory)){for(Path p:paths.toList())Files.delete(p);}Files.delete(directory);}
        Terrain ceiling=new Terrain(){public boolean solid(int x,int y,int z){return y==0||y==3;}public int voxel(int x,int y,int z){return solid(x,y,z)?16:0;}public void close(){}};
        World world=new World(ceiling);var feet=new World.Vec(.5,1,.5);
        check(world.nativePlacement(feet,.3,1.8)==1,"normal body fits below ceiling");check(world.nativePlacement(feet,.6,3.6)==2,"enlargement blocked by actual terrain cells");
        check(world.nativePlacement(feet,.15,.9)==1,"smaller body fits");
        check(Math.abs(CharacterScale.height(1,2)-3)<1e-6,"crouch uses native body height, not eye height");
        check(Math.abs(CharacterScale.eye(new double[]{0,64,0},1,2)[1]-66.5)<1e-6,"crouch eye independently scaled");
        var copy=new PlayerClones.Snapshot(new double[]{8,64,8},0,0,100,Map.of(),Map.of(),Map.of(),new byte[0],null,"stalker",false,0,2);
        check(copy.scale==2&&CharacterScale.height(copy.locomotion,copy.scale)>3.59,"clones inherit physical size");
        byte[] corpse=GamePackets.corpse(20,2,"x",new double[]{8,64,8},0,new double[3],null,new double[3],null);
        check(Arrays.equals(corpse,GamePackets.corpse(20,2,"x",new double[]{8,64,8},0,new double[3],null,new double[3],null,null,1)),"normal corpse stays byte-identical");
        byte[] enlarged=GamePackets.corpse(20,2,"x",new double[]{8,64,8},0,new double[3],null,new double[3],null,null,2);
        ByteBuffer body=ByteBuffer.wrap(enlarged);Wire.varint(body);Wire.varint(body);int name=body.getShort()&65535;body.position(body.position()+name+19);Wire.varint(body);
        int scaleOffset=body.position()+28;check(body.getFloat(scaleOffset)==2,"native corpse scale field");
        for(int i=0;i<corpse.length;i++)if(i<scaleOffset||i>=scaleOffset+4)check(corpse[i]==enlarged[i],"corpse change limited to native scale field");
        System.out.println("Character scale server: "+checks+" PASS");
    }
}
