package szserver.net;
import szserver.client.GameplayPacketAliases;import java.util.*;
public final class SuicideCommandTests {
    public static void main(String[] args)throws Exception{
        int checks=0;for(String text:List.of("/suicide","/kill","/SUICIDE","  /Kill  ")){if(!SuicideCommand.matches(text))throw new AssertionError(text);SuicideCommand.parse(text);checks++;}
        for(String text:List.of("","/suicides","/suicide_now","/heal","suicide")){if(SuicideCommand.matches(text))throw new AssertionError(text);checks++;}
        for(String text:List.of("/suicide now","/kill 1","/suicide \"a\""))try{SuicideCommand.parse(text);throw new AssertionError("arguments accepted: "+text);}catch(IllegalArgumentException expected){checks++;}
        if(SuicideCommand.PACKET_ID!=1746||!SuicideCommand.REQUEST.equals("907123902"))throw new AssertionError("give-up packet constants");checks++;
        if(GameplayPacketAliases.registered(SuicideCommand.REQUEST)!=SuicideCommand.PACKET_ID)throw new AssertionError("give-up packet alias");checks++;
        if(!CommandSupport.COMMANDS.containsKey("/suicide")||!CommandSupport.COMMANDS.containsKey("/kill"))throw new AssertionError("catalog");checks++;
        System.out.println("PASS "+checks+" suicide command aliases, arity and give-up packet registration");
    }
}
