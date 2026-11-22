package completion;
import java.util.*;
public class CompletionTest {
 static final Map<String,String> commands=Map.of("/anim","clip","/clone","name","/fraction","faction","/wedit","operation","/apply","slot id","/alliance","settings","/фракции","settings");
 static Completion.Result get(String text,int cursor){return Completion.suggest(text,cursor,commands,List.of(new Completion.Suggestion("Клон с пробелом","#1234"),new Completion.Suggestion("1234","Клон с пробелом")),List.of("Pilot"),List.of("pda_sit_idle","npc_generic:pda_sit_idle","PRONE","dialog_anim:california","dialog_anim:california_idle"));}
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  check(get("/an",3).replace("/an",0).equals("/anim"));
  String s="/anim clone \"Клон с\" pda_sit_idle";var r=get(s,18);check(r.values().size()==1);check(r.replace(s,0).equals("/anim clone \"Клон с пробелом\" pda_sit_idle"));
  s="/anim clone 1234 pda";r=get(s,s.length());check(r.replace(s,0).equals("/anim clone 1234 pda_sit_idle"));
  s="/anim \"pda";r=get(s,s.length());check(r.replace(s,0).equals("/anim \"pda_sit_idle\""));
  check(get("hello",5).values().isEmpty());
  check(get("/fraction fr",12).values().get(0).value().equals("freedom"));
  check(get("/wedit ",7).values().stream().noneMatch(v->v.value().equals("set")));
  check(get("/apply ",7).values().stream().anyMatch(v->v.value().equals("primary")));
  s="/anim CALIFORNIA";r=get(s,s.length());check(r.values().size()==2);check(r.replace(s,0).equals("/anim dialog_anim:california"));
  s="/anim clone 1234 california";r=get(s,s.length());check(r.values().size()==2);check(r.replace(s,0).equals("/anim clone 1234 dialog_anim:california"));
  s="/anim clone \"пробел\" pda_sit_idle";r=get(s,s.indexOf("\" pda"));check(r.replace(s,0).equals("/anim clone \"Клон с пробелом\" pda_sit_idle"));
  s="/anim pda";r=get(s,s.length());check(r.values().size()==2);check(r.values().get(0).value().equals("pda_sit_idle"));
  s="/anim california suffix";r=get(s,16);check(r.replace(s,0).equals("/anim dialog_anim:california suffix"));
  check(get("/alliance ban",13).values().stream().anyMatch(v->v.value().equals("bandits")));
  check(get("/фракции duty add ",18).values().stream().anyMatch(v->v.value().equals("farm")));
  check(get("/alliance duty ",15).values().stream().anyMatch(v->v.value().equals("fee")));
  System.out.println("PASS command completion: quotes, cursor suffix, Unicode clones, animation context, valid commands");
 }
}
