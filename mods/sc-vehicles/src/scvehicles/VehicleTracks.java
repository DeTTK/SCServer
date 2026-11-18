package scvehicles;

import java.util.*;

/** Source geometry travels around its own measured loop; atlas UVs stay intact. */
final class VehicleTracks {
    static final int SAMPLES=256;
    record Piece(float[] vertices,int side,Loop loop,float[] pivot,double radius) {}
    record Point(double y,double z) {}
    static final class Loop {
        final float[] texture=new float[SAMPLES*4];
        final double length;
        Loop(float[] vertices){
            TreeSet<Point> points=new TreeSet<>(Comparator.comparingDouble(Point::y).thenComparingDouble(Point::z));
            for(int i=0;i<vertices.length;i+=8)points.add(new Point(vertices[i+1],vertices[i+2]));
            List<Point> sorted=new ArrayList<>(points),hull=new ArrayList<>();
            for(Point p:sorted){while(hull.size()>1&&cross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}
            int lower=hull.size();for(int i=sorted.size()-2;i>=0;i--){Point p=sorted.get(i);while(hull.size()>lower&&cross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}
            hull.remove(hull.size()-1);if(hull.size()<3)throw new IllegalArgumentException("degenerate track loop");
            // Round only the measured polygon corners. No oval assumption: the
            // raised idler, inclined return run and original track footprint remain.
            for(int pass=0;pass<2;pass++){
                List<Point> smooth=new ArrayList<>();for(int i=0;i<hull.size();i++){
                    Point a=hull.get(i),b=hull.get((i+1)%hull.size());
                    smooth.add(new Point(a.y*.75+b.y*.25,a.z*.75+b.z*.25));smooth.add(new Point(a.y*.25+b.y*.75,a.z*.25+b.z*.75));
                }hull=smooth;
            }
            double[] distance=new double[hull.size()+1];for(int i=0;i<hull.size();i++)distance[i+1]=distance[i]+dist(hull.get(i),hull.get((i+1)%hull.size()));
            length=distance[hull.size()];int segment=0;
            for(int i=0;i<SAMPLES;i++){
                double at=i*length/SAMPLES;while(segment+1<hull.size()&&distance[segment+1]<at)segment++;
                Point a=hull.get(segment),b=hull.get((segment+1)%hull.size());double t=(at-distance[segment])/(distance[segment+1]-distance[segment]);
                texture[i*4]=(float)(a.y+(b.y-a.y)*t);texture[i*4+1]=(float)(a.z+(b.z-a.z)*t);
            }
            for(int i=0;i<SAMPLES;i++){
                int prev=Math.floorMod(i-1,SAMPLES)*4,next=(i+1)%SAMPLES*4;double dy=texture[next]-texture[prev],dz=texture[next+1]-texture[prev+1],n=Math.hypot(dy,dz);
                texture[i*4+2]=(float)(dy/n);texture[i*4+3]=(float)(dz/n);
            }
        }
        double[] at(double metres){
            double index=(metres/length-Math.floor(metres/length))*SAMPLES;int i=(int)index,j=(i+1)%SAMPLES;double t=index-i;
            double y=texture[i*4]*(1-t)+texture[j*4]*t,z=texture[i*4+1]*(1-t)+texture[j*4+1]*t;
            double ty=texture[i*4+2]*(1-t)+texture[j*4+2]*t,tz=texture[i*4+3]*(1-t)+texture[j*4+3]*t,n=Math.hypot(ty,tz);
            return new double[]{y,z,ty/n,tz/n};
        }
        double project(double y,double z){
            double best=Double.POSITIVE_INFINITY,arc=0;
            for(int i=0;i<SAMPLES;i++){
                int j=(i+1)%SAMPLES;double ay=texture[i*4],az=texture[i*4+1],dy=texture[j*4]-ay,dz=texture[j*4+1]-az;
                double t=VehiclePhysics.clamp(((y-ay)*dy+(z-az)*dz)/(dy*dy+dz*dz),0,1),d=Math.pow(y-ay-t*dy,2)+Math.pow(z-az-t*dz,2);
                if(d<best){best=d;arc=(i+t)*length/SAMPLES;}
            }return arc;
        }
        float[] attributes(float[] vertices,boolean rigidLinks){
            float[] out=new float[vertices.length/8*4];
            List<float[]> components=rigidLinks?components(vertices):List.of(vertices);
            // Components may be reordered; the packed buffer below keeps vertex
            // and arc attributes together, so no index/atlas association is lost.
            int offset=0;
            for(float[] component:components){
                float[] bounds=bounds(component);double cy=(bounds[1]+bounds[4])*.5,cz=(bounds[2]+bounds[5])*.5,shared=project(cy,cz);
                for(int i=0;i<component.length;i+=8){
                    double arc=rigidLinks?shared:project(component[i+1],component[i+2]);double[] base=at(arc);
                    out[offset++]=(float)arc;out[offset++]=(float)base[0];out[offset++]=(float)base[1];out[offset++]=(float)Math.atan2(base[3],base[2]);
                }
            }return out;
        }
        float[] interleaved(float[] vertices,boolean rigidLinks){
            float[] ordered=rigidLinks?join(components(vertices)):vertices;
            float[] attributes=attributes(ordered,rigidLinks),out=new float[ordered.length/8*12];
            for(int i=0;i<ordered.length/8;i++){System.arraycopy(ordered,i*8,out,i*12,8);System.arraycopy(attributes,i*4,out,i*12+8,4);}return out;
        }
        double[] move(double y,double z,double arc,double metres){
            double[] a=at(arc),b=at(arc+metres);double c=a[2]*b[2]+a[3]*b[3],s=a[2]*b[3]-a[3]*b[2],dy=y-a[0],dz=z-a[1];
            return new double[]{b[0]+c*dy-s*dz,b[1]+s*dy+c*dz};
        }
    }
    static List<Piece> split(String material,float[] vertices,VehicleFleet.Type type){
        List<float[]> components=components(vertices),fixed=new ArrayList<>();
        Map<Integer,List<float[]>> belts=new TreeMap<>();List<Piece> wheels=new ArrayList<>();
        for(float[] component:components){
            float[] b=bounds(component);double x=(b[0]+b[3])*.5,y=(b[1]+b[4])*.5,dx=b[3]-b[0],dy=b[4]-b[1],dz=b[5]-b[2];int side=x>0?1:-1;
            boolean belt=material.equals("track_mat")||type.id==16&&material.equals("dt75")&&component.length/24==172||
                type.id==17&&component.length/24==2584||type.id==18&&material.equals("tank_wheels")&&component.length/24==472||
                type.id==23&&component.length/24==1356;
            if(belt){belts.computeIfAbsent(side,k->new ArrayList<>()).add(component);continue;}
            boolean wheelMaterial=type.id==16||type.id==17||type.id==23||material.equals("tank_wheels")||material.equals("tank_chassis_01");
            if(wheelMaterial&&Math.abs(x)>type.width*.2&&y<1.6&&dy>.28&&dz>.28&&dy/dz>.90&&dy/dz<1.10&&dx<dy*.85&&component.length/24>=60){
                float[] pivot={(b[0]+b[3])*.5f,(b[1]+b[4])*.5f,(b[2]+b[5])*.5f};wheels.add(new Piece(component,side,null,pivot,(dy+dz)*.25));
            }else fixed.add(component);
        }
        List<List<float[]>> wheelGroups=new ArrayList<>();List<Piece> axles=new ArrayList<>();
        for(Piece wheel:wheels){
            int found=-1;for(int i=0;i<axles.size();i++){Piece axle=axles.get(i);if(wheel.side==axle.side&&Math.hypot(wheel.pivot[1]-axle.pivot[1],wheel.pivot[2]-axle.pivot[2])<.04){found=i;break;}}
            if(found<0){found=axles.size();axles.add(wheel);wheelGroups.add(new ArrayList<>());}wheelGroups.get(found).add(wheel.vertices);
        }
        for(var it=fixed.iterator();it.hasNext();){
            float[] component=it.next();if(component.length/24>60)continue;float[] b=bounds(component);
            for(int i=0;i<axles.size();i++){
                Piece axle=axles.get(i);double x=(b[0]+b[3])*.5,y=(b[1]+b[4])*.5,z=(b[2]+b[5])*.5;
                double extent=Math.hypot((b[4]-b[1])*.5,(b[5]-b[2])*.5);
                if(Math.signum(x)==axle.side&&Math.abs(x-axle.pivot[0])<axle.radius*.9&&Math.hypot(y-axle.pivot[1],z-axle.pivot[2])+extent<axle.radius*.98){wheelGroups.get(i).add(component);it.remove();break;}
            }
        }
        List<Piece> result=new ArrayList<>();if(!fixed.isEmpty())result.add(new Piece(join(fixed),0,null,new float[3],0));
        for(int i=0;i<axles.size();i++){Piece axle=axles.get(i);result.add(new Piece(join(wheelGroups.get(i)),axle.side,null,axle.pivot,axle.radius));}
        for(var e:belts.entrySet()){float[] v=join(e.getValue());result.add(new Piece(v,e.getKey(),new Loop(v),new float[3],0));}
        return result;
    }
    static float[] bounds(float[] v){float[] b={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};for(int i=0;i<v.length;i+=8)for(int a=0;a<3;a++){b[a]=Math.min(b[a],v[i+a]);b[a+3]=Math.max(b[a+3],v[i+a]);}return b;}
    private record Vertex(int x,int y,int z){}
    private static List<float[]> components(float[] vertices){
        int count=vertices.length/24;int[] parents=new int[count];for(int i=0;i<count;i++)parents[i]=i;
        Map<Vertex,Integer> seen=new HashMap<>();
        for(int i=0;i<vertices.length;i+=8){Vertex p=new Vertex(Math.round(vertices[i]*10000),Math.round(vertices[i+1]*10000),Math.round(vertices[i+2]*10000));int tri=i/24;Integer other=seen.putIfAbsent(p,tri);if(other!=null)parents[root(parents,tri)]=root(parents,other);}
        Map<Integer,List<Integer>> groups=new LinkedHashMap<>();for(int i=0;i<count;i++)groups.computeIfAbsent(root(parents,i),k->new ArrayList<>()).add(i);
        List<float[]> out=new ArrayList<>();for(List<Integer> indices:groups.values()){float[] v=new float[indices.size()*24];for(int i=0;i<indices.size();i++)System.arraycopy(vertices,indices.get(i)*24,v,i*24,24);out.add(v);}return out;
    }
    private static int root(int[] parents,int at){while(parents[at]!=at){parents[at]=parents[parents[at]];at=parents[at];}return at;}
    private static float[] join(List<float[]> list){int length=list.stream().mapToInt(v->v.length).sum(),at=0;float[] out=new float[length];for(float[] v:list){System.arraycopy(v,0,out,at,v.length);at+=v.length;}return out;}
    private static double cross(Point a,Point b,Point c){return(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);}
    private static double dist(Point a,Point b){return Math.hypot(a.y-b.y,a.z-b.z);}
}
