package szserver.net;
import java.util.*;import java.util.concurrent.*;
/** Parse on the owner, persist on the alliance worker. Legacy and native faction names are accepted. */
public final class AllianceCommands {
    public static final String USAGE="/alliance [reload | list | help] | <фракция> <fee|farm|delivery|quest|discount|buyer|ffa> <значение> | <фракция> add <бонус> [значение] | <фракция> remove <бонус> | <фракция> list";
    private AllianceCommands(){}
    public static CompletableFuture<String> execute(AllianceState state,String text){
        List<String> words=CommandSupport.split(text);
        if(words.size()==1)return CompletableFuture.completedFuture(state.describe());
        String op=words.get(1).toLowerCase(Locale.ROOT);
        if(words.size()==2){if(Set.of("reload","перечитать").contains(op))return state.reload();if(Set.of("list","показать").contains(op))return CompletableFuture.completedFuture(state.describe());if(Set.of("help","помощь").contains(op))return CompletableFuture.completedFuture(USAGE);throw new IllegalArgumentException(USAGE);}
        String action=words.get(2).toLowerCase(Locale.ROOT);
        if(words.size()==3&&Set.of("list","показать").contains(action))return CompletableFuture.completedFuture("Активные бонусы "+op+": "+String.join(", ",state.active(op)));
        if(words.size()==4&&Set.of("remove","убрать","удалить").contains(action))return state.remove(op,words.get(3));
        if((words.size()==4||words.size()==5)&&Set.of("add","добавить").contains(action))return state.add(op,words.get(3),words.size()==5?number(words.get(4)):null);
        if(words.size()==4)return state.set(op,action,number(words.get(3)));
        throw new IllegalArgumentException(USAGE);
    }
    private static double number(String value){try{return Double.parseDouble(value.replace(',','.'));}catch(NumberFormatException invalid){throw new IllegalArgumentException("Не число: "+value);}}
}
