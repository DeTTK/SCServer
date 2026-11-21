package szserver.inventory;
import com.google.gson.*;
public final class WeaponResourceTests {
    static int checks;
    static void reject(JsonObject item){try{WeaponProfile.from(item);throw new AssertionError("invalid resource accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        JsonObject original=JsonParser.parseString("{\"clip_size\":200,\"bullets_id\":[12737,12736],\"damage\":30000,\"damage_distant\":30000,\"max_distance\":250,\"rate_of_fire\":1000,\"reload_time\":3.75,\"head_damage_modifier\":1.12}").getAsJsonObject();
        WeaponProfile admin=WeaponProfile.from(original);if(admin.damage()!=30000||admin.capacity()!=200)throw new AssertionError("admin Gauss");checks++;
        for(String name:new String[]{"damage","damage_distant","max_distance","rate_of_fire","reload_time","reload_time_tactical","head_damage_modifier","damage_decrease_start","damage_decrease_end"})for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){JsonObject copy=original.deepCopy();copy.addProperty(name,bad);reject(copy);}
        JsonObject tooHigh=original.deepCopy();tooHigh.addProperty("damage",1_000_001);reject(tooHigh);
        System.out.println("Weapon resource profiles: "+checks+" PASS");
    }
}
