package szserver.net;

import szserver.util.Json;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Legacy command catalog and pose protocol; no command execution on completion requests. */
public final class CommandSupport {
    public static final String REQUEST="szmods.bridge.CommandRequest",CATALOG="szmods.bridge.CommandCatalog",ANIMATION="szmods.bridge.PlayerAnimation";
    public static final Map<String,String> COMMANDS;
    static{var m=new LinkedHashMap<String,String>();
        m.put("/anim","\"анимация\" | clear | clone \"клон\" \"анимация\"");m.put("/clone","[\"ник\"]");m.put("/spawn","[global | x y z]");m.put("/fraction","stalker | bandit | merc | freedom | duty | covenant");m.put("/drone","");
        for(String name:List.of("/medkit","/medicine","/heal"))m.put(name,"[слот]");m.put("/chunkdistance","число чанков");m.put("/tp","\"игрок\" | x y z");m.put("/time","set <day|night|noon|midnight|число> | add <число>");m.put("/weather","clear | rain | thunder [секунды]");m.put("/wedit","select <1|2> | remove | copy | cut | paste | move <x y z> | back");m.put("/apply","<слот> <ID предмета>");m.put("/help","");m.put("/warp","<имя> | set <имя> <x y z> | delete <имя>");
        for(String name:List.of("/inv","/inventory"))m.put(name,"delete <слот> [количество] | drop <слот> [количество]");m.put("/drop","<слот> [количество]");for(String name:List.of("/storage","/stash"))m.put(name,"open");m.put("/loadout","save <имя> | apply <имя> | delete <имя> | list");m.put("/stats","");m.put("/zombies","start | stop | status");m.put("/npc","help | presets | spawn | list | select | goto | follow | stop | patrol | equip | state | remove");COMMANDS=Collections.unmodifiableMap(m);
    }
    /** Python str.split whitespace, including NBSP/NEL and Unicode separators. */
    public static List<String> split(String value){
        if(value.length()>16384)throw new IllegalArgumentException("command_too_long");List<String> result=new ArrayList<>();int start=-1;
        for(int at=0;at<value.length();){int cp=value.codePointAt(at),next=at+Character.charCount(cp);if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0x85){if(start>=0){result.add(value.substring(start,at));start=-1;}}else if(start<0)start=at;at=next;}
        if(start>=0)result.add(value.substring(start));return List.copyOf(result);
    }
    public static String verb(String value){var parts=split(value);return parts.isEmpty()?"":parts.get(0).toLowerCase(Locale.ROOT);}
    /** POSIX shlex.split grammar used by old clone/animation/spawn/faction/loadout commands. */
    public static List<String> words(String value){
        if(value.length()>16384)throw new IllegalArgumentException("command_too_long");var out=new ArrayList<String>();StringBuilder token=new StringBuilder();char quote=0;boolean active=false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);
            if(c=='\\'&&quote!='\''){if(++i==value.length())throw new IllegalArgumentException("command_quote_not_closed");char escaped=value.charAt(i);if(quote=='"'&&escaped!='"'&&escaped!='\\')token.append('\\');token.append(escaped);active=true;continue;}
            if(quote!=0){if(c==quote)quote=0;else token.append(c);active=true;continue;}
            if(c=='\''||c=='"'){quote=c;active=true;}else if(c==' '||c=='\t'||c=='\r'||c=='\n'){if(active){out.add(token.toString());token.setLength(0);active=false;}}else{token.append(c);active=true;}}
        if(quote!=0)throw new IllegalArgumentException("command_quote_not_closed");if(active)out.add(token.toString());return List.copyOf(out);
    }
    public static String normalizeAnimation(String value){String v=value.strip();if(v.equalsIgnoreCase("clear"))return null;if(Set.of("fpv","fpv_sit","pda-sit-idle","murmur","murmur_tablet").contains(v.toLowerCase(Locale.ROOT)))return "pda_sit_idle";String upper=v.toUpperCase(Locale.ROOT);if(Set.of("STAND","CROUCH","PRONE","SIT","CLIMB","CLIMB_OVER","CLIMB_ON","LADDER","JUMP","KNOCKDOWN","FLY","LOGOUT","ZIPLINE").contains(upper))return upper;if(!v.matches("[a-zA-Z0-9_:/.-]{1,63}"))throw new IllegalArgumentException("Некорректное название анимации");return v;}
    public static byte[] pose(int entity,long revision,String value){byte[] name=(value==null?"":value).getBytes(StandardCharsets.US_ASCII);if(entity<0||revision<1||name.length>63||value!=null&&!value.matches("[a-zA-Z0-9_:/.-]{1,63}"))throw new IllegalArgumentException("animation_packet_bounds");return Wire.dynamic(ANIMATION,Wire.bytes(out->{out.writeByte(1);out.writeInt(entity);out.writeLong(revision);out.writeByte(name.length);out.write(name);}));}
    public static byte[] catalog(List<Map<String,String>> clones,List<String> players){byte[] raw=Json.bytes(Json.tree(Map.of("version",1,"commands",COMMANDS,"clones",clones,"players",players)));if(raw.length>65536)throw new IllegalArgumentException("completion_catalog_too_large");return Wire.dynamic(CATALOG,Wire.bytes(out->{out.writeInt(raw.length);out.write(raw);}));}
    public static String artifactStats(Map<String,Float> stats){return "Статы сборки: "+(stats.isEmpty()?"нет активных эффектов":String.join(", ",new TreeMap<>(stats).entrySet().stream().map(e->e.getKey()+"="+new java.math.BigDecimal(Float.toString(e.getValue())).stripTrailingZeros().toPlainString()).toList()));}
    public static String help(){return String.join("\n",COMMANDS.entrySet().stream().map(e->e.getKey()+(e.getValue().isEmpty()?"":" "+e.getValue())).toList());}
    private CommandSupport(){}
}
