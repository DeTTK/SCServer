package completion;
import java.util.*;

/** Cursor-aware, quote-aware completion; never sends or executes the input. */
public final class Completion {
 public record Suggestion(String value,String detail) {}
 public record Result(int start,int end,boolean quoted,List<Suggestion> values,String usage) {
  public String replace(String input,int index){
   String value=values.get(index).value;
   if(quoted||value.chars().anyMatch(Character::isWhitespace))value='"'+value.replace("\\","\\\\").replace("\"","\\\"")+'"';
   return input.substring(0,start)+value+input.substring(end);
  }
 }
 public record Token(String text,int start,int end,boolean quoted) {}
 public static List<Token> tokens(String input){
  List<Token> out=new ArrayList<>();int i=0;
  while(i<input.length()){
   while(i<input.length()&&Character.isWhitespace(input.charAt(i)))i++;
   if(i==input.length())break;
   int start=i;boolean quoted=input.charAt(i)=='"'||input.charAt(i)=='\'';char quote=quoted?input.charAt(i++):0;
   StringBuilder word=new StringBuilder();
   while(i<input.length()){
    char c=input.charAt(i);
    if(quoted&&c==quote){i++;break;}
    if(!quoted&&Character.isWhitespace(c))break;
    if(c=='\\'&&i+1<input.length())c=input.charAt(++i);
    word.append(c);i++;
   }
   out.add(new Token(word.toString(),start,i,quoted));
  }return out;
 }
 public static Result suggest(String input,int cursor,Map<String,String> commands,List<Suggestion> clones,List<String> players,Collection<String> animations){
  cursor=Math.max(0,Math.min(input.length(),cursor));
  if(!input.startsWith("/"))return new Result(cursor,cursor,false,List.of(),"");
  List<Token> all=tokens(input),before=new ArrayList<>();Token current=null;
  for(Token t:all){if(cursor>=t.start&&cursor<=t.end){current=t;break;}if(t.end<cursor)before.add(t);}
  if(current==null)current=new Token("",cursor,cursor,false);
  String prefix=input.substring(current.start,cursor);
  if(current.quoted&&!prefix.isEmpty())prefix=prefix.substring(1);
  if(current.quoted&&(prefix.endsWith("\"")||prefix.endsWith("'")))prefix=prefix.substring(0,prefix.length()-1);
  prefix=prefix.toLowerCase(Locale.ROOT);
  int arg=before.size();String command=all.isEmpty()?"":all.get(0).text.toLowerCase(Locale.ROOT);
  List<Suggestion> values=new ArrayList<>();String usage=commands.containsKey(command)?command+" "+commands.get(command):"";
  if(arg==0)commands.forEach((k,v)->values.add(new Suggestion(k,v)));
  else switch(command){
   case "/anim" -> {
    if(arg==1){add(values,"clear","clone");for(String s:animations)values.add(new Suggestion(s,"анимация"));}
    else if(arg==2&&before.get(1).text.equalsIgnoreCase("clone"))values.addAll(clones);
    else if(arg==3&&before.get(1).text.equalsIgnoreCase("clone")){add(values,"clear");for(String s:animations)values.add(new Suggestion(s,"анимация"));}
   }
   case "/fraction" -> {if(arg==1)add(values,"stalker","bandit","merc","freedom","duty","covenant");}
   case "/friendlyfire" -> {if(arg==1)add(values,"on","off","status");}
   case "/alliance", "/фракции" -> {
    if(arg==1){add(values,"reload","list","help","stalker","stalkers","bandit","bandits","duty","freedom","merc","covenant");}
    else if(arg==2){values.add(new Suggestion("add","добавить бонус"));values.add(new Suggestion("remove","убрать бонус"));values.add(new Suggestion("fee","стоимость перехода"));add(values,"list","farm","delivery","quest","discount","buyer","ffa");}
    else if(arg==3&&(before.get(2).text.equalsIgnoreCase("add")||before.get(2).text.equalsIgnoreCase("remove")))add(values,"farm","delivery","quest","discount","buyer","ffa");
    else if(arg==3&&!Set.of("add","remove").contains(before.get(2).text.toLowerCase(Locale.ROOT))||arg==4&&before.get(2).text.equalsIgnoreCase("add")){boolean fee=before.get(2).text.equalsIgnoreCase("fee");for(String v:fee?new String[]{"0","1000","100000","250000"}:new String[]{"0","5","10","15","20","25","50","100"})values.add(new Suggestion(v,fee?"₽":"%"));}
   }
   case "/spawn" -> {if(arg==1)add(values,"global");}
   case "/warp" -> {if(arg==1)add(values,"set","delete","poligon","cinema","shelter");}
   case "/inv" -> {if(arg==1)add(values,"delete","remove","drop");}
   case "/inventory" -> {if(arg==1)add(values,"delete","remove","drop");}
     case "/storage", "/stash" -> {if(arg==1)add(values,"open","put","take");}
   case "/tp" -> {if(arg==1)for(String s:players)values.add(new Suggestion(s,"игрок"));}
   case "/time" -> {if(arg==1)add(values,"set","add","day","noon","night","midnight","sunrise","sunset");else if(arg==2&&before.get(1).text.equalsIgnoreCase("set"))add(values,"day","noon","night","midnight","sunrise","sunset");}
   case "/weather" -> {if(arg==1)add(values,"clear","rain","thunder");}
   case "/wedit" -> {if(arg==1)add(values,"select","remove","copy","cut","paste","move","back");else if(arg==2&&before.get(1).text.equalsIgnoreCase("select"))add(values,"1","2");}
   case "/apply" -> {if(arg==1)add(values,"armor","backpack","container","skin","primary","secondary","melee");}
  }
  String filter=prefix;Map<String,Suggestion> unique=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  for(Suggestion v:values)if(v.value.toLowerCase(Locale.ROOT).contains(filter))unique.putIfAbsent(v.value,v);
  List<Suggestion> matches=new ArrayList<>(unique.values());
  // Stable alphabetical order within each group; direct prefixes remain easiest to reach.
  matches.sort(Comparator.comparingInt(v->v.value.toLowerCase(Locale.ROOT).startsWith(filter)?0:1));
  return new Result(current.start,current.end,current.quoted,List.copyOf(matches),usage);
 }
 static void add(List<Suggestion> out,String...words){for(String word:words)out.add(new Suggestion(word,""));}
}
