package scvehicles;

import java.util.*;

/** Articulates the installed asset without modifying or shipping a copy of it. */
final class VehicleGeometry {
    enum Part { BODY, FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, DOOR_LEFT, DOOR_RIGHT, STEERING, DOOR_REAR_LEFT, DOOR_REAR_RIGHT }
    static Part door(int model,int seat){return switch(scvehicles.protocol.VehicleSeats.PANEL[model][seat]){case 0->Part.DOOR_LEFT;case 1->Part.DOOR_RIGHT;case 2->Part.DOOR_REAR_LEFT;default->Part.DOOR_REAR_RIGHT;};}
    record Piece(Part part, float[] vertices) {}
    static List<Piece> splitType(String material,float[] vertices,VehicleFleet.Type type){
        if(type.door==null&&type.steering==null)return List.of(new Piece(Part.BODY,vertices));
        var groups=new EnumMap<Part,List<float[]>>(Part.class);
        for(float[] component:components(vertices)){
            float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(int i=0;i<component.length;i+=8)for(int axis=0;axis<3;axis++){lo[axis]=Math.min(lo[axis],component[i+axis]);hi[axis]=Math.max(hi[axis],component[i+axis]);}
            if(type.steering!=null&&component.length/24>=100&&component.length/24<=180){
                double distance=0;for(int axis=0;axis<3;axis++)distance+=Math.pow((lo[axis]+hi[axis])*.5-type.steering[axis],2);
                if(distance<.035){add(groups,Part.STEERING,component);continue;}
            }
            // Interior seat/backrest islands stay intact. Door outlines are measured
            // from each source asset, independent of seat position and vehicle size.
            boolean glass=material.toLowerCase(Locale.ROOT).contains("glass");
            boolean skin=type.door!=null&&(component.length/24>=200||Math.min(Math.abs(lo[0]),Math.abs(hi[0]))>=type.door.x);
            if(type.door==null||!glass&&!skin){add(groups,Part.BODY,component);continue;}
            List<float[]> remaining=new ArrayList<>();for(int i=0;i<component.length;i+=24)remaining.add(Arrays.copyOfRange(component,i,i+24));
            for(VehicleFleet.Door panel:new VehicleFleet.Door[]{type.door,type.rearDoor}){if(panel==null)continue;for(int side:new int[]{1,-1}){
                if(side>0&&!panel.left||side<0&&!panel.right)continue;
                double sideX=panel.sideX==null?panel.x:panel.sideX[side>0?0:1];
                List<float[]> planes=new ArrayList<>();planes.add(new float[]{side,0,0,(float)-sideX});
                double[][] outline=panel.outline;
                for(int i=0;i<outline.length;i++){
                    double[] a=outline[i],b=outline[(i+1)%outline.length];double dz=b[0]-a[0],dy=b[1]-a[1];
                    planes.add(new float[]{0,(float)dz,(float)-dy,(float)(dy*a[0]-dz*a[1])});
                }
                List<float[]> rest=new ArrayList<>(),door=new ArrayList<>();
                for(float[] triangle:remaining){
                    List<float[]> inside=new ArrayList<>();for(int k=0;k<3;k++)inside.add(Arrays.copyOfRange(triangle,k*8,k*8+8));
                    for(float[] plane:planes){triangulate(clip(inside,plane,false),rest);inside=clip(inside,plane,true);if(inside.isEmpty())break;}
                    triangulate(inside,door);
                }
                Part part=panel==type.rearDoor?(side>0?Part.DOOR_REAR_LEFT:Part.DOOR_REAR_RIGHT):(side>0?Part.DOOR_LEFT:Part.DOOR_RIGHT);for(float[] t:door)add(groups,part,t);remaining=rest;
            }}
            for(float[] t:remaining)add(groups,Part.BODY,t);
        }
        List<Piece> out=new ArrayList<>();groups.forEach((part,arrays)->out.add(new Piece(part,join(arrays))));return out;
    }
    static final float RADIUS=(float)(.3982042*VehicleFleet.nivaUnits());
    static List<Piece> splitNative(String name,String material,float[] vertices,boolean thickness){
        double f=VehicleFleet.nivaUnits();float[] measured=vertices.clone();
        for(int i=0;i<measured.length;i+=8)for(int a=0;a<3;a++)measured[i+a]/=f;
        List<Piece> out=split(name,material,measured,thickness);
        for(Piece p:out)for(int i=0;i<p.vertices.length;i+=8)for(int a=0;a<3;a++)p.vertices[i+a]*=f;
        return out;
    }
    static float[] pivot(Part part) {
        float[] pivot=switch(part) {
            case FRONT_LEFT -> new float[]{.669f,.3982042f,1.1777f};
            case FRONT_RIGHT -> new float[]{-.669f,.3982042f,1.1777f};
            case REAR_LEFT -> new float[]{.669f,.3982042f,-1.0685f};
            case REAR_RIGHT -> new float[]{-.669f,.3982042f,-1.0685f};
            case DOOR_LEFT -> new float[]{.754f,1.10f,.535f};
            case DOOR_RIGHT -> new float[]{-.754f,1.10f,.535f};
            case STEERING -> new float[]{.383f,1.046f,.295f};
            default -> new float[3];
        };
        double f=VehicleFleet.nivaUnits();for(int i=0;i<3;i++)pivot[i]*=f;return pivot;
    }
    static List<Piece> split(String name,String material,float[] vertices) {
        return split(name,material,vertices,true);
    }
    static List<Piece> split(String name,String material,float[] vertices,boolean thickness) {
        var groups=new EnumMap<Part,List<float[]>>(Part.class);
        if(name.toLowerCase(Locale.ROOT).contains("wheel")) {
            for(int i=0;i<vertices.length;i+=24) {
                float x=0,z=0;for(int k=0;k<3;k++){x+=vertices[i+k*8];z+=vertices[i+k*8+2];}
                Part part=z>0?(x>0?Part.FRONT_LEFT:Part.FRONT_RIGHT):(x>0?Part.REAR_LEFT:Part.REAR_RIGHT);
                add(groups,part,Arrays.copyOfRange(vertices,i,i+24));
            }
        } else for(float[] component:components(vertices)) {
            float[] lo={9,9,9},hi={-9,-9,-9};
            for(int i=0;i<component.length;i+=8)for(int a=0;a<3;a++){lo[a]=Math.min(lo[a],component[i+a]);hi[a]=Math.max(hi[a],component[i+a]);}
            boolean glass=material.toLowerCase(Locale.ROOT).contains("glass");
            if(glass) {
                // Whole side-door panes have their own islands in the source.
                Part part=lo[2]>-.5f&&hi[2]<.45f&&lo[1]>1.1f&&Math.min(Math.abs(lo[0]),Math.abs(hi[0]))>.65f
                    ? (lo[0]>0?Part.DOOR_LEFT:Part.DOOR_RIGHT):Part.BODY;
                add(groups,part,component);continue;
            }
            // The steering wheel is an independent island; seats and the
            // windshield surround must stay intact even beside the door cut.
            if(lo[0]>.20f&&hi[0]<.56f&&lo[1]>.87f&&hi[1]<1.23f&&lo[2]>.21f&&hi[2]<.38f&&component.length/24>80) {
                add(groups,Part.STEERING,component);continue;
            }
            boolean shell=component.length/24>=300;
            boolean handle=Math.min(Math.abs(lo[0]),Math.abs(hi[0]))>.77f;
            if(!shell&&!handle){add(groups,Part.BODY,component);continue;}
            List<float[]> remaining=new ArrayList<>();
            for(int i=0;i<component.length;i+=24)remaining.add(Arrays.copyOfRange(component,i,i+24));
            for(int side:new int[]{1,-1}) {
                List<float[]> rest=new ArrayList<>(),door=new ArrayList<>();
                // Convex door outline in Y/Z. Every crossing triangle is clipped
                // with interpolated normals/UVs; its exact complement stays on
                // the body. A centroid selection would tear the original mesh.
                float[][] planes={{side,0,0,-.63f},{0,1,0,-.435f},{0,-1,0,1.55f},
                    {0,0,1,.485f},{0,0,-1,.535f},{0,-.84f,-1,1.50f}};
                for(float[] triangle:remaining) {
                    List<float[]> inside=new ArrayList<>();for(int k=0;k<3;k++)inside.add(Arrays.copyOfRange(triangle,k*8,k*8+8));
                    for(float[] plane:planes) {
                        triangulate(clip(inside,plane,false),rest);
                        inside=clip(inside,plane,true);if(inside.isEmpty())break;
                    }
                    triangulate(inside,door);
                }
                Part part=side>0?Part.DOOR_LEFT:Part.DOOR_RIGHT;
                for(float[] t:door)add(groups,part,t);
                // Give the outside skin a narrow return and inner skin. Existing
                // native cabin trim is also retained on the moving door.
                if(thickness&&shell&&component.length/24>1000)thicken(door,side,groups,part);
                remaining=rest;
            }
            for(float[] t:remaining)add(groups,Part.BODY,t);
        }
        var result=new ArrayList<Piece>();
        groups.forEach((part,arrays)->result.add(new Piece(part,join(arrays))));return result;
    }
    private static float value(float[] v,float[] p){return v[0]*p[0]+v[1]*p[1]+v[2]*p[2]+p[3];}
    private static List<float[]> clip(List<float[]> polygon,float[] plane,boolean positive) {
        var out=new ArrayList<float[]>();if(polygon.isEmpty())return out;
        float[] a=polygon.get(polygon.size()-1);float da=value(a,plane)*(positive?1:-1);
        for(float[] b:polygon) {
            float db=value(b,plane)*(positive?1:-1);
            boolean ain=positive?da>=0:da>0,bin=positive?db>=0:db>0;
            if(ain!=bin){float t=da/(da-db);float[] v=new float[8];for(int k=0;k<8;k++)v[k]=a[k]+t*(b[k]-a[k]);out.add(v);}
            if(bin)out.add(b);a=b;da=db;
        }
        return out;
    }
    private static void triangulate(List<float[]> poly,List<float[]> out) {
        for(int i=1;i+1<poly.size();i++) {
            float[] t=new float[24];System.arraycopy(poly.get(0),0,t,0,8);System.arraycopy(poly.get(i),0,t,8,8);System.arraycopy(poly.get(i+1),0,t,16,8);
            double ax=t[8]-t[0],ay=t[9]-t[1],az=t[10]-t[2],bx=t[16]-t[0],by=t[17]-t[1],bz=t[18]-t[2];
            if(Math.pow(ay*bz-az*by,2)+Math.pow(az*bx-ax*bz,2)+Math.pow(ax*by-ay*bx,2)>1e-15)out.add(t);
        }
    }
    private static void thicken(List<float[]> triangles,int side,Map<Part,List<float[]>> groups,Part part) {
        record Edge(float[] a,float[] b) {}
        Map<String,Edge> boundary=new LinkedHashMap<>();
        for(float[] t:triangles) {
            float[] inner=t.clone();
            for(int k=0;k<3;k++){int o=k*8;inner[o]-=side*.028f;for(int a=3;a<6;a++)inner[o+a]=-inner[o+a];}
            for(int a=0;a<8;a++){float f=inner[a+8];inner[a+8]=inner[a+16];inner[a+16]=f;}
            add(groups,part,inner);
            for(int k=0;k<3;k++) {
                float[] a=Arrays.copyOfRange(t,k*8,k*8+8),b=Arrays.copyOfRange(t,((k+1)%3)*8,((k+1)%3)*8+8);
                String ka=key(a),kb=key(b),edge=ka.compareTo(kb)<0?ka+"/"+kb:kb+"/"+ka;
                if(boundary.remove(edge)==null)boundary.put(edge,new Edge(a,b));
            }
        }
        for(Edge e:boundary.values()) {
            float[] a=e.a(),b=e.b(),c=b.clone(),d=a.clone();c[0]-=side*.028f;d[0]-=side*.028f;
            float nx=0,ny=-(b[2]-a[2])*side,nz=(b[1]-a[1])*side,length=(float)Math.hypot(ny,nz);
            if(length<1e-7)continue;
            a=a.clone();b=b.clone();for(float[] v:new float[][]{a,b,c,d}){v[3]=nx;v[4]=ny/length;v[5]=nz/length;}
            add(groups,part,join(List.of(a,b,c)));add(groups,part,join(List.of(a,c,d)));
        }
    }
    private static void add(Map<Part,List<float[]>> out,Part part,float[] v){if(v.length>0)out.computeIfAbsent(part,k->new ArrayList<>()).add(v);}
    private static float[] join(List<float[]> arrays){int n=arrays.stream().mapToInt(a->a.length).sum(),i=0;float[] out=new float[n];for(float[] a:arrays){System.arraycopy(a,0,out,i,a.length);i+=a.length;}return out;}
    private static String key(float[] v){return Math.round(v[0]*100000)+","+Math.round(v[1]*100000)+","+Math.round(v[2]*100000);}
    static List<float[]> components(float[] vertices) {
        int n=vertices.length/24;int[] parent=new int[n];for(int i=0;i<n;i++)parent[i]=i;
        Map<String,Integer> seen=new HashMap<>();
        for(int i=0;i<n;i++)for(int k=0;k<3;k++) {
            String p=key(Arrays.copyOfRange(vertices,i*24+k*8,i*24+k*8+3));Integer other=seen.putIfAbsent(p,i);
            if(other!=null)parent[root(parent,i)]=root(parent,other);
        }
        Map<Integer,List<float[]>> groups=new LinkedHashMap<>();
        for(int i=0;i<n;i++)groups.computeIfAbsent(root(parent,i),k->new ArrayList<>()).add(Arrays.copyOfRange(vertices,i*24,i*24+24));
        return groups.values().stream().map(VehicleGeometry::join).toList();
    }
    private static int root(int[] p,int i){while(p[i]!=i){p[i]=p[p[i]];i=p[i];}return i;}
}
