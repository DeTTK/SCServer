package szserver.net;

import com.google.gson.*;
import szserver.persistence.*;
import szserver.world.World.Vec;
import szserver.util.JsonDocument;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Compatibility with core.GameService. Call only from bounded development frontend workers.
 * Account indexes and character positions are the same durable services used by native login.
 */
public final class DevelopmentService {
    private static final java.security.SecureRandom RANDOM=new java.security.SecureRandom();
    private static String sessionId(){byte[] value=new byte[18];RANDOM.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    public static final class Session {
        public final String id=sessionId();
        private String state="connected";
        private UUID account,character;
        public String state(){return state;}
    }
    private final LocalAccounts accounts;
    private final CharacterLocations locations;
    private final DeveloperBlocks blocks;
    private final Supplier<JsonObject> scenes;
    private final Supplier<JsonDocument> streamedScenes;
    private final Object[] movementLocks=new Object[256];
    public DevelopmentService(LocalAccounts accounts,CharacterLocations locations,DeveloperBlocks blocks,Supplier<JsonObject> scenes){this(accounts,locations,blocks,Objects.requireNonNull(scenes),null);}
    private DevelopmentService(LocalAccounts accounts,CharacterLocations locations,DeveloperBlocks blocks,Supplier<JsonObject> scenes,Supplier<JsonDocument> streamedScenes){this.accounts=Objects.requireNonNull(accounts);this.locations=Objects.requireNonNull(locations);this.blocks=Objects.requireNonNull(blocks);this.scenes=scenes;this.streamedScenes=streamedScenes;Arrays.setAll(movementLocks,i->new Object());}
    public static DevelopmentService streaming(LocalAccounts accounts,CharacterLocations locations,DeveloperBlocks blocks,Supplier<JsonDocument> scenes){return new DevelopmentService(accounts,locations,blocks,null,Objects.requireNonNull(scenes));}
    private static void require(Session s,String... states){if(!Arrays.asList(states).contains(s.state))throw new IllegalArgumentException("operation requires state: "+String.join(", ",states));}
    public static DeveloperBlocks.Position position(JsonElement value){
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=3)throw new IllegalArgumentException("position must contain three integers");int[] p=new int[3];
        for(int i=0;i<3;i++){JsonElement e=value.getAsJsonArray().get(i);if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.getAsString().matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("position must contain three integers");try{var n=e.getAsBigDecimal();if(n.abs().compareTo(java.math.BigDecimal.valueOf(30_000_000))>0)throw new IllegalArgumentException("position is outside world bounds");p[i]=n.intValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("position is outside world bounds",error);}}
        return new DeveloperBlocks.Position(p[0],p[1],p[2]);
    }
    private static <T>T await(CompletableFuture<T> future){try{return future.get();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("development_interrupted",error);}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof RuntimeException r)throw r;throw new IllegalStateException("development_storage_failed",cause);}}
    private static JsonObject object(){return new JsonObject();}
    private JsonObject character(Session s,UUID id){LocalAccounts.CharacterIdentity identity;try{identity=accounts.getCharacter(s.account,id);}catch(IllegalArgumentException error){throw new IllegalArgumentException("character does not belong to account");}return character(identity);}
    private JsonObject character(LocalAccounts.CharacterIdentity identity){Vec p=locations.position(identity.id());JsonObject result=object();result.addProperty("id",identity.id().toString());result.addProperty("name",identity.name());JsonArray position=new JsonArray();if(p==null){position.add(0);position.add(64);position.add(0);}else{position.add(p.x());position.add(p.y());position.add(p.z());}result.add("position",position);return result;}
    public JsonDocument reply(Session s,JsonObject request){
        JsonElement value=request.get("op");String op=value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():"";
        if(!Set.of("enter_world","inspect").contains(op))return JsonDocument.of(handleSmall(s,request));
        require(s,op.equals("enter_world")?"character_selected":"in_world");
        Map<String,Object> fields=new LinkedHashMap<>();fields.put("id","dev:origin");fields.put("character",character(s,s.character));fields.put("blocks",blocks.snapshotRows());
        JsonDocument scene;if(streamedScenes==null){JsonObject captured=scenes.get();Map<String,Object> sceneFields=new LinkedHashMap<>();sceneFields.put("scene_objects",captured.get("scene_objects"));sceneFields.put("scene_tombstones",captured.get("scene_tombstones"));scene=new JsonDocument(sceneFields);}else scene=streamedScenes.get();
        // Flatten scene fields without exposing their immutable row sources to callers.
        JsonDocument world=new JsonDocument(fields).append(scene);Map<String,Object> result=new LinkedHashMap<>();
        if(op.equals("enter_world")){s.state="in_world";result.put("state",s.state);}result.put("world",world);return new JsonDocument(result);
    }
    private static String value(JsonObject request,String key){return pythonString(request.has(key)?request.get(key):new JsonPrimitive(""));}
    public JsonObject handle(Session s,JsonObject request){
        return reply(s,request).materialize();
    }
    private JsonObject handleSmall(Session s,JsonObject request){
        JsonElement operation=request.get("op");String op=operation!=null&&operation.isJsonPrimitive()&&operation.getAsJsonPrimitive().isString()?operation.getAsString():"";
        if(Set.of("move","place_block","break_block").contains(op)&&s.character!=null)character(s,s.character);
        JsonObject result=object();switch(op){
            case "authenticate":{require(s,"connected");try{s.account=await(accounts.authenticate(value(request,"token")));}catch(IllegalArgumentException error){if(error.getMessage().equals("invalid_token"))throw new IllegalArgumentException("invalid token");throw error;}s.state="authenticated";result.addProperty("account_id",s.account.toString());result.addProperty("state",s.state);break;}
            case "list_characters":{require(s,"authenticated","character_selected");JsonArray rows=new JsonArray();for(var identity:accounts.listCharacters(s.account))rows.add(character(identity));result.add("characters",rows);break;}
            case "create_character":{require(s,"authenticated");LocalAccounts.CharacterIdentity identity;try{identity=await(accounts.createCharacter(s.account,value(request,"name")));}catch(IllegalArgumentException error){if(error.getMessage().equals("invalid_character"))throw new IllegalArgumentException("name must be 3-24 letters, digits, '_' or '-'");if(error.getMessage().equals("character_name_already_exists"))throw new IllegalArgumentException("character name already exists");throw error;}result.add("character",character(identity));break;}
            case "select_character":{require(s,"authenticated","character_selected");String raw=value(request,"character_id");UUID id;try{id=UUID.fromString(raw);if(!raw.equals(id.toString()))throw new IllegalArgumentException();}catch(IllegalArgumentException error){throw new IllegalArgumentException("character does not belong to account");}JsonObject chosen=character(s,id);s.character=id;s.state="character_selected";result.add("character",chosen);result.addProperty("state",s.state);break;}
            case "move":{require(s,"in_world");var target=position(request.get("position"));synchronized(movementLocks[(s.character.hashCode()&0x7fffffff)%movementLocks.length]){JsonArray before=character(s,s.character).getAsJsonArray("position");if(Math.abs(before.get(0).getAsDouble()-target.x())+Math.abs(before.get(1).getAsDouble()-target.y())+Math.abs(before.get(2).getAsDouble()-target.z())>16)throw new IllegalArgumentException("movement exceeds development step limit");await(locations.teleport(s.character,new Vec(target.x(),target.y(),target.z())));}result.addProperty("event","character_moved");result.add("position",target.json());break;}
            case "place_block","break_block":{require(s,"in_world");var target=position(request.get("position"));JsonArray current=character(s,s.character).getAsJsonArray("position");if(Math.max(Math.abs(current.get(0).getAsDouble()-target.x()),Math.max(Math.abs(current.get(1).getAsDouble()-target.y()),Math.abs(current.get(2).getAsDouble()-target.z())))>6)throw new IllegalArgumentException("block is out of interaction range");String id=op.equals("place_block")?DeveloperBlocks.id(value(request,"block_id")):null;await(blocks.set(target,id));result.addProperty("event",id==null?"block_broken":"block_placed");result.add("position",target.json());if(id!=null)result.addProperty("block_id",id);break;}
            default:throw new IllegalArgumentException("unknown operation: "+pythonRepr(operation==null?JsonNull.INSTANCE:operation));
        }return result;
    }
    /** JSON values passed to Python str()/repr() by the original service. */
    public static String pythonString(JsonElement e){if(e==null||e.isJsonNull())return "None";if(e.isJsonPrimitive()){JsonPrimitive p=e.getAsJsonPrimitive();if(p.isString())return p.getAsString();if(p.isBoolean())return p.getAsBoolean()?"True":"False";String text=p.getAsString();if(text.matches("-?(0|[1-9][0-9]*)"))return new java.math.BigInteger(text).toString();double n=p.getAsDouble();if(n==0)return Double.doubleToRawLongBits(n)<0?"-0.0":"0.0";java.math.BigDecimal decimal=java.math.BigDecimal.valueOf(n).stripTrailingZeros();int exponent=decimal.precision()-decimal.scale()-1;if(exponent>=-4&&exponent<16){String plain=decimal.toPlainString();return plain.contains(".")?plain:plain+".0";}String digits=decimal.unscaledValue().abs().toString();String mantissa=digits.substring(0,1)+(digits.length()==1?"":"."+digits.substring(1));return (n<0?"-":"")+mantissa+"e"+(exponent<0?"-":"+")+String.format(Locale.ROOT,"%02d",Math.abs(exponent));}return pythonRepr(e);}
    public static String pythonRepr(JsonElement e){if(e==null||e.isJsonNull())return "None";if(e.isJsonPrimitive()){if(!e.getAsJsonPrimitive().isString())return pythonString(e);String s=e.getAsString();char quote=s.indexOf('\'')>=0&&s.indexOf('"')<0?'"':'\'';StringBuilder b=new StringBuilder().append(quote);s.codePoints().forEach(c->{if(c==quote||c=='\\')b.append('\\').appendCodePoint(c);else if(c=='\n')b.append("\\n");else if(c=='\r')b.append("\\r");else if(c=='\t')b.append("\\t");else if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE||Character.getType(c)==Character.SPACE_SEPARATOR&&c!=32||Character.getType(c)==Character.LINE_SEPARATOR||Character.getType(c)==Character.PARAGRAPH_SEPARATOR||Character.getType(c)==Character.UNASSIGNED||Character.getType(c)==Character.PRIVATE_USE)b.append(c<=255?String.format("\\x%02x",c):c<=65535?String.format("\\u%04x",c):String.format("\\U%08x",c));else b.appendCodePoint(c);});return b.append(quote).toString();}List<String> values=new ArrayList<>();if(e.isJsonArray()){for(var v:e.getAsJsonArray())values.add(pythonRepr(v));return "["+String.join(", ",values)+"]";}e.getAsJsonObject().entrySet().forEach(entry->values.add(pythonRepr(new JsonPrimitive(entry.getKey()))+": "+pythonRepr(entry.getValue())));return "{"+String.join(", ",values)+"}";}
}
