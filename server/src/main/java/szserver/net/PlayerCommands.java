package szserver.net;
import szserver.world.World.Vec;
import java.util.*;
/** Complete legacy chat forms for shared character services; parsing performs no state changes. */
public final class PlayerCommands {
    public record Spawn(boolean global,Vec position){}
    public record Loadout(String operation,String name){}
    public record Storage(String operation,int storageSlot,int inventorySlot){}
    public record Apply(int container,int factory){}
    private static List<String> quoted(String text,String verb){var w=CommandSupport.words(text);if(w.isEmpty()||!w.get(0).equalsIgnoreCase(verb))throw new IllegalArgumentException("not_player_command");return w;}
    public static Spawn spawn(String text){var w=quoted(text,"/spawn");if(w.size()==1)return new Spawn(false,null);if(w.size()==2&&w.get(1).equalsIgnoreCase("global"))return new Spawn(true,null);if(w.size()!=4)throw new IllegalArgumentException("/spawn [global|x y z]");return new Spawn(false,new Vec(TeleportCommands.number(w.get(1),"invalid_spawn_coordinate"),TeleportCommands.number(w.get(2),"invalid_spawn_coordinate"),TeleportCommands.number(w.get(3),"invalid_spawn_coordinate")));}
    public static String faction(String text){var w=quoted(text,"/fraction");if(w.size()!=2||!Set.of("stalker","bandit","merc","freedom","duty","covenant").contains(w.get(1).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("invalid_faction");return w.get(1).toLowerCase(Locale.ROOT);}
    public static Loadout loadout(String text){var w=quoted(text,"/loadout");if(w.size()==1||w.size()==2&&w.get(1).equalsIgnoreCase("list"))return new Loadout("list",null);if(w.size()!=3||!Set.of("save","apply","delete").contains(w.get(1).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("usage_loadout");return new Loadout(w.get(1).toLowerCase(Locale.ROOT),w.get(2));}
    public static Integer medicine(String text){var w=CommandSupport.words(text);if(w.isEmpty()||!Set.of("/medkit","/medicine","/heal").contains(w.get(0).toLowerCase(Locale.ROOT))||w.size()>2)throw new IllegalArgumentException("medicine_command_arguments");if(w.size()==1)return null;String slot=w.get(1);int at=0;while(at<slot.length()&&slot.charAt(at)=='-')at++;if(at==slot.length()||!slot.substring(at).codePoints().allMatch(Character::isDigit))return null;return CommandNumbers.bounded(slot,Integer.MIN_VALUE,Integer.MAX_VALUE,"medicine_slot_invalid");}
    public static Storage storage(String text){var w=CommandSupport.split(text);if(w.isEmpty()||!Set.of("/storage","/stash").contains(w.get(0).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("usage_storage");if(w.size()==1||w.size()==2&&w.get(1).equalsIgnoreCase("open"))return new Storage("open",0,0);if(w.size()!=4||!Set.of("put","take").contains(w.get(1).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("usage_storage");return new Storage(w.get(1).toLowerCase(Locale.ROOT),CommandNumbers.bounded(w.get(2),0,32767,"invalid_storage_slot"),CommandNumbers.bounded(w.get(3),0,32767,"invalid_storage_slot"));}
    public static Apply apply(String text){var w=CommandSupport.split(text);if(w.size()!=3||!w.get(0).equalsIgnoreCase("/apply"))throw new IllegalArgumentException("usage_apply_armor_id");int container=switch(w.get(1).toLowerCase(Locale.ROOT)){case "armor"->1;case "skin"->2;case "backpack","container"->3;case "primary"->6;case "secondary"->7;case "melee"->5;default->throw new IllegalArgumentException("usage_apply_armor_id");};return new Apply(container,CommandNumbers.bounded(w.get(2),0,65535,"invalid_armor_id"));}
    private PlayerCommands(){}
}
