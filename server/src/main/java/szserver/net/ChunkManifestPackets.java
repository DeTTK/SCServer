package szserver.net;

import java.io.*;
import java.util.*;

/** Actual 1980173742/297896610 and 866048497 coordinate/nullable-revision contract. */
public final class ChunkManifestPackets {
    public record Entry(int x,int z,UUID revision){}
    public record Request(int x,int z,UUID revision,boolean release){}
    public static byte[] body(List<Entry> entries){
        if(entries.size()>4096)throw new IllegalArgumentException("chunk_manifest_count");return Wire.bytes(out->{out.writeInt(entries.size());for(var entry:entries){out.writeInt(entry.x());out.writeInt(entry.z());out.writeBoolean(entry.revision()==null);if(entry.revision()!=null){out.writeLong(entry.revision().getMostSignificantBits());out.writeLong(entry.revision().getLeastSignificantBits());}}});
    }
    public static byte[] frame(List<Entry> entries){return Wire.dynamic("1980173742",body(entries));}
    public static List<Entry> demand(byte[] body)throws IOException{
        var in=Wire.input(body);int count=in.readInt();if(count<0||count>4096)throw new IOException("chunk_batch_limit");List<Entry> entries=new ArrayList<>();
        for(int n=0;n<count;n++){int x=in.readInt(),z=in.readInt(),absent=in.readUnsignedByte();if(absent>1)throw new IOException("chunk_revision_presence");UUID revision=absent==1?null:new UUID(in.readLong(),in.readLong());entries.add(new Entry(x,z,revision));}
        if(in.available()!=0)throw new IOException("chunk_batch_trailing");return List.copyOf(entries);
    }
    /** Current mixed nullable entries plus the old uniform9-byte explicit unload extension. */
    public static List<Request> requests(byte[] body)throws IOException {
        var in=Wire.input(body);int count=in.readInt();if(count<0||count>4096)throw new IOException("chunk_batch_limit");
        if(count>0&&body.length==4+count*9){List<Request> result=new ArrayList<>();for(int n=0;n<count;n++){int x=in.readInt(),z=in.readInt(),flag=in.readUnsignedByte();if(flag>1)throw new IOException("chunk_revision_presence");result.add(new Request(x,z,null,flag==0));}return List.copyOf(result);}
        return demand(body).stream().map(e->new Request(e.x(),e.z(),e.revision(),false)).toList();
    }
}
