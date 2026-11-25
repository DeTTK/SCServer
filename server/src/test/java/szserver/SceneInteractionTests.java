package szserver;
import szserver.client.*;import szserver.world.*;import java.util.*;import java.nio.ByteBuffer;import java.io.*;import com.google.gson.*;import com.github.luben.zstd.Zstd;
public final class SceneInteractionTests {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  var object=new SceneObject("a_door1_A_2_anm",HexFormat.of().parseHex("019b00006000040000000f00f0000000000001000000000000000000000000000000003f8000000000000000000000000000003f8000000000000000000000000000003f8000000000000000000000000000000001010a000001002369735377697463684279506c617965727344697361626c656446726f6d456469746f720001001375736544616e6765726f75734e61766d6573680001001664697361626c654175746f4f70656e54726967676572000000000000"));var propsDefinition=new JsonObject();propsDefinition.addProperty("state.open","open");propsDefinition.addProperty("state.closed","closed");propsDefinition.addProperty("default_state","closed");var def=new SceneRegistry.Definition(411,object.asset(),"switchable_collision","",propsDefinition);var source=new World.Vec(0,0,0);long now=1800000000000L;
  var change=SceneInteraction.prepare(object,def,source,now);check(change!=null&&change.state().equals("open"),"stock closed door opens");var open=SceneAttributes.configured(object,change.save(),change.behavior());SceneAttributes.validateCurrent(open.record(),def.behaviour());check(open.identity().equals(object.identity()),"stable UUID");check(Arrays.equals(open.configuration(),object.configuration()),"editor settings preserved");check(SceneAttributes.objectState(open,def.behaviour(),"closed").equals("open"),"native stable state attribute");check(SceneInteraction.prepare(open,def,source,now+499)==null,"native 10-tick debounce");check(SceneInteraction.prepare(open,def,new World.Vec(7,0,0),now+500)==null,"out of reach rejected");var close=SceneInteraction.prepare(open,def,source,now+500);check(close.state().equals("closed"),"reverse state");var closed=SceneAttributes.configured(open,close.save(),close.behavior());check(SceneAttributes.objectState(closed,def.behaviour(),"closed").equals("closed"),"empty state means native factory default");
  var props=def.definition().deepCopy();props.addProperty("disable_switch_by_players",true);var locked=new SceneRegistry.Definition(def.id(),def.name(),def.behaviour(),def.profile(),props);check(SceneInteraction.prepare(object,locked,source,now)==null,"factory lock respected");
  var settings=SceneNbt.read(object.configuration());var values=new LinkedHashMap<>(settings.values());values.put("isSwitchByPlayersDisabledFromEditor",SceneNbt.number(1,(byte)1));var lockedObject=object.configured(SceneNbt.write(new SceneNbt.Root(settings.name(),values)));check(SceneInteraction.prepare(lockedObject,def,source,now)==null,"per-object editor lock respected");
  byte[] nbt=SceneNbt.write(new SceneNbt.Root("",Map.of("settings",new SceneNbt.Tag(10,Map.of("teleportOnInteraction",SceneNbt.number(1,(byte)1))))));var in=new DataInputStream(new ByteArrayInputStream(PortalPackets.configuration(nbt)));byte[] compressed=in.readNBytes(in.readInt());check(!in.readBoolean()&&in.available()==0,"native portal DTO framing");byte[] plain=Zstd.decompress(compressed,(int)Zstd.decompressedSize(compressed));check(SceneNbt.read(plain).values().get("teleportOnInteraction").value().equals((byte)1),"portal DTO contains settings compound");var temporary=java.nio.file.Files.createTempDirectory("door-collision-");try{var file=temporary.resolve("assets/test/Door.aabb");java.nio.file.Files.createDirectories(file.getParent());var buf=java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);buf.put(new byte[]{65,65,66,66}).putInt(3);int index=0;for(String part:List.of("closed_1","open_1","frame")){byte[] text=part.getBytes(java.nio.charset.StandardCharsets.UTF_8);buf.putShort((short)text.length).put(text);float x=index++*2;buf.putFloat(x).putFloat(0).putFloat(0).putFloat(x+1).putFloat(2).putFloat(.1f);}java.nio.file.Files.write(file,Arrays.copyOf(buf.array(),buf.position()));var collisionProps=def.definition().deepCopy();collisionProps.addProperty("aabb","test:door.aabb");var collisionDef=new SceneRegistry.Definition(def.id(),def.name(),def.behaviour(),def.profile(),collisionProps);var boxes=new CollisionBoxes(temporary);var closedBoxes=boxes.get(collisionDef,object);var openBoxes=boxes.get(collisionDef,open);check(closedBoxes.size()==2&&closedBoxes.get(0).x()==0&&closedBoxes.get(1).x()==4,"closed native mesh + static frame");check(openBoxes.size()==2&&openBoxes.get(0).x()==2&&openBoxes.get(1).x()==4,"open native mesh + static frame");check(boxes.get(collisionDef,object)==closedBoxes,"state-specific geometry cached");check(boxes.feedback(collisionDef).size()==3,"interaction feedback remains independent of collision state");}finally{try(var walk=java.nio.file.Files.walk(temporary)){for(var path:walk.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
  sceneInstanceIds(object,def);
  System.out.println("PASS "+checks+" scene interaction, native portal and imported instance checks");
 }
 static void sceneInstanceIds(SceneObject fixture,SceneRegistry.Definition definition){
  byte[] first=fixture.record(),second=fixture.record();
  int handleAt=SceneObject.prefix(first).transformAt()-20;
  ByteBuffer.wrap(first).putInt(handleAt,2992).putLong(handleAt+4,123).putLong(handleAt+12,0);
  ByteBuffer.wrap(second).putInt(handleAt,2992).putLong(handleAt+4,0).putLong(handleAt+12,123);
  SceneHashWire.configure(List.of(definition));
  var instances=new SceneHashWire.SceneInstances();
  byte[] source=sceneChunk(List.of(first),List.of(second));
  byte[] wire=SceneHashWire.record(source,instances);
  var scene=ScenePayload.parseHashWire(wire).channels();
  int wireHandleAt=handleAt+SceneHashWire.GROWTH;
  int a=ByteBuffer.wrap(scene.objects().get(0)).getInt(wireHandleAt);
  int b=ByteBuffer.wrap(scene.containerObjects().get(0)).getInt(wireHandleAt);
  check(a>=SceneHashWire.FIRST_SCENE_HANDLE&&b>=SceneHashWire.FIRST_SCENE_HANDLE&&a!=b,
      "distinct scene identities share neither the imported handle nor the same UUID hash slot");
  check(ByteBuffer.wrap(scene.objects().get(0)).getLong(wireHandleAt+4)==123
      &&ByteBuffer.wrap(scene.containerObjects().get(0)).getLong(wireHandleAt+12)==123,"wire retains scene UUIDs");
  byte[] again=SceneHashWire.record(sceneChunk(List.of(second),List.of(first)),instances);
  var reverse=ScenePayload.parseHashWire(again).channels();
  check(ByteBuffer.wrap(reverse.objects().get(0)).getInt(wireHandleAt)==b
      &&ByteBuffer.wrap(reverse.containerObjects().get(0)).getInt(wireHandleAt)==a,"subsequent chunk loads reuse handles");
  check(!Arrays.equals(Arrays.copyOfRange(source,17,33),Arrays.copyOfRange(wire,17,33)),
      "cache keys invalidate the pre-fix objects");
  check(Arrays.equals(wire,SceneHashWire.record(source,instances)),"unchanged wire keeps its cache key");
 }
 static byte[] sceneChunk(List<byte[]> flat,List<byte[]> container){
  byte[] scenery=new ScenePayload.Channels(flat,true,true,container,new byte[0],new byte[12]).encode();
  return ByteBuffer.allocate(45+scenery.length)
      .putInt(0).putInt(0).put((byte)1).putLong(0).putLong(1).putLong(2)
      .putInt(0).putInt(4).putInt(0).put(scenery).array();
 }
}

