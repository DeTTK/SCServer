package szserver.net;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class AllianceStateTests {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory(Path.of(args[0]),"alliance-");
        AllianceState state=new AllianceState(directory);
        check(Files.isRegularFile(state.file()),"settings file is created on first start");
        check(state.describe().contains("stalkers")&&state.describe().contains("covenant"),"all six alliances are editable");
        check(state.set("duty","farm",0).contains("скрыта"),"zero hides a perk row");
        check(!state.active("duty").contains("farm"),"hidden perk is inactive");
        check(state.add("duty","farm").contains("добавлен"),"add restores a hidden perk");
        check(state.active("duty").contains("farm"),"restored perk is active again");
        check(state.add("duty","quest",25d).contains("25"),"add accepts an explicit percentage");
        check(state.remove("duty","buyer").contains("убран"),"remove hides a perk");
        check(state.set("duty","fee",250000).contains("250000"),"transfer fee is editable");
        check(state.set("duty","стоимость",1000).contains("1000"),"fee accepts the Russian key");
        check(state.describe().contains("duty — 1000 ₽"),"fee is persisted in the table");
        try{state.set("duty","unknown",1);throw new AssertionError("unknown key accepted");}catch(IllegalArgumentException expected){checks++;}
        try{state.set("nobody","farm",5);throw new AssertionError("unknown alliance accepted");}catch(IllegalArgumentException expected){checks++;}
        try{state.set("duty","fee",-1);throw new AssertionError("negative fee accepted");}catch(IllegalArgumentException expected){checks++;}
        AllianceState reloaded=new AllianceState(directory);
        check(reloaded.active("duty").equals(List.of("farm","delivery","quest","discount","ffa")),"values survive a restart");
        check(reloaded.describe().contains("duty — 1000 ₽"),"fee survives a restart");
        byte[] frame=reloaded.frame();
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));
        in.readInt();
        check(in.readInt()==AllianceState.ALLIANCES.size(),"frame carries every alliance");
        for(String id:AllianceState.ALLIANCES){
            check(in.readUTF().equals(id),"wire order follows the bundle");
            check(in.readUTF().isEmpty(),"label is unused");
            check(!in.readBoolean()&&!in.readBoolean(),"recruitment is open for non-members");
            float[] bonuses=new float[6];
            for(int i=0;i<bonuses.length;i++)bonuses[i]=in.readFloat();
            float[] expected=id.equals("duty")?new float[]{5,10,25,10,0,5}:new float[]{10,10,10,10,10,5};
            check(Arrays.equals(bonuses,expected),"bonus percentages match the settings for "+id);
            check(in.readInt()==(id.equals("duty")?1000:AllianceState.DEFAULT_FEE),"fee follows the settings");
            check(in.readBoolean(),"no expiry instant");
            check(in.readInt()==0,"no per-key overrides");
        }
        check(!in.readBoolean(),"open menus are refreshed");
        check(in.available()==0,"frame has no trailing bytes");
        System.out.println("alliance settings: add/remove, fee, persistence and packet 1667 layout PASS ("+checks+" checks)");
    }
}