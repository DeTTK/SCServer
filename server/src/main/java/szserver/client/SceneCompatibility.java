package szserver.client;

import com.google.gson.*;
import szserver.net.Wire;
import szserver.world.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Lossless captured object serializer migrations, separate from stable-name registry translation. */
public final class SceneCompatibility {
    private final String metadataDigest;
    public String metadataDigest(){return metadataDigest;}
    private final Map<String,JsonObject> entries=new HashMap<>();
    public SceneCompatibility(Path path,SceneRegistry.Manifest target)throws IOException{
        if(RuntimeFiles.size(path)>8*1024*1024)throw new IOException("scene_codec_metadata_limit");byte[] raw=RuntimeFiles.read(path);if(raw.length>8*1024*1024)throw new IOException("scene_codec_metadata_limit");try{metadataDigest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}JsonObject data=JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();if(data.get("version").getAsInt()!=1||!data.get("target_build").getAsString().equals(target.build()))throw new IOException("scene_codec_registry_mismatch");
        for(JsonElement e:data.getAsJsonArray("entries")){JsonObject entry=e.getAsJsonObject();if(entries.put(entry.get("name").getAsString(),entry)!=null)throw new IOException("scene_codec_duplicate_name");}
    }
    public boolean hasMetadata(String asset){return entries.containsKey(asset);}
    public byte[] object(byte[] frame,SceneRegistry.Definition definition){
        JsonObject entry=entries.get(definition.name());SceneObject.Prefix layout=SceneObject.prefix(frame);int flags=layout.flags(),packed=layout.packed(),start=layout.behaviourAt();
        if(entry==null){if((flags&0x400)!=0||(packed&1)!=0||definition.behaviour().equals("modeller_mesh"))throw new IllegalArgumentException("scene_codec_missing_asset_requires_migration:"+definition.name());return frame;}
        String behaviour=entry.get("behaviour").getAsString(),reader=entry.get("reader").getAsString();if(!behaviour.equals(definition.behaviour()))throw new IllegalArgumentException("scene_codec_behaviour_mismatch:"+definition.name());
        if(behaviour.equals("modeller_mesh")&&(flags&0x400)==0){
            // The legacy editor provisionally cloned a stateless Lenin template
            // for assets without a captured template. An exactly empty NBT body
            // contains no mesh state to preserve. Restore the native sr_model_live
            // factory's empty v5 mesh, keeping every generic property and identity.
            // Non-empty NBT is intentionally rejected instead of discarding it.
            if(definition.name().equals("sr_model_live")&&frame.length>=start+4&&
                    frame[start]==10&&frame[start+1]==0&&frame[start+2]==0&&frame[start+3]==0){
                SceneAttributes.layout(frame,"");
                byte[] empty=HexFormat.of().parseHex("000005003f8000000000000000000000");
                byte[] repaired=Wire.concat(Arrays.copyOf(frame,start),empty,Arrays.copyOfRange(frame,start+4,frame.length));
                SceneAttributes.validateCurrent(repaired,behaviour);return repaired;
            }
            // DEV tails have variable strings/lists. Current typed mesh framing locates their
            // start without guessing backwards from the end as the legacy converter does.
            if((flags&(256|128|2|1))!=0){SceneAttributes.validateCurrent(frame,behaviour);return frame;}
            if((packed&1)!=0)return frame;return mesh(frame,layout);
        }
        if((flags&0x400)==0&&(packed&1)==0)return frame;
        if((flags&0x400)==0&&!Set.of("1079929180","15977357").contains(reader))return frame;
        byte[] nativeName=layout.nativeName(),state=new byte[0],unbound=new byte[0],prefix=Arrays.copyOf(frame,start);Map<Integer,JsonObject> descriptors=new HashMap<>();for(JsonElement item:entry.getAsJsonArray("attributes")){JsonObject descriptor=item.getAsJsonObject();descriptors.put(descriptor.get("id").getAsInt(),descriptor);}
        if((flags&0x400)!=0){int stateAt=start-nativeName.length;ByteBuffer input=ByteBuffer.wrap(frame);input.position(stateAt);byte[] name=ScenePayload.utf(input),clock=ScenePayload.take(input,8);nativeName=(flags&0x200)!=0?ScenePayload.utf(input):new byte[0];prefix=Wire.concat(Arrays.copyOf(frame,stateAt),nativeName);start=input.position();flags&=~0x400;
            if(descriptors.containsKey(467))state=Wire.concat(new byte[]{1,(byte)211},clock,new byte[]{0},name);else unbound=Wire.concat(clock,new byte[]{0},name);
        }
        ByteBuffer body=ByteBuffer.wrap(frame);body.position(start);int behaviourEnd;
        if((frame[start]&255)==10)behaviourEnd=ScenePayload.namedNbt(body);else{String actual=(flags&0x200)!=0?text(nativeName):behaviour;if(!actual.equals("lootable"))throw new IllegalArgumentException("scene_unrecognized_behaviour:"+actual);int presence=body.get()&255;if((presence&2)!=0)ScenePayload.utf(body);behaviourEnd=body.position();}
        byte[] nativeBehaviour=Arrays.copyOfRange(frame,start,behaviourEnd);if(unbound.length!=0)nativeBehaviour=nbtExtra(nativeBehaviour,"sz_legacy_animation_state",unbound);
        byte[] attributes,suffix;
        if((packed&1)!=0){Attributes result=attributes(Arrays.copyOfRange(frame,behaviourEnd,frame.length),entry,descriptors,flags,packed);attributes=result.encoded;suffix=Arrays.copyOfRange(frame,behaviourEnd+result.consumed,frame.length);if(result.preserved.length!=0)nativeBehaviour=nbtExtra(nativeBehaviour,"sz_legacy_network_attributes",result.preserved);}
        else{attributes=new byte[]{0};ByteBuffer rest=ByteBuffer.wrap(frame);rest.position(behaviourEnd);suffix(rest,flags,packed);if(rest.hasRemaining())throw new IllegalArgumentException("scene_generic_trailing");suffix=Arrays.copyOfRange(frame,behaviourEnd,frame.length);}
        if(state.length!=0){ByteBuffer input=ByteBuffer.wrap(attributes);int count=ScenePayload.varint(input);int valuesAt=input.position();byte[] previous=Arrays.copyOfRange(attributes,valuesAt,attributes.length),extra=state;attributes=Wire.bytes(out->{Wire.varint(out,count+1);out.write(previous);out.write(extra);});}
        if(!Arrays.equals(attributes,new byte[]{0})||(packed&1)!=0)packed|=1;else{attributes=new byte[0];packed&=~1;}
        byte[] result=Wire.concat(prefix,nativeBehaviour,attributes,suffix);ByteBuffer.wrap(result).putInt(2,flags).put(6,(byte)packed);return result;
    }
    private static String text(byte[] utf){try{return new DataInputStream(new ByteArrayInputStream(utf)).readUTF();}catch(IOException e){throw new IllegalArgumentException("scene_modified_utf",e);}}
    private static byte[] nbtExtra(byte[] nbt,String name,byte[] opaque){if(nbt.length<4||nbt[0]!=10||nbt[nbt.length-1]!=0)throw new IllegalArgumentException("scene_legacy_state_requires_nbt");return Wire.bytes(out->{out.write(nbt,0,nbt.length-1);out.writeByte(7);out.writeUTF(name);out.writeInt(opaque.length);out.write(opaque);out.writeByte(0);});}
    private record Attributes(byte[] encoded,int consumed,byte[] preserved){}
    private static Attributes attributes(byte[] data,JsonObject entry,Map<Integer,JsonObject> descriptors,int flags,int packed){
        String behaviour=entry.get("behaviour").getAsString();List<Map<Integer,Integer>> candidates=new ArrayList<>();candidates.add(Map.of());switch(behaviour){
            case "seat"->candidates.add(Map.of(257,270,259,270,273,270));case "stash"->{candidates.add(Map.of(283,280,284,281,285,282));candidates.add(Map.of(269,280,270,281,271,282));}
            case "breakthrough/tank"->candidates.add(Map.of(48,50,49,51,50,52,51,53,1,468));case "breakthrough/med_kit"->candidates.add(Map.of(41,43,1,468));case "airdrop"->candidates.add(Map.of(276,272,275,273,277,274));case "studio_event_emitter"->candidates.add(Map.of(305,300,306,301));
        }
        for(Map<Integer,Integer> mapping:candidates)try{
            ByteBuffer in=ByteBuffer.wrap(data);int count=ScenePayload.varint(in);if(count>256||count>in.remaining()/3)throw new IllegalArgumentException("scene_attribute_count");List<byte[]> values=new ArrayList<>(),opaque=new ArrayList<>();Set<Integer> seen=new HashSet<>();
            for(int n=0;n<count;n++){int old=in.getShort(),current=mapping.getOrDefault(old,old==475?467:old);int at=in.position();
                if(behaviour.equals("resource_extractor")&&(old==308||old==309)){JsonObject legacy=new JsonObject();legacy.addProperty("type",old==309?"java.lang.Long":"legacy.extractor.mode");legacy.addProperty("reader",old==309?"1798539107":"963945232");if(old==308)legacy.addProperty("enumCount",256);value(in,legacy);opaque.add(Wire.concat(ByteBuffer.allocate(2).putShort((short)old).array(),Arrays.copyOfRange(data,at,in.position())));continue;}
                JsonObject descriptor=descriptors.get(current);if(descriptor==null||!seen.add(current))throw new IllegalArgumentException("scene_unresolved_attribute:"+old);value(in,descriptor);values.add(Wire.concat(ByteBuffer.allocate(2).putShort((short)current).array(),Arrays.copyOfRange(data,at,in.position())));
            }int consumed=in.position();suffix(in,flags,packed);if(in.hasRemaining())throw new IllegalArgumentException("scene_attribute_trailing");byte[] encoded=Wire.bytes(out->{Wire.varint(out,values.size());for(byte[] record:values)out.write(record);}),preserved=Wire.bytes(out->{for(byte[] record:opaque)out.write(record);});return new Attributes(encoded,consumed,preserved);
        }catch(IllegalArgumentException|BufferUnderflowException ignored){}
        throw new IllegalArgumentException("scene_attribute_epoch_unresolved:"+behaviour);
    }
    private static void nullable(ByteBuffer in,int size){if(!ScenePayload.bool(in))ScenePayload.take(in,size);}
    private static void value(ByteBuffer in,JsonObject descriptor){
        if(descriptor.get("reader").getAsString().equals("963945232"))in.getLong();if(ScenePayload.bool(in))return;String type=descriptor.get("type").getAsString();switch(type){
            case "java.lang.Boolean"->ScenePayload.bool(in);case "java.lang.Byte"->in.get();case "java.lang.Short","java.lang.Character"->in.getShort();case "java.lang.Integer","java.lang.Float"->in.getInt();case "java.lang.Long","java.lang.Double","java.time.Instant","java.time.Duration","862285083"->in.getLong();case "java.util.UUID"->ScenePayload.take(in,16);case "java.lang.String"->ScenePayload.utf(in);
            case "1839922469"->{ScenePayload.utf(in);ScenePayload.utf(in);ScenePayload.take(in,2);}case "438226217"->{in.get();ScenePayload.utf(in);ScenePayload.utf(in);}case "792097079"->{ScenePayload.utf(in);ScenePayload.utf(in);ScenePayload.utf(in);nullable(in,16);nullable(in,8);ScenePayload.take(in,3);}
            default->{int count=descriptor.has("enumCount")?descriptor.get("enumCount").getAsInt():0;if(count<1||(in.getShort()&65535)>=count)throw new IllegalArgumentException("scene_attribute_type:"+type);}
        }
    }
    private static void suffix(ByteBuffer in,int flags,int packed){
        SceneGenericState.read(in,flags,packed);
    }
    private static int meshEnd(ByteBuffer in){
        int version=in.getShort()&65535;if(version>5)throw new IllegalArgumentException("scene_mesh_version");int materials=version<5?in.getShort()&65535:in.get()&255;for(int n=0;n<materials;n++){ScenePayload.utf(in);ScenePayload.take(in,version<5?2:1);}in.getFloat();ScenePayload.take(in,12*(in.getShort()&65535));ScenePayload.take(in,8*(in.getShort()&65535));int faces=in.getShort()&65535;
        for(int n=0;n<faces;n++){int vertices=in.get()&255;ScenePayload.take(in,6*vertices+2);int flags=in.get()&255;if(version>4){ScenePayload.take(in,in.get()&255);if((flags&64)!=0)ScenePayload.take(in,3);ScenePayload.take(in,(flags&128)!=0?4:1);}else ScenePayload.take(in,(version>2?6:3)+(version>3?1:0)+2);}
        if(version<5){int edges=in.getShort()&65535;for(int n=0;n<edges;n++){ScenePayload.take(in,4);int flags=in.get()&255;ScenePayload.take(in,1+2*(flags>>>1));}}return in.position();
    }
    private static byte[] mesh(byte[] record,SceneObject.Prefix layout){
        int flags=layout.flags(),packed=layout.packed();if((flags&(256|128|2|1))!=0)throw new IllegalArgumentException("scene_mesh_developer_suffix");int fixed=4+((flags&64)!=0?4:0)+((flags&32)!=0?12:0)+((flags&16)!=0?4:0)+((flags&8)!=0?12:0)+((flags&4)!=0?16:0)+((flags&0x10000)!=0?12:0)+((flags&0x20000)!=0?4:0),end=record.length-fixed;
        if((packed&2)!=0){int before=3+((flags&64)!=0?4:0)+((flags&32)!=0?12:0)+((flags&16)!=0?4:0)+((flags&8)!=0?12:0)+((flags&4)!=0?16:0),utfEnd=record.length-1-((flags&0x10000)!=0?12:0)-((flags&0x20000)!=0?4:0);List<Integer> possible=new ArrayList<>();for(int at=layout.behaviourAt()+before;at<utfEnd-1;at++){int size=ByteBuffer.wrap(record).getShort(at)&65535;if(at+2+size==utfEnd)possible.add(at-before);}if(possible.size()!=1)throw new IllegalArgumentException("scene_mesh_suffix_ambiguous");end=possible.get(0);}
        if(end<layout.behaviourAt())throw new IllegalArgumentException("scene_mesh_truncated");byte[] body=Arrays.copyOfRange(record,layout.behaviourAt(),end);
        if(body.length>0&&(body[0]==0||body[0]==1))try{ByteBuffer current=ByteBuffer.wrap(body);current.position(1);meshEnd(current);if(body[0]==0)ScenePayload.utf(current);if(!current.hasRemaining())return record;}catch(IllegalArgumentException|BufferUnderflowException ignored){}
        ByteBuffer old=ByteBuffer.wrap(body);meshEnd(old);ScenePayload.utf(old);if(old.hasRemaining())throw new IllegalArgumentException("scene_legacy_mesh_framing");return Wire.concat(Arrays.copyOf(record,layout.behaviourAt()),new byte[]{0},Arrays.copyOfRange(record,layout.behaviourAt(),record.length));
    }
}
