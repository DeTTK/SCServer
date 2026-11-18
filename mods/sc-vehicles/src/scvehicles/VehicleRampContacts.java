package scvehicles;

import java.util.*;
import static scvehicles.NativeAccess.*;

/** Scoped voxel adjustment called only by the vehicle's private native-solver copy. */
public final class VehicleRampContacts {
    private static final ThreadLocal<VehicleRampContacts> ACTIVE=new ThreadLocal<>();
    private final RampSurface surface=new RampSurface();
    private final RampProfileDependencies dependencies=new RampProfileDependencies();
    private final Object owner,world;
    private double maxRaised,maxBuried;
    private record Bounds(double x,double y,double z,double X,double Y,double Z) {}
    private record Cell(Object box,double x,double z,double X,double Z) {}
    private static final List<Cell> UNCHANGED=List.of(new Cell(null,0,0,0,0));
    private final Map<Bounds,List<Cell>> adjusted=new LinkedHashMap<>(256,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<Bounds,List<Cell>> e){return size()>4096;}
    };
    private long fingerprint=Long.MIN_VALUE,cacheHits,cacheMisses,cacheInvalidated;
    private long frame,profiledFrame=-1,worldCollections,snapshotQueries;
    private Bounds profileRegion;
    private VehicleVoxelIndex snapshot;
    private boolean adjustedSweep;
    VehicleRampContacts(Object owner,Object world){this.owner=owner;this.world=world;}
    static boolean ramp(int id){return id==2401||id>=2414&&id<=2423;}
    static double[] bounds(Object b)throws Exception{return (double[])call(Class.forName("scvehicles.NativeRampBounds"),"read","[D",b);}
    void beginFrame(){frame++;snapshot=null;}
    private VehicleVoxelIndex obstacles(double[] query)throws Exception{
        if(snapshot!=null&&snapshot.covers(query))return snapshot;
        double[] region=query.clone();
        if(profileRegion!=null){double[] profile={profileRegion.x,profileRegion.y-1,profileRegion.z,profileRegion.X,profileRegion.Y+1,profileRegion.Z};boolean inside=true;for(int i=0;i<3;i++)inside&=query[i]>=profile[i]-1e-7&&query[i+3]<=profile[i+3]+1e-7;if(inside)region=profile;}
        VehicleVoxelIndex next=new VehicleVoxelIndex(region);Object pool=call(Class.forName("1326697707"),"long","1326697707");call(pool,"import","void");
        try{
            List<Object> raw=new ArrayList<>();call(world,"const","void",owner,box(region[0],region[1],region[2],region[3],region[4],region[5]),raw);worldCollections++;
            for(Object b:raw){double[] a=bounds(b);next.add(box(a[0],a[1],a[2],a[3],a[4],a[5]),a);}
        }finally{call(pool,"byte","void");}
        snapshot=next;return next;
    }
    void refresh(VehiclePhysics p)throws Exception{
        if(VehiclePose.rotate(p.rotation(),0,1,0)[1]<.45){surface.set(List.of());adjusted.clear();dependencies.clear();fingerprint=Long.MIN_VALUE;profiledFrame=-1;return;}
        double reach=Math.max(3.5,Math.max(p.type.length,p.type.width)*.5+1.5);
        Bounds requested=new Bounds(Math.floor(p.x-reach),Math.floor(p.y-2),Math.floor(p.z-reach),Math.ceil(p.x+reach),Math.ceil(p.y+Math.max(2.5,p.type.height)),Math.ceil(p.z+reach));
        if(profiledFrame==frame&&requested.equals(profileRegion))return;
        profileRegion=requested;profiledFrame=frame;
        double[] area={requested.x,requested.y,requested.z,requested.X,requested.Y,requested.Z};
        Map<String,List<double[]>> cells=new LinkedHashMap<>();Map<String,int[]> coords=new LinkedHashMap<>();
        Map<String,Boolean> rampBlocks=new HashMap<>();
        {
            for(VehicleVoxelIndex.Entry obstacle:obstacles(area).at(area)){double[] a=obstacle.bounds;int x=(int)Math.floor((a[0]+a[3])*.5),y=(int)Math.floor(a[1]+.001),z=(int)Math.floor((a[2]+a[5])*.5);
                String key=x+","+y+","+z;Boolean isRamp=rampBlocks.get(key);if(isRamp==null){isRamp=ramp((Integer)call(world,"strictfp","int",x,y,z));rampBlocks.put(key,isRamp);}if(!isRamp)continue;
                cells.computeIfAbsent(key,k->new ArrayList<>()).add(a);coords.put(key,new int[]{x,y,z});
            }
        }
        List<RampSurface.Patch> patches=new ArrayList<>();
        List<RampCornerSurface.Patch> corners=new ArrayList<>();
        for(var e:cells.entrySet()){
            int[] c=coords.get(e.getKey());double xl=height(e.getValue(),c[0]+.0625,c[2]+.5),xr=height(e.getValue(),c[0]+.9375,c[2]+.5),zl=height(e.getValue(),c[0]+.5,c[2]+.0625),zr=height(e.getValue(),c[0]+.5,c[2]+.9375);
            double gx=xr-xl,gz=zr-zl;
            if(Math.abs(gx)>.70&&Math.abs(gz)<.15)patches.add(new RampSurface.Patch(c[0],c[1],c[2],0,gx>0?1:-1));
            else if(Math.abs(gz)>.70&&Math.abs(gx)<.15)patches.add(new RampSurface.Patch(c[0],c[1],c[2],1,gz>0?1:-1));
            else {var corner=RampCornerSurface.fit(c[0],c[1],c[2],e.getValue());if(corner!=null)corners.add(corner);}
        }
        long next=patches.hashCode();for(var c:corners)next=next*31+Objects.hash(c.x(),c.y(),c.z(),c.kind(),c.sx(),c.sz(),Arrays.deepHashCode(c.grid()));
        if(next!=fingerprint){
            surface.set(patches);surface.corners(corners);
            var changes=dependencies.update(surface.dependencies());int before=adjusted.size();
            adjusted.keySet().removeIf(b->RampProfileDependencies.affected(changes,b.x,b.z,b.X,b.Z));
            cacheInvalidated+=before-adjusted.size();fingerprint=next;
        }
    }
    private static double height(List<double[]> cells,double x,double z){double top=Double.NaN;for(double[] a:cells)if(x>=a[0]-1e-6&&x<=a[3]+1e-6&&z>=a[2]-1e-6&&z<=a[5]+1e-6)top=Double.isNaN(top)?a[4]:Math.max(top,a[4]);return top;}
    private static Object box(double x,double y,double z,double X,double Y,double Z)throws Exception{return call(Class.forName("242639986"),"boolean","242639986",x,y,z,X,Y,Z);}
    int sweep(Object b,double dx,double dy,double dz,float step)throws Exception{
        return sweep(b,dx,dy,dz,step,true);
    }
    int rawSweep(Object b,double dx,double dy,double dz)throws Exception{return sweep(b,dx,dy,dz,0f,false);}
    private int sweep(Object b,double dx,double dy,double dz,float step,boolean adjusted)throws Exception{
        if(ACTIVE.get()!=null)throw new IllegalStateException("recursive vehicle ramp sweep");
        adjustedSweep=adjusted&&!surface.empty();ACTIVE.set(this);
        try{return (Integer)call(Class.forName("SCVehicleTerrainSolver"),"else","int",owner,b,dx,dy,dz,step,false);}
        finally{ACTIVE.remove();}
    }
    boolean near(double x,double top,double z){return surface.near(x,z,top);}
    boolean supports(double x,double top,double z){return surface.supports(x,z,top);}
    RampSurface.Contact contact(double x,double bottom,double z){return surface.contact(x,z,bottom);}
    boolean corners(){return surface.cornerCount()>0;}
    public static void collect(Object world,Object owner,Object area,List<Object> target)throws Exception{
        VehicleRampContacts active=ACTIVE.get();
        boolean scoped=active!=null&&active.world==world&&active.owner==owner;
        Object collected=scoped&&active.adjustedSweep?call(area,"void","242639986",0.,RampSurface.LIMIT+.01,0.):area;
        int start=target.size();
        if(scoped){double[] query=bounds(collected);for(VehicleVoxelIndex.Entry entry:active.obstacles(query).at(query))target.add(entry.box);active.snapshotQueries++;}
        else call(world,"const","void",owner,collected,target);
        if(!scoped||!active.adjustedSweep)return;
        active.adjust(target,start,area);
    }
    void adjust(List<Object> target,int start,Object area)throws Exception{
        List<Object> originals=new ArrayList<>(target.subList(start,target.size()));target.subList(start,target.size()).clear();
        double[] query=bounds(area);
        for(Object original:originals){
            double[] a=bounds(original);double minX=Math.max(a[0],query[0]-.01),maxX=Math.min(a[3],query[3]+.01),minZ=Math.max(a[2],query[2]-.01),maxZ=Math.min(a[5],query[5]+.01);
            if(minX>=maxX||minZ>=maxZ){target.add(original);continue;}
            // Native voxel obstacle bounds are immutable inputs to the solver.
            // Cache our equally immutable columns across wheel/hull sweeps;
            // invalidate only within changed profile influence regions.
            if(a[3]-a[0]<=1.001&&a[5]-a[2]<=1.001){
                Bounds key=new Bounds(a[0],a[1],a[2],a[3],a[4],a[5]);List<Cell> cells=adjusted.get(key);
                if(cells==null){cacheMisses++;cells=columns(a);adjusted.put(key,cells);}else cacheHits++;
                if(cells==UNCHANGED){target.add(original);continue;}
                for(Cell c:cells)if(c.X>minX&&c.x<maxX&&c.Z>minZ&&c.z<maxZ)target.add(c.box);
                continue;
            }
            boolean affected=false;
            for(double x:new double[]{minX,(minX+maxX)*.5,maxX})for(double z:new double[]{minZ,(minZ+maxZ)*.5,maxZ})affected|=near(x,a[4],z);
            if(!affected){target.add(original);continue;}
            int nx=Math.max(1,(int)Math.ceil((maxX-minX)/.125)),nz=Math.max(1,(int)Math.ceil((maxZ-minZ)/.125));
            for(int ix=0;ix<nx;ix++)for(int iz=0;iz<nz;iz++){
                double x=minX+(maxX-minX)*ix/nx,X=minX+(maxX-minX)*(ix+1)/nx,z=minZ+(maxZ-minZ)*iz/nz,Z=minZ+(maxZ-minZ)*(iz+1)/nz;
                // Conservative upper sample avoids introducing cracks between adjacent columns.
                double delta=-Double.MAX_VALUE;
                for(double u:new double[]{x+1e-7,X-1e-7})for(double v:new double[]{z+1e-7,Z-1e-7})delta=Math.max(delta,surface.shift(u,v,a[4]));
                maxRaised=Math.max(maxRaised,delta);maxBuried=Math.max(maxBuried,-delta);
                if(a[4]+delta>a[1]+1e-5)target.add(box(x,a[1],z,X,a[4]+delta,Z));
            }
        }
    }
    private List<Cell> columns(double[] a)throws Exception{
        boolean affected=false;for(double x:new double[]{a[0],(a[0]+a[3])*.5,a[3]})for(double z:new double[]{a[2],(a[2]+a[5])*.5,a[5]})affected|=near(x,a[4],z);
        if(!affected)return UNCHANGED;
        List<Cell> result=new ArrayList<>();int nx=Math.max(1,(int)Math.ceil((a[3]-a[0])/.125)),nz=Math.max(1,(int)Math.ceil((a[5]-a[2])/.125));
        for(int ix=0;ix<nx;ix++)for(int iz=0;iz<nz;iz++){
            double x=a[0]+(a[3]-a[0])*ix/nx,X=a[0]+(a[3]-a[0])*(ix+1)/nx,z=a[2]+(a[5]-a[2])*iz/nz,Z=a[2]+(a[5]-a[2])*(iz+1)/nz,delta=-Double.MAX_VALUE;
            for(double u:new double[]{x+1e-7,X-1e-7})for(double v:new double[]{z+1e-7,Z-1e-7})delta=Math.max(delta,surface.shift(u,v,a[4]));
            maxRaised=Math.max(maxRaised,delta);maxBuried=Math.max(maxBuried,-delta);
            if(a[4]+delta>a[1]+1e-5)result.add(new Cell(box(x,a[1],z,X,a[4]+delta,Z),x,z,X,Z));
        }
        return List.copyOf(result);
    }
    Map<String,Object> inspect(){var out=new LinkedHashMap<String,Object>();out.put("patches",surface.size());out.put("corners",surface.cornerCount());out.put("raised",maxRaised);out.put("buried",maxBuried);out.put("limit",RampSurface.LIMIT);out.put("cacheHits",cacheHits);out.put("cacheMisses",cacheMisses);out.put("cachedShapes",adjusted.size());out.put("worldCollections",worldCollections);out.put("snapshotQueries",snapshotQueries);out.put("shapeTemplates",RampCornerSurface.templateStats());return out;}
}
