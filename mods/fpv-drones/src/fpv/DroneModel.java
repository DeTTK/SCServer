package fpv;
import java.nio.*;
import java.util.*;
import static fpv.NativeFlight.*;
import static org.lwjgl.opengl.GL33.*;

/** Reads the installed stock FPV asset using this client's own MCSB and OL loaders. */
final class DroneModel {
    static final String ROOT="models/fpv/";
    static final class Mesh {
        String name; float[] vertices; int vao,vbo,count,texture; float[] pivots=new float[8]; boolean blades;
    }
    final List<Mesh> meshes=new ArrayList<>();
    private final List<Object> textures=new ArrayList<>();
    int emission;
    private Object resource(String file)throws Exception {
        return Class.forName("779455578").getConstructor(String.class,String.class).newInstance("stalkermobs",ROOT+file);
    }
    private int texture(String file)throws Exception {
        Object cache=Class.forName("1016831644").getField("long").get(null);
        Object t=call(cache,"short","530507599",resource(file));textures.add(t);
        return (Integer)call(t,"default","int");
    }
    void load()throws Exception {
        Object model=Class.forName("530006582").getConstructor(Class.forName("779455578")).newInstance(resource("fpv.mcsb"));
        Object reader=Class.forName("1182339164").getConstructor().newInstance();
        try {
            call(reader,"try","void",model,false);
            Object scales=call(model,"public","1184405233");
            float scale=((Number)get(scales,"extends")).floatValue()/32767f;
            float uvScale=((Number)get(scales,"catch")).floatValue()/32767f;
            Class<?> base=Class.forName("1639883046");
            for(Object part:(List<?>)get(model,"instanceof")) {
                Mesh mesh=new Mesh();mesh.name=(String)base.getField("char").get(part);mesh.blades=mesh.name.equals("helix");
                ByteBuffer data=((ByteBuffer)call(get(part,"class"),"throws","java.nio.ByteBuffer")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer indices=((ByteBuffer)call(get(part,"native"),"throws","java.nio.ByteBuffer")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int start=data.position(),indexStart=indices.position();
                int normal=(Integer)get(part,"catch"),uv=(Integer)get(part,"boolean");
                mesh.count=base.getField("synchronized").getInt(part);mesh.vertices=new float[mesh.count*8];
                boolean wide=(Boolean)call(part,"final","boolean");
                for(int i=0;i<mesh.count;i++) {
                    int index=wide?indices.getInt(indexStart+i*4):Short.toUnsignedInt(indices.getShort(indexStart+i*2));
                    int p=start+index*8,n=start+normal+index*4,t=start+uv+index*4,o=i*8;
                    // Stock model nose is -Z, flight coordinates use +Z.
                    mesh.vertices[o]=-data.getShort(p)*scale;mesh.vertices[o+1]=data.getShort(p+2)*scale;mesh.vertices[o+2]=-data.getShort(p+4)*scale;
                    mesh.vertices[o+3]=-data.get(n)/127f;mesh.vertices[o+4]=data.get(n+1)/127f;mesh.vertices[o+5]=-data.get(n+2)/127f;
                    mesh.vertices[o+6]=data.getShort(t)*uvScale;mesh.vertices[o+7]=data.getShort(t+2)*uvScale;
                }
                if(mesh.blades) {
                    float[] minX={9,9,9,9},minZ={9,9,9,9},maxX={-9,-9,-9,-9},maxZ={-9,-9,-9,-9};
                    for(int i=0;i<mesh.count;i++){float x=mesh.vertices[i*8],z=mesh.vertices[i*8+2];int r=(z<0?2:0)+(x<0?0:1);minX[r]=Math.min(minX[r],x);maxX[r]=Math.max(maxX[r],x);minZ[r]=Math.min(minZ[r],z);maxZ[r]=Math.max(maxZ[r],z);}
                    for(int r=0;r<4;r++){mesh.pivots[r*2]=(minX[r]+maxX[r])*.5f;mesh.pivots[r*2+1]=(minZ[r]+maxZ[r])*.5f;}
                }
                meshes.add(mesh);
            }
        } finally {
            Object data=get(reader,"catch");if(data==null&&!((List<?>)get(model,"instanceof")).isEmpty())data=get(get(((List<?>)get(model,"instanceof")).get(0),"class"),"implements");if(data!=null)call(Class.forName("802726662"),"byte","void",data);
        }
        if(meshes.isEmpty())throw new IllegalStateException("FPV stock model has no meshes");
        int diffuse=texture("fpv_diff.ol"),blade=texture("fpv_blade.ol");emission=texture("fpv_emi.ol");
        for(Mesh mesh:meshes) {
            mesh.texture=mesh.blades?blade:diffuse;
            mesh.vao=glGenVertexArrays();mesh.vbo=glGenBuffers();glBindVertexArray(mesh.vao);glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);
            glBufferData(GL_ARRAY_BUFFER,mesh.vertices,GL_STATIC_DRAW);
            for(int a=0;a<3;a++){glEnableVertexAttribArray(a);glVertexAttribPointer(a,a==2?2:3,GL_FLOAT,false,32,a==0?0L:a==1?12L:24L);}
        }
        System.err.println("[fpv-drones] Stock model stalkermobs:"+ROOT+"fpv.mcsb loaded: "+meshes.stream().map(m->m.name+"="+m.count/3+" triangles").toList());
    }
    void close(){
        for(Mesh m:meshes){if(m.vbo!=0)glDeleteBuffers(m.vbo);if(m.vao!=0)glDeleteVertexArrays(m.vao);}meshes.clear();
        for(Object t:textures)try{call(t,"default","void");}catch(Exception ignored){}textures.clear();
    }
}
