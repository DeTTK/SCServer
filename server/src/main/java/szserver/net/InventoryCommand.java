package szserver.net;

import java.util.*;

/** Existing chat command syntax from game_handshake.parse_inventory_command. */
public record InventoryCommand(String operation,int slot,Integer count) {
    public static boolean matches(String text){return Set.of("/inv","/inventory","/drop").contains(CommandSupport.verb(text));}
    public static InventoryCommand parse(String text){var words=CommandSupport.split(text);if(words.size()<2||!matches(text))throw new IllegalArgumentException("not_inventory_command");boolean direct=words.get(0).equalsIgnoreCase("/drop");String operation=direct?"drop":words.get(1).toLowerCase(Locale.ROOT);int offset=direct?1:2;if(!Set.of("delete","remove","drop").contains(operation)||words.size()<=offset)throw new IllegalArgumentException("usage_inventory");return new InventoryCommand(operation,CommandNumbers.bounded(words.get(offset),0,32767,"invalid_inventory_slot"),words.size()>offset+1?CommandNumbers.bounded(words.get(offset+1),1,Integer.MAX_VALUE,"invalid_inventory_slot"):null);}
}
