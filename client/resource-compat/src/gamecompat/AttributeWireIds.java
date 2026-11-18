package gamecompat;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.ConcurrentHashMap;
/** Preserve this custom server's native wire epoch while loading newer resource catalogs. */
public final class AttributeWireIds {
 private static final Map<String,Integer> BASE;private static final Map<String,Integer> ADDED=new ConcurrentHashMap<>();private static final int MAX;
 static{
  var base=new HashMap<String,Integer>();var ids=new HashSet<Integer>();int max=0;
  try(var input=AttributeWireIds.class.getResourceAsStream("/gamecompat/attribute-wire-ids.tsv");var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(input),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){String[] row=line.split("\t",2);int id=Integer.parseInt(row[0]);String name=new String(Base64.getDecoder().decode(row[1]),StandardCharsets.UTF_8);if(id<1||id>32767||!ids.add(id)||base.put(name,id)!=null)throw new IOException("wire_attribute_table_duplicate");max=Math.max(max,id);}}
  catch(Exception error){throw new ExceptionInInitializerError(error);}
  if(base.size()!=469||base.getOrDefault("GOInstance_currentState",0)!=467||base.getOrDefault("GOLiving_hiddenAfterDeath",0)!=468)throw new ExceptionInInitializerError("wire_attribute_epoch");BASE=Map.copyOf(base);MAX=max;
 }
 public static int resolve(int proposed,String name){Integer known=BASE.get(name);if(known!=null)return known;synchronized(ADDED){Integer added=ADDED.get(name);if(added!=null)return added;int id=MAX+ADDED.size()+1;if(id>32767)throw new IllegalStateException("wire_attribute_limit");ADDED.put(name,id);return id;}}
 public static int baseSize(){return BASE.size();}
 public static String expectedBase(){try(var in=AttributeWireIds.class.getResourceAsStream("/gamecompat/native-base-sha256.txt")){return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.US_ASCII).trim();}catch(IOException e){throw new IllegalStateException(e);}}
 private AttributeWireIds(){}
}
