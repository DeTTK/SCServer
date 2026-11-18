package szserver.world;
import java.util.*;
/** Authored source and original active capture phase. The enclosing controller identifies its target. */
public record ActorCarouselState(String asset,int networkId,String profile,String definition,String record,int phase) {
 public ActorCarouselState {
  if(asset==null||asset.isEmpty()||asset.length()>1024||networkId<0||networkId>32767||profile==null||profile.length()>128||definition==null||definition.length()>65536||record==null||record.length()>90000||phase< -1||phase>299)throw new IllegalArgumentException("actor_carousel_state");
  byte[] bytes=Base64.getDecoder().decode(record);if(bytes.length>65536||!Base64.getEncoder().encodeToString(bytes).equals(record))throw new IllegalArgumentException("actor_carousel_source");
 }
 public ActorCarouselState phase(int value){return new ActorCarouselState(asset,networkId,profile,definition,record,value);}
}
