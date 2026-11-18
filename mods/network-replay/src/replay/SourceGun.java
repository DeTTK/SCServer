package replay;

import java.io.*;
import java.util.*;

/** Source weapon display snapshot adds a packed upgrade/stage-progress value to motifs. */
final class SourceGun {
    static final List<String> SLOTS=List.of("FOREND","MOUNT1","MOUNT2","MOUNT3","MOUNT4","ON_BARREL","MOUNT5","MOUNT6","MOUNT7","MOUNT8","BARREL","SIGHT","SIGHT_ON_MOUNT","MAGAZINE","BUTT","BUTT_ON_MOUNT","ACC1","HANDLE");
    record Attachment(int slot,int item,Short motifProgress){}
    record State(byte[] skin,List<Attachment> attachments,short ammunition,short counter){
        byte[] nativeBytes()throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){
                out.writeByte(skin==null?-1:skin.length);if(skin!=null)out.write(skin);out.writeByte(attachments.size());
                for(Attachment part:attachments){out.writeByte(part.slot());out.writeShort(part.item());}
                out.writeShort(ammunition);out.writeShort(counter);
            }return bytes.toByteArray();
        }
    }
    static State read(DataInput in)throws IOException {
        int length=in.readByte();if(length< -1)throw new IOException("Invalid source weapon skin length");byte[] skin=length== -1?null:SourceAdapters.bytes(in,length);
        int count=in.readByte();if(count<0)throw new IOException("Invalid source weapon attachment count");List<Attachment> attachments=new ArrayList<>();
        for(int n=0;n<count;n++){
            int slot=in.readByte();if(slot< -3||slot>=SLOTS.size())throw new IOException("Invalid source weapon slot "+slot);
            int item=in.readUnsignedShort();Short parameters=slot== -2?in.readShort():null;attachments.add(new Attachment(slot,item,parameters));
        }
        return new State(skin,List.copyOf(attachments),in.readShort(),in.readShort());
    }
}
