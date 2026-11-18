package scvehicles;
import java.nio.*;
import java.util.*;
import static scvehicles.NativeAccess.*;
import static org.lwjgl.opengl.GL33.*;

/** Reads and separates the installed stock Niva asset using this client's own MCSB and OL loaders. */
final class VehicleModel {
    static final String ROOT="models/blocks/vehicles/cars/light_cars/niva/";
    static final class Mesh {
        String name,material; float[] pivot;double radius=VehicleGeometry.RADIUS; VehicleGeometry.Part part=VehicleGeometry.Part.BODY; float[] vertices; int vao,vbo,count,texture;
        int trackSide,trackTexture;VehicleTracks.Loop trackLoop;
    }
    final List<Mesh> meshes=new ArrayList<>();
    private final List<Object> textures=new ArrayList<>();
    int emission;
    VehicleFleet.Type type=VehicleFleet.get(0);
    private final Map<String,Integer> textureIds=new HashMap<>();
    private Object resource(String file)throws Exception {
        return Class.forName("779455578").getConstructor(String.class,String.class).newInstance("customitems",file);
    }
    private int texture(String file)throws Exception {
        Object cache=Class.forName("1016831644").getField("long").get(null);
        Object t=call(cache,"short","530507599",resource(file));textures.add(t);
        return (Integer)call(t,"default","int");
    }
    void load()throws Exception{load(0);}
    void load(int id)throws Exception {
        type=VehicleFleet.get(id);read(type.asset,null,null);
        for(VehicleFleet.Extra extra:type.extras)read("models/blocks/vehicles/"+extra.asset,extra.offset,extra.textures);
        if(meshes.isEmpty())throw new IllegalStateException("Vehicle model has no meshes: "+type.asset);
        var separated=new ArrayList<Mesh>();
        for(Mesh original:meshes){
            if(type.tracked()){
                for(var piece:VehicleTracks.split(original.material,original.vertices,type)){
                    Mesh m=piece(original,VehicleGeometry.Part.BODY,piece.vertices(),piece.pivot(),piece.radius());m.trackSide=piece.side();m.trackLoop=piece.loop();
                    if(m.trackSide!=0)m.name=(m.trackLoop==null?"ROLLER_":"TRACK_")+(m.trackSide>0?"LEFT/":"RIGHT/")+original.material;
                    if(m.trackSide==0&&type.door!=null){for(var body:VehicleGeometry.splitType(original.material,m.vertices,type))separated.add(piece(original,body.part(),body.vertices(),body.part()==VehicleGeometry.Part.BODY?new float[3]:doorPivot(body.part()),m.radius));}
                    else separated.add(m);
                }
            }
            else if(id==0){for(var piece:VehicleGeometry.splitNative(original.name,original.material,original.vertices,true))separated.add(piece(original,piece.part(),piece.vertices(),VehicleGeometry.pivot(piece.part()),VehicleGeometry.RADIUS));}
            else if(!type.tracked()&&(original.material.contains("wheel")||original.material.contains("kolesa")||original.material.contains("tire")||type.kind.equals("toy"))){
                var toyVertices=new HashSet<String>();
                if(type.kind.equals("toy"))for(float[] c:VehicleGeometry.components(original.vertices)){
                    float[] lo={9,9,9},hi={-9,-9,-9};for(int i=0;i<c.length;i+=8)for(int a=0;a<3;a++){lo[a]=Math.min(lo[a],c[i+a]);hi[a]=Math.max(hi[a],c[i+a]);}
                    double dx=hi[0]-lo[0],dy=hi[1]-lo[1],dz=hi[2]-lo[2];
                    if(dy>.1&&dz>.1&&dx<dy*.7&&dy/dz>.8&&dy/dz<1.2)for(int i=0;i<c.length;i+=8)toyVertices.add(c[i]+","+c[i+1]+","+c[i+2]);
                }
                var fixed=new ArrayList<float[]>();var buckets=new ArrayList<List<float[]>>();for(double[] w:type.wheels)buckets.add(new ArrayList<>());
                for(int i=0;i<original.vertices.length;i+=24){double x=0,y=0,z=0;for(int k=0;k<3;k++){x+=original.vertices[i+k*8]/3;y+=original.vertices[i+k*8+1]/3;z+=original.vertices[i+k*8+2]/3;}
                    int best=0;double distance=Double.POSITIVE_INFINITY;for(int k=0;k<type.wheels.length;k++){double[] w=type.wheels[k];double d=Math.pow(x-w[0],2)+Math.pow(y-w[1],2)+Math.pow(z-w[2],2);if(d<distance){distance=d;best=k;}}
                    double[] selected=type.wheels[best];
                    float[] triangle=Arrays.copyOfRange(original.vertices,i,i+24);
                    if(type.kind.equals("toy")&&!toyVertices.contains(triangle[0]+","+triangle[1]+","+triangle[2])||Math.hypot(y-selected[1],z-selected[2])>selected[3]*1.18||Math.abs(x-selected[0])>selected[3]*.95)fixed.add(triangle);else buckets.get(best).add(triangle);
                }
                if(!fixed.isEmpty()){float[] vertices=new float[fixed.size()*24];int offset=0;for(float[] t:fixed){System.arraycopy(t,0,vertices,offset,24);offset+=24;}separated.add(piece(original,VehicleGeometry.Part.BODY,vertices,new float[3],1));}
                for(int i=0;i<buckets.size();i++)if(!buckets.get(i).isEmpty()){
                    float[] vertices=new float[buckets.get(i).size()*24];int offset=0;for(float[] t:buckets.get(i)){System.arraycopy(t,0,vertices,offset,24);offset+=24;}
                    double[] w=type.wheels[i];VehicleGeometry.Part part=i<2?(w[0]>0?VehicleGeometry.Part.FRONT_LEFT:VehicleGeometry.Part.FRONT_RIGHT):(w[0]>0?VehicleGeometry.Part.REAR_LEFT:VehicleGeometry.Part.REAR_RIGHT);
                    separated.add(piece(original,part,vertices,new float[]{(float)w[0],(float)w[1],(float)w[2]},w[3]));
                }
            }else for(var piece:VehicleGeometry.splitType(original.material,original.vertices,type))separated.add(piece(original,piece.part(),piece.vertices(),doorPivot(piece.part()),VehicleGeometry.RADIUS));
        }
        meshes.clear();meshes.addAll(separated);emission=meshes.get(0).texture;
        for(Mesh mesh:meshes){
            mesh.vao=glGenVertexArrays();mesh.vbo=glGenBuffers();glBindVertexArray(mesh.vao);glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);
            int stride=mesh.trackLoop==null?32:48;
            glBufferData(GL_ARRAY_BUFFER,mesh.trackLoop==null?mesh.vertices:mesh.trackLoop.interleaved(mesh.vertices,type.id==16),GL_STATIC_DRAW);
            for(int a=0;a<3;a++){glEnableVertexAttribArray(a);glVertexAttribPointer(a,a==2?2:3,GL_FLOAT,false,stride,a==0?0L:a==1?12L:24L);}
            if(mesh.trackLoop!=null){
                glEnableVertexAttribArray(3);glVertexAttribPointer(3,4,GL_FLOAT,false,stride,32L);
                mesh.trackTexture=glGenTextures();glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,mesh.trackTexture);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,VehicleTracks.SAMPLES,1,0,GL_RGBA,GL_FLOAT,mesh.trackLoop.texture);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glActiveTexture(GL_TEXTURE0);
            }
        }
        System.err.println("[sc-vehicles] Loaded "+type.name+": "+meshes.size()+" articulated parts");
    }
    private float[] doorPivot(VehicleGeometry.Part part){
        if(part==VehicleGeometry.Part.STEERING&&type.steering!=null)return new float[]{(float)type.steering[0],(float)type.steering[1],(float)type.steering[2]};
        if(part==VehicleGeometry.Part.BODY)return new float[3];boolean rear=part==VehicleGeometry.Part.DOOR_REAR_LEFT||part==VehicleGeometry.Part.DOOR_REAR_RIGHT,left=part==VehicleGeometry.Part.DOOR_LEFT||part==VehicleGeometry.Part.DOOR_REAR_LEFT;
        VehicleFleet.Door door=rear?type.rearDoor:type.door;if(door==null)return new float[3];double[] h=door.hinge;
        if(!left&&door.rightHinge!=null){h=door.rightHinge;return new float[]{(float)h[0],(float)h[1],(float)h[2]};}
        return new float[]{(float)((left?1:-1)*h[0]),(float)h[1],(float)h[2]};
    }
    private Mesh piece(Mesh original,VehicleGeometry.Part part,float[] vertices,float[] pivot,double radius){Mesh m=new Mesh();m.name=part+"/"+original.material;m.material=original.material;m.part=part;m.vertices=vertices;m.count=vertices.length/8;m.texture=original.texture;m.pivot=pivot;m.radius=radius;return m;}
    private void read(String asset,double[] offset,Map<String,String> bindings)throws Exception {
        int first=meshes.size();
        Object model=Class.forName("530006582").getConstructor(Class.forName("779455578")).newInstance(resource(asset));
        Object reader=Class.forName("1182339164").getConstructor().newInstance();
        try {
            call(reader,"try","void",model,false);
            Object scales=call(model,"public","1184405233");
            float scale=((Number)get(scales,"extends")).floatValue()/32767f;
            float uvScale=((Number)get(scales,"catch")).floatValue()/32767f;
            Class<?> base=Class.forName("1639883046");
            for(Object part:(List<?>)get(model,"instanceof")) {
                Mesh mesh=new Mesh();mesh.name=(String)base.getField("char").get(part);mesh.material=(String)base.getField("native").get(part);
                ByteBuffer data=((ByteBuffer)call(get(part,"class"),"throws","java.nio.ByteBuffer")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer indices=((ByteBuffer)call(get(part,"native"),"throws","java.nio.ByteBuffer")).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int start=data.position(),indexStart=indices.position();
                int normal=(Integer)get(part,"catch"),uv=(Integer)get(part,"boolean");
                mesh.count=base.getField("synchronized").getInt(part);mesh.vertices=new float[mesh.count*8];
                boolean wide=(Boolean)call(part,"final","boolean");
                for(int i=0;i<mesh.count;i++) {
                    int index=wide?indices.getInt(indexStart+i*4):Short.toUnsignedInt(indices.getShort(indexStart+i*2));
                    int p=start+index*8,n=start+normal+index*4,t=start+uv+index*4,o=i*8;
                    // Stock model nose is -Z; vehicle coordinates use +Z.
                    mesh.vertices[o]=-data.getShort(p)*scale;mesh.vertices[o+1]=data.getShort(p+2)*scale;mesh.vertices[o+2]=-data.getShort(p+4)*scale;
                    mesh.vertices[o+3]=-data.get(n)/127f;mesh.vertices[o+4]=data.get(n+1)/127f;mesh.vertices[o+5]=-data.get(n+2)/127f;
                    mesh.vertices[o+6]=data.getShort(t)*uvScale;mesh.vertices[o+7]=data.getShort(t+2)*uvScale;
                }
                meshes.add(mesh);
            }
        } finally {
            Object data=get(reader,"catch");if(data==null&&!((List<?>)get(model,"instanceof")).isEmpty())data=get(get(((List<?>)get(model,"instanceof")).get(0),"class"),"implements");if(data!=null)call(Class.forName("802726662"),"byte","void",data);
        }
        Map<String,String> textures=bindings==null?type.textures:bindings;
        for(Mesh mesh:meshes.subList(first,meshes.size())){
            String path=textures.get(mesh.material);if(path==null)throw new IllegalStateException("Missing material "+mesh.material+" in "+asset);
            Integer handle=textureIds.get(path);if(handle==null){handle=texture(path);textureIds.put(path,handle);}mesh.texture=handle;
            for(int i=0;i<mesh.vertices.length;i+=8){
                if(offset==null){mesh.vertices[i]=(float)((mesh.vertices[i]-type.origin[0])*type.scale*type.direction);mesh.vertices[i+1]=(float)((mesh.vertices[i+1]-type.origin[1])*type.scale);mesh.vertices[i+2]=(float)((mesh.vertices[i+2]-type.origin[2])*type.scale*type.direction);}
                else{mesh.vertices[i]=(float)(mesh.vertices[i]*type.scale*type.direction+offset[0]);mesh.vertices[i+1]=(float)(mesh.vertices[i+1]*type.scale+offset[1]);mesh.vertices[i+2]=(float)(mesh.vertices[i+2]*type.scale*type.direction+offset[2]);}
                mesh.vertices[i+3]*=type.direction;mesh.vertices[i+5]*=type.direction;
            }
        }
    }
    void close(){
        for(Mesh m:meshes){if(m.vbo!=0)glDeleteBuffers(m.vbo);if(m.vao!=0)glDeleteVertexArrays(m.vao);if(m.trackTexture!=0)glDeleteTextures(m.trackTexture);}meshes.clear();
        for(Object t:textures)try{call(t,"default","void");}catch(Exception ignored){}textures.clear();textureIds.clear();
    }
}
