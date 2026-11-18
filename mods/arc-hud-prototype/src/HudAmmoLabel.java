import java.util.*;import java.util.regex.*;
/** Localized label from the currently loaded native ammunition profile, never a weapon-ID table. */
final class HudAmmoLabel {
 static final Map<Object,String> cache=new WeakHashMap<>();
 static final Pattern CALIBER=Pattern.compile("(?i)(\\d{1,2}(?:[.,]\\d{1,3})?)\\s*[xх×]\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)");
 static String loaded(Object weapon)throws Exception{
  if(weapon==null||!Class.forName("1745367406").isInstance(HudNative.call(weapon,"interface","1366800141")))return "";
  Object profile=HudNative.call(Class.forName("1745367406"),"interface","657970419",weapon);
  if(profile==null)profile=HudNative.call(Class.forName("1745367406"),"if","210381469",weapon);
  if(profile==null)return "";String saved=cache.get(profile);if(saved!=null)return saved;
  Object localized=HudNative.call(profile,"for","595713561",(Object)null);String name=(String)HudNative.call(localized,"throws","java.lang.String");
  Matcher m=CALIBER.matcher(name);String value=m.find()?m.group(1).replace(',','.')+"×"+m.group(2).replace(',','.') : shortCaliber(name);
  cache.put(profile,value);return value;
 }
 static String shortCaliber(String name){Matcher gauge=Pattern.compile("(?i)(12|16|20)\\s*(?:калибр|cal|gauge)").matcher(name);if(gauge.find())return gauge.group(1)+" кал.";Matcher mm=Pattern.compile("(?i)(\\d{1,2}(?:[.,]\\d{1,3})?)\\s*(?:мм|mm)").matcher(name);return mm.find()?mm.group(1).replace(',','.')+" мм":"";}
}
