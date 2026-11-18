package szserver.world;

import java.util.*;

/** Portable identity and host coordinates; native implementation details stay in the adapter payload. */
public record ActorControllerState(int version,String id,long incarnation,int originX,int originZ,String payload,ActorPresentation presentation,List<ActorCarouselState> carousels) {
    public ActorControllerState(int version,String id,long incarnation,int originX,int originZ,String payload,ActorPresentation presentation){this(version,id,incarnation,originX,originZ,payload,presentation,List.of());}
    public ActorControllerState(int version,String id,long incarnation,int originX,int originZ,String payload){this(version,id,incarnation,originX,originZ,payload,ActorPresentation.EMPTY);}
    public static final int MAX_BYTES=1_048_576;
    public ActorControllerState {
        if(presentation==null)presentation=ActorPresentation.EMPTY; // Old journals/checkpoints omit this optional projection.
        carousels=carousels==null?List.of():List.copyOf(carousels);if(carousels.size()>8)throw new IllegalArgumentException("actor_carousel_count");
        if(version!=1||id==null||!UUID.fromString(id).toString().equals(id)||incarnation<1||
           originX%16!=0||originZ%16!=0||Math.abs((long)originX)>30_000_000||Math.abs((long)originZ)>30_000_000||
           payload==null||payload.isEmpty()||payload.length()>((MAX_BYTES+2)/3)*4)
            throw new IllegalArgumentException("actor_controller_header");
        byte[] bytes=Base64.getDecoder().decode(payload);
        if(bytes.length>MAX_BYTES||!Base64.getEncoder().encodeToString(bytes).equals(payload))throw new IllegalArgumentException("actor_controller_payload");
        long total=payload.length();for(var value:carousels)total+=value.record().length()+value.definition().length()*2L+value.asset().length()+value.profile().length()+256;
        for(String value:presentation.roots().values())total+=value.length()*2L;for(String value:presentation.attributes().values())total+=value.length();
        if(total>1_800_000)throw new IllegalArgumentException("actor_controller_envelope_size");
    }
    public byte[] bytes(){return Base64.getDecoder().decode(payload);}
    public ActorControllerState withPresentation(ActorPresentation value){return new ActorControllerState(version,id,incarnation,originX,originZ,payload,value,carousels);}
    public ActorControllerState withCarousels(List<ActorCarouselState> value){return new ActorControllerState(version,id,incarnation,originX,originZ,payload,presentation,value);}
    public long retainedCharacters(){long size=payload.length();for(var c:carousels)size+=c.record().length()+c.definition().length()*2L+256;for(String v:presentation.roots().values())size+=v.length()*2L;for(String v:presentation.attributes().values())size+=v.length();return size;}
    public static ActorControllerState of(UUID id,long incarnation,int originX,int originZ,byte[] bytes){
        Objects.requireNonNull(bytes);if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("actor_controller_payload");
        return new ActorControllerState(1,id.toString(),incarnation,originX,originZ,Base64.getEncoder().encodeToString(bytes));
    }
}
