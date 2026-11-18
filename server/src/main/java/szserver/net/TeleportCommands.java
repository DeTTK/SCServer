package szserver.net;

import szserver.world.World.Vec;
import java.nio.ByteBuffer;
import java.util.*;

/** Reviewed legacy chat and native map request formats; no native reflection here. */
public final class TeleportCommands {
    public static final double COMMAND_LIMIT=30_000_000;
    public record Request(Vec position,String player,boolean surface) {}
    public record Warp(String operation,String name,Vec position) {}
    private static String[] words(String text){List<String> words=text.indexOf('"')>=0||text.indexOf('\'')>=0?CommandSupport.words(text):CommandSupport.split(text);if(words.isEmpty())throw new IllegalArgumentException("empty_teleport_command");return words.toArray(String[]::new);}
    public static double number(String value,String error){StringBuilder normalized=new StringBuilder();AccountIdentity.strip(value).codePoints().forEach(cp->{int digit=Character.getType(cp)==Character.DECIMAL_DIGIT_NUMBER?Character.digit(cp,10):-1;if(digit>=0)normalized.append((char)('0'+digit));else normalized.appendCodePoint(cp);});value=normalized.toString();String digits="[0-9](?:_?[0-9])*";if(!value.matches("[+-]?(?:"+digits+"(?:\\.(?:"+digits+")?)?|\\."+digits+")(?:[eE][+-]?"+digits+")?"))throw new IllegalArgumentException(error);try{double result=Double.parseDouble(value.replace("_",""));if(!Double.isFinite(result))throw new IllegalArgumentException(error);return result;}catch(NumberFormatException invalid){throw new IllegalArgumentException(error);}}
    public static Request parse(String text){String[] words=words(text);if(!words[0].equalsIgnoreCase("/tp"))throw new IllegalArgumentException("not_teleport_command");if(words.length==2){if(AccountIdentity.strip(words[1]).isEmpty()||words[1].codePointCount(0,words[1].length())>64)throw new IllegalArgumentException("invalid_teleport_player");return new Request(null,words[1],false);}if(words.length!=4)throw new IllegalArgumentException("usage_tp_x_y_z");double x=number(words[1],"invalid_teleport_coordinate"),y=number(words[2],"invalid_teleport_coordinate"),z=number(words[3],"invalid_teleport_coordinate");if(Math.abs(x)>COMMAND_LIMIT||Math.abs(z)>COMMAND_LIMIT||y< -1024||y>4096)throw new IllegalArgumentException("teleport_out_of_bounds");return new Request(new Vec(x,y,z),null,false);}
    public static String warpName(String raw){return AccountIdentity.label(raw,1,32,true,"invalid_warp_name");}
    public static Vec warpPosition(Vec p){if(p==null||!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.z())||Math.abs(p.x())>COMMAND_LIMIT||Math.abs(p.y())>COMMAND_LIMIT||Math.abs(p.z())>COMMAND_LIMIT)throw new IllegalArgumentException("invalid_warp_position");return p;}
    public static Warp warp(String text){String[] w=words(text);if(!w[0].equalsIgnoreCase("/warp"))throw new IllegalArgumentException("not_warp_command");if(w.length==2)return new Warp("teleport",warpName(w[1]),null);if(w.length==3&&w[1].equalsIgnoreCase("delete"))return new Warp("delete",warpName(w[2]),null);if(w.length!=6||!Set.of("set","create").contains(w[1].toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("usage_warp");return new Warp("set",warpName(w[2]),warpPosition(new Vec(number(w[3],"invalid_warp_position"),number(w[4],"invalid_warp_position"),number(w[5],"invalid_warp_position"))));}
    public static Request map(byte[] raw){if(raw.length!=13)throw new IllegalArgumentException("invalid_admin_map_teleport_size");ByteBuffer in=ByteBuffer.wrap(raw);int x=in.getInt(),y=in.getInt(),z=in.getInt(),marker=in.get()&255;if(marker>1)throw new IllegalArgumentException("invalid_admin_map_teleport_marker");if(Math.abs((long)x)>COMMAND_LIMIT||Math.abs((long)z)>COMMAND_LIMIT||y< -1024||y>4096)throw new IllegalArgumentException("teleport_out_of_bounds");return new Request(new Vec(x+.5,y,z+.5),null,marker==1);}
    public static String foldName(String name){StringBuilder out=new StringBuilder();name.codePoints().forEach(c->{String value=new String(Character.toChars(c));out.append(c==0x131?value:value.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));});return out.toString();}
    private TeleportCommands(){}
}
