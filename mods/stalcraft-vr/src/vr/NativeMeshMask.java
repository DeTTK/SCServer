package vr;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;
import static vr.NativeAccess.*;

/** Remove hidden skin triangles with an owned index buffer; native materials stay intact. */
final class NativeMeshMask implements AutoCloseable {
    record Filter(int[] indices,int original){}
    record Cached(BitSet hidden,int buffer,int count){}
    private final Map<Object,List<Cached>> cache=new IdentityHashMap<>();
    private static Object base(Object data,String field)throws Exception{return type("1639883046").getField(field).get(data);}
    static Filter filter(Object data,BitSet hidden)throws Exception {
        int count=((Number)base(data,"private")).intValue()*3;
        if(!(boolean)base(data,"const"))return null;
        ByteBuffer vertices=((ByteBuffer)get(data,"switch")).duplicate().order(ByteOrder.nativeOrder()),indices=((ByteBuffer)get(data,"for")).duplicate().order(ByteOrder.nativeOrder());
        short[] palette=(short[])base(data,"throw");int vertexCount=((Number)base(data,"while")).intValue(),version=((Number)base(data,"assert")).intValue();
        int boneOffset=((Number)get(data,"final")).intValue(),weightOffset=((Number)get(data,"float")).intValue();boolean wide=((Number)call(data,"return","int")).intValue()==GL_UNSIGNED_INT;
        boolean[] visible=new boolean[vertexCount];
        for(int i=0;i<vertexCount;i++){
            double blocked=0,total=0;for(int j=0;j<(version>=3?4:1);j++){int bone=vertices.get(boneOffset+i*4+j)&255;double weight=version>=3?(vertices.get(weightOffset+i*4+j)&255)/255.0:1;if(weight==0)continue;if(bone>=palette.length)throw new IllegalStateException("Native skin palette index "+bone);total+=weight;if(hidden.get(palette[bone]&65535))blocked+=weight;}
            visible[i]=blocked<total*.5||total==0;
        }
        int[] selected=new int[count];int n=0;for(int i=0;i<count;i+=3){int a=index(indices,i,wide),b=index(indices,i+1,wide),c=index(indices,i+2,wide);if(a>=vertexCount||b>=vertexCount||c>=vertexCount)throw new IllegalStateException("Native triangle index outside mesh");if(visible[a]&&visible[b]&&visible[c]){selected[n++]=a;selected[n++]=b;selected[n++]=c;}}
        return new Filter(Arrays.copyOf(selected,n),count);
    }
    private static int index(ByteBuffer b,int i,boolean wide){return wide?b.getInt(i*4):b.getShort(i*2)&65535;}
    Object draw(Object mesh,Object shader,NativePose pose)throws Exception {
        if(pose.hidden.isEmpty())return null;Object data=get(mesh,"short");
        List<Cached> list=cache.computeIfAbsent(data,k->new ArrayList<>());Cached mask=list.stream().filter(c->c.hidden.equals(pose.hidden)).findFirst().orElse(null);
        if(mask==null){Filter f=filter(data,pose.hidden);if(f==null||f.indices.length==f.original)return null;int old=glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING),buffer=glGenBuffers();try{glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,buffer);glBufferData(GL_ELEMENT_ARRAY_BUFFER,BufferUtils.createIntBuffer(f.indices.length).put(f.indices).flip(),GL_STATIC_DRAW);}finally{glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,old);}mask=new Cached((BitSet)pose.hidden.clone(),buffer,f.indices.length);list.add(mask);}
        if(mask.count!=0){call(mesh,"throws","void",shader,pose.pose);if((boolean)base(data,"default"))call(mesh,"static","void",shader,pose.pose);int old=glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);try{glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mask.buffer);glDrawElements(GL_TRIANGLES,mask.count,GL_UNSIGNED_INT,0L);}finally{glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,old);}}
        return Boolean.FALSE;
    }
    public void close(){for(List<Cached> list:cache.values())for(Cached c:list)glDeleteBuffers(c.buffer);cache.clear();}
}
