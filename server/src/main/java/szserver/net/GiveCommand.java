package szserver.net;
import com.google.gson.*;
import java.util.*;

/** Both installed native Item Editor forms and existing short self-give convenience syntax. */
public record GiveCommand(String target,int factory,int count,int metadata,JsonObject tags) {
    static boolean matches(String text){String command=AccountIdentity.strip(text);return command.startsWith("/{")||Set.of("/gif","/give").contains(CommandSupport.verb(command));}
    private static JsonObject object(String text){
        try{
            JsonElement parsed=JsonParser.parseString(text);
            if(parsed.isJsonObject())return parsed.getAsJsonObject();
        }catch(JsonParseException error){
            throw new IllegalArgumentException("Неполный или некорректный JSON в команде выдачи.",error);
        }
        throw new IllegalArgumentException("give_parameters");
    }
    private static int number(String value,int minimum,int maximum){return CommandNumbers.bounded(value,minimum,maximum,"give_parameters");}
    private static boolean decimal(String value){try{CommandNumbers.integer(value,"give_parameters");return true;}catch(IllegalArgumentException invalid){return false;}}
    private static int jsonInteger(JsonObject value,String key,int fallback){if(!value.has(key))return fallback;JsonElement element=value.get(key);if(!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("give_parameters");try{return element.getAsBigDecimal().toBigIntegerExact().intValueExact();}catch(ArithmeticException invalid){throw new IllegalArgumentException("give_parameters");}}
    public static GiveCommand parse(String text){
        if(text.length()>16384)throw new IllegalArgumentException("give_command_limit");String command=AccountIdentity.strip(text);var parts=CommandSupport.split(command);
        boolean raw=command.startsWith("/{");if(!raw&&(parts.isEmpty()||!Set.of("/gif","/give").contains(parts.get(0).toLowerCase(Locale.ROOT))))throw new IllegalArgumentException("usage: /gif item_id [count]");
        if(raw||parts.size()>1&&parts.get(1).startsWith("{")){
            JsonObject p=object(raw?command.substring(1):command.substring(command.indexOf('{')));JsonObject tags=p.has("tag")&&!p.get("tag").isJsonNull()?p.getAsJsonObject("tag"):new JsonObject();
            return checked(new GiveCommand("self",jsonInteger(p,"id",0),jsonInteger(p,"count",1),jsonInteger(p,"itemState",0),tags));
        }
        JsonObject tags=new JsonObject();String arguments=command;var marker=java.util.regex.Pattern.compile("(?<!\\S)-tag(?=\\s|\\{|$)",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).matcher(arguments);boolean tagged=marker.find();
        if(tagged){String json=AccountIdentity.strip(arguments.substring(marker.end()));arguments=AccountIdentity.strip(arguments.substring(0,marker.start()));tags=object(json);}
        var tokens=CommandSupport.split(arguments);if(tokens.size()<2)throw new IllegalArgumentException("give_item_id");int end=tokens.size();for(int i=1;i<tokens.size();i++)if(Set.of("-i","-q","-f").contains(tokens.get(i))){end=i;break;}
        for(int i=end;i<tokens.size();i++)if(!Set.of("-i","-q","-f").contains(tokens.get(i)))throw new IllegalArgumentException("give_flag");
        int operands=end-1,at=1;String target="self";
        // Four positional values are the stock target/id/count/state form even for numeric character names.
        // The stock tagged form has target/id; /give retains short id/count -tag convenience.
        if(!decimal(tokens.get(at))||operands==4||tagged&&operands==2&&tokens.get(0).equalsIgnoreCase("/gif"))target=tokens.get(at++);
        if(at>=end)throw new IllegalArgumentException("give_item_id");int id=number(tokens.get(at++),1,65535),count=1,metadata=0;
        if(at<end)count=number(tokens.get(at++),1,10000);if(at<end)metadata=number(tokens.get(at++),0,65535);if(at!=end)throw new IllegalArgumentException("give_parameters");
        return checked(new GiveCommand(target,id,count,metadata,tags));
    }
    private static GiveCommand checked(GiveCommand request){if(request.factory<1||request.factory>65535||request.count<1||request.count>10000||request.metadata<0||request.metadata>65535||request.tags.size()>256)throw new IllegalArgumentException("give_parameters");return request;}
}
