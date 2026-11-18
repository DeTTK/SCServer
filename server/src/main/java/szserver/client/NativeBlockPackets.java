package szserver.client;

import szserver.world.World;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Bounded SZBD envelope only. Full NBT decoding belongs to command workers. */
public final class NativeBlockPackets {
    public static final int MAX_ACTIONS=4*1024*1024,MAX_INPUT=MAX_ACTIONS+16464;
    public static final class State {
        private final byte[] bytes;
        State(byte[] bytes){this.bytes=bytes;}
        public byte[] bytes(){return bytes.clone();}
        public int size(){return bytes.length;}
    }
    public record Delta(World.Block position,int beforeId,int beforeMetadata,int afterId,int afterMetadata,State before,State after){}
    public record Batch(int legacyLength,int version,List<Delta> deltas,int retainedBytes){}
    public static Batch parse(byte[] actions)throws IOException{
        int marker=-1,end=actions.length;
        if(actions.length>=8&&ByteBuffer.wrap(actions).getInt(actions.length-8)==0x535a4245){int size=ByteBuffer.wrap(actions).getInt(actions.length-4);marker=actions.length-size;end-=8;if(size<14||marker<0||marker>actions.length-4||ByteBuffer.wrap(actions).getInt(marker)!=0x535a4244)throw new IOException("invalid_client_block_delta_footer");}
        else for(int at=actions.length-4;at>=0;at--)if(actions[at]==83&&actions[at+1]==90&&actions[at+2]==66&&actions[at+3]==68){marker=at;break;}
        if(marker<0)return new Batch(actions.length,0,List.of(),0);
        if(marker+6>actions.length)throw new IOException("truncated_client_block_delta");
        int version=actions[marker+4]&255,count=actions[marker+5]&255;
        if(version<1||version>3||count<1||count>32)throw new IOException("unsupported_client_block_delta");
        if(version==3&&end==actions.length)throw new IOException("missing_client_block_delta_footer");
        if(actions.length-marker>MAX_ACTIONS)throw new IOException("client_block_delta_batch_too_large");
        ByteBuffer b=ByteBuffer.wrap(actions);b.position(marker+6);b.limit(end);List<Delta> values=new ArrayList<>();int bytes=0;
        try{for(int n=0;n<count;n++){int x=b.getInt(),y=b.getInt(),z=b.getInt(),before=b.getShort()&65535,bm=b.get()&255,after=b.getShort()&65535,am=b.get()&255;
            if(y<0||y>255||before>4095||after>4095||bm>15||am>15)throw new IOException("client_block_delta_voxel_bounds");
            State previous=null,next=null;if(version>=2){State[] states=new State[2];for(int i=0;i<2;i++){int length=b.getInt();if(length<-1||length>CompleteTileState.MAX_STATE||length>b.remaining())throw new IOException("invalid_client_block_delta_state_size");if(length>=0){byte[] state=new byte[length];b.get(state);states[i]=new State(state);bytes+=length;}}previous=states[0];next=states[1];}
            values.add(new Delta(new World.Block(x,y,z),before,bm,after,am,previous,next));}
        }catch(BufferUnderflowException|IllegalArgumentException error){throw new IOException("truncated_client_block_delta_record",error);}
        if(b.hasRemaining())throw new IOException("invalid_client_block_delta_size");return new Batch(marker,version,List.copyOf(values),bytes);
    }
    public static boolean reachable(double[] origin,World.Block p){double x=p.x()+.5-origin[0],y=p.y()+.5-(origin[1]+1.62),z=p.z()+.5-origin[2];return Double.isFinite(x+y+z)&&x*x+y*y+z*z<=100;}
    public static World.Block target(byte[] body,boolean destruction)throws IOException{
        if(body.length!=(destruction?20:16))throw new IOException("native_block_target_size");ByteBuffer b=ByteBuffer.wrap(body);int x=b.getInt(),y=b.getInt(),z=b.getInt(),face=b.getInt();if(y<0||y>255||face<0||face>5)throw new IOException("native_block_target_bounds");if(destruction){int action=b.getInt();if(action<0||action>7)throw new IOException("native_block_destroy_action");}return new World.Block(x,y,z);
    }
    private NativeBlockPackets(){}
}
