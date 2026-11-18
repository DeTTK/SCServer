package replay;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;

/** Portable, complete terrain draw output. No GPU handles or builder history. */
final class SceneTerrainCodec {
    static final int MAGIC=0x53544d48,VERSION=1,MAX_BYTES=31*1024*1024;
    record Coordinate(int x,int y,int z,int size) {
        Coordinate {if(size<1||size>256||(size&(size-1))!=0||Math.floorMod(x,size)!=0||Math.floorMod(y,size)!=0||Math.floorMod(z,size)!=0)throw new IllegalArgumentException("Invalid terrain coordinate");}
        String key(){return x+","+y+","+z+","+size;}
        static Coordinate parse(String key)throws IOException {try{String[] p=key.split(",",-1);if(p.length!=4)throw new IllegalArgumentException();Coordinate c=new Coordinate(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));if(!key.equals(c.key()))throw new IllegalArgumentException();return c;}catch(IllegalArgumentException failure){throw new IOException("Invalid terrain mesh key",failure);}}
    }
    record Pass(String category,int usage,byte[] vertices,String hash) {
        Pass(String category,int usage,byte[] vertices){this(category,usage,vertices,null);}
        Pass {Objects.requireNonNull(category);Objects.requireNonNull(vertices);if(!category.matches("[A-Z_0-9]{1,80}")||usage<35040||usage>35050||vertices.length<1||vertices.length>MAX_BYTES)throw new IllegalArgumentException("Invalid terrain pass");vertices=vertices.clone();try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(vertices));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
        public byte[] vertices(){return vertices.clone();}
        int vertexByteSize(){return vertices.length;}
    }
    record Mesh(Coordinate coordinate,String layout,int stride,int generation,double[] bounds,byte[] visibility,List<Pass> passes) {
        Mesh {
            Objects.requireNonNull(coordinate);if(!layout.matches("[0-9a-f]{64}")||stride<1||stride>4096||bounds.length!=6||visibility.length>65536||passes.size()!=3)throw new IllegalArgumentException("Invalid terrain mesh layout");
            bounds=bounds.clone();visibility=visibility.clone();for(double value:bounds)if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid terrain bounds");for(int i=0;i<3;i++)if(bounds[i]>bounds[i+3])throw new IllegalArgumentException("Reversed terrain bounds");
            long total=visibility.length;for(Pass p:passes)if(p!=null){if(p.vertices.length%stride!=0||(total+=p.vertices.length)>MAX_BYTES)throw new IllegalArgumentException("Invalid terrain vertex count");}passes=Collections.unmodifiableList(new ArrayList<>(passes));
        }
        public double[] bounds(){return bounds.clone();}public byte[] visibility(){return visibility.clone();}
    }
    /** No native draw ever visits an empty cell; keep its exact archive metadata. */
    record EmptyCell(Mesh mesh){}
    static SceneTracks.Bytes encode(Mesh mesh)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(mesh.coordinate.key());out.writeUTF(mesh.layout);out.writeInt(mesh.stride);out.writeInt(mesh.generation);for(double b:mesh.bounds)out.writeDouble(b);out.writeInt(mesh.visibility.length);out.write(mesh.visibility);
            for(Pass p:mesh.passes){out.writeBoolean(p!=null);if(p!=null){out.writeUTF(p.category);out.writeInt(p.usage);out.writeInt(p.vertices.length);out.write(p.vertices);}}
        }return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static Mesh decode(SceneTracks.Bytes value)throws IOException {
        if(value.size()>32*1024*1024)throw new IOException("Terrain mesh exceeds archive limit");
        try(var in=new DataInputStream(new ByteArrayInputStream(value.data()))){if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported terrain mesh");Coordinate c=Coordinate.parse(in.readUTF());String layout=in.readUTF();int stride=in.readInt(),generation=in.readInt();double[] bounds=new double[6];for(int i=0;i<6;i++)bounds[i]=in.readDouble();int n=in.readInt();if(n<0||n>65536||n>in.available())throw new IOException("Invalid terrain visibility length");byte[] visibility=in.readNBytes(n);List<Pass> passes=new ArrayList<>();for(int i=0;i<3;i++){if(!in.readBoolean()){passes.add(null);continue;}String category=in.readUTF();int usage=in.readInt(),length=in.readInt();if(length<1||length>MAX_BYTES||length>in.available())throw new IOException("Invalid terrain buffer length");passes.add(new Pass(category,usage,in.readNBytes(length)));}if(in.available()!=0)throw new IOException("Trailing terrain mesh data");return new Mesh(c,layout,stride,generation,bounds,visibility,passes);
        }catch(IllegalArgumentException failure){throw new IOException("Invalid terrain mesh",failure);}
    }
    static String nativeLayout()throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");for(String name:List.of("32347481","1312565770","1488007653","860547231","1754218721")){try(var in=Native.type(name).getResourceAsStream("/"+name+".class")){if(in==null)throw new IOException("Missing terrain layout class "+name);digest.update(in.readAllBytes());}}return HexFormat.of().formatHex(digest.digest());
    }
    static Coordinate coordinate(Object cell)throws Exception {return new Coordinate(((Number)Native.call(cell,"new","int")).intValue(),((Number)Native.call(cell,"return","int")).intValue(),((Number)Native.call(cell,"default","int")).intValue(),((Number)Native.field(cell,"double")).intValue());}
    static SceneTracks.Bytes capture(Object cell,String layout)throws Exception {
        if(cell instanceof EmptyCell empty){if(!empty.mesh.layout.equals(layout))throw new IOException("Empty terrain layout changed");return encode(empty.mesh);}
        Object mesh=Native.field(cell,"boolean");if(mesh==Native.field(Native.type("1488007653"),"protected"))return null;
        if((boolean)Native.field(mesh,"extends"))throw new IOException("Disposed terrain mesh selected for capture");
        int stride=((Number)Native.field(Native.type("32347481"),"import")).intValue();double[] bounds=new double[6];Object box=Native.field(mesh,"throw");String[] fields={"return","void","long","class","final","default"};for(int i=0;i<6;i++)bounds[i]=((Number)Native.field(box,fields[i])).doubleValue();
        List<Pass> passes=new ArrayList<>();Class<?> gl=Native.type("org.lwjgl.opengl.GL15");int original=((Number)Native.call(gl,"glGetInteger","int",34964)).intValue();
        try{for(Object pass:(Object[])Native.field(mesh,"throws")){Object buffer=Native.field(pass,"double");if(buffer==null){passes.add(null);continue;}if(((Number)Native.field(buffer,"finally")).intValue()!=34962||(boolean)Native.field(buffer,"const")||(boolean)Native.field(buffer,"implements"))throw new IOException("Unsupported or unavailable terrain vertex buffer");int size=((Number)Native.field(buffer,"goto")).intValue();if(size<1||size>MAX_BYTES)throw new IOException("Invalid native terrain buffer size");ByteBuffer data=ByteBuffer.allocateDirect(size);Native.call(gl,"glBindBuffer","void",34962,((Number)Native.field(buffer,"byte")).intValue());Native.call(gl,"glGetBufferSubData","void",34962,0L,data);byte[] bytes=new byte[size];data.get(bytes);passes.add(new Pass(((Enum<?>)Native.field(buffer,"switch")).name(),((Number)Native.field(buffer,"protected")).intValue(),bytes));}}
        finally{Native.call(gl,"glBindBuffer","void",34962,original);}
        return encode(new Mesh(coordinate(cell),layout,stride,((Number)Native.field(mesh,"strictfp")).intValue(),bounds,(byte[])Native.field(mesh,"goto"),passes));
    }
    /** The returned cell owns every GPU buffer; closing never touches captured native cells. */
    static Object create(Mesh mesh,String layout)throws Exception {
        return create(mesh,layout,null);
    }
    static Object createPass(Pass pass)throws Exception {
        Object category=Enum.valueOf((Class)Native.type("573168672"),pass.category);ByteBuffer data=ByteBuffer.allocateDirect(pass.vertices.length);data.put(pass.vertices).flip();
        Object buffer=Native.type("1754218721").getConstructor(Native.type("573168672"),int.class,int.class,ByteBuffer.class).newInstance(category,34962,pass.usage,data);boolean retained=false;
        try{Object result=Native.type("860547231").getConstructor(Native.type("1754218721")).newInstance(buffer);retained=true;return result;}finally{if(!retained)Native.call(buffer,"while","void");}
    }
    static Object create(Mesh mesh,String layout,SceneTerrainBuffers<Object> shared)throws Exception {
        int stride=((Number)Native.field(Native.type("32347481"),"import")).intValue();if(!mesh.layout.equals(layout)||mesh.stride!=stride)throw new IOException("Terrain vertex layout differs from prepared recording");
        if(shared!=null&&mesh.passes.stream().allMatch(Objects::isNull))return new EmptyCell(mesh);
        List<Object> buffers=new ArrayList<>();List<SceneTerrainBuffers<Object>.Lease> leases=new ArrayList<>();Class<?> gl=Native.type("org.lwjgl.opengl.GL15");int original=((Number)Native.call(gl,"glGetInteger","int",34964)).intValue();boolean complete=false;
        try{Object[] passes=new Object[3];for(int i=0;i<3;i++){Pass p=mesh.passes.get(i);if(p==null){passes[i]=Native.field(Native.type("860547231"),"interface");continue;}if(shared==null){passes[i]=createPass(p);buffers.add(Native.field(passes[i],"double"));}else{var lease=shared.acquire(mesh.layout,mesh.stride,p);leases.add(lease);passes[i]=lease.value();}}
            double[] b=mesh.bounds;Object bounds=Native.type("1994198637").getConstructor(double.class,double.class,double.class,double.class,double.class,double.class).newInstance(b[0],b[1],b[2],b[3],b[4],b[5]);Object value=Native.type("1488007653").getConstructor(Native.type("860547231"),Native.type("860547231"),Native.type("860547231"),byte[].class,int.class,Native.type("1994198637")).newInstance(passes[0],passes[1],passes[2],mesh.visibility(),mesh.generation,bounds);Object query=Native.type("581461543").getConstructor(int.class).newInstance(0);Coordinate c=mesh.coordinate;Object cell=Native.type("949691181").getConstructor(Native.type("581461543"),int.class,int.class,int.class,int.class).newInstance(query,c.size,c.x/c.size,c.y/c.size,c.z/c.size);SourceAdapters.put(cell,"boolean",value);SourceAdapters.put(cell,"static",true);SourceAdapters.put(cell,"super",true);SourceAdapters.put(cell,"extends",1f);SourceAdapters.put(cell,"short",1f);SourceAdapters.put(cell,"while",-Double.MAX_VALUE);if(shared!=null)shared.bind(cell,leases);complete=true;return cell;
        }finally{try{Native.call(gl,"glBindBuffer","void",34962,original);}finally{if(!complete){if(shared!=null)shared.release(leases);else for(Object buffer:buffers)Native.call(buffer,"while","void");}}}
    }
    static void dispose(Object cell)throws Exception {if(!(cell instanceof EmptyCell))Native.call(cell,"float","void");}
    static void dispose(Object cell,SceneTerrainBuffers<Object> shared)throws Exception {
        if(!shared.owns(cell)){dispose(cell);return;}
        // The native mesh destructor owns per-cell validation buffers. Detach
        // its shared immutable vertex passes before it deletes those handles.
        Object mesh=Native.field(cell,"boolean");Object[] passes=(Object[])Native.field(mesh,"throws");Object empty=Native.field(Native.type("860547231"),"interface");Arrays.fill(passes,empty);
        try{dispose(cell);}finally{shared.release(cell);}
    }
}
