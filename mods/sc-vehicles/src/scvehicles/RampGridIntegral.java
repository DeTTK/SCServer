package scvehicles;

/** Exact integral of the native bilinear height grid, with constant edge extension. */
final class RampGridIntegral {
    private final double[][] grid,columns=new double[8][8],rows=new double[8][8],area=new double[8][8];
    RampGridIntegral(double[][] grid){
        this.grid=grid;
        for(int x=0;x<8;x++)for(int z=1;z<8;z++)columns[x][z]=columns[x][z-1]+(grid[x][z-1]+grid[x][z])*.5;
        for(int z=0;z<8;z++)for(int x=1;x<8;x++)rows[x][z]=rows[x-1][z]+(grid[x-1][z]+grid[x][z])*.5;
        for(int x=1;x<8;x++)for(int z=1;z<8;z++)area[x][z]=area[x-1][z]+area[x][z-1]-area[x-1][z-1]+(grid[x-1][z-1]+grid[x][z-1]+grid[x-1][z]+grid[x][z])*.25;
    }
    double average(double u,double v){
        double x=u*8-.5,z=v*8-.5,r=1.05*8;
        return (integral(x+r,z+r)-integral(x-r,z+r)-integral(x+r,z-r)+integral(x-r,z-r))/(4*r*r);
    }
    private double integral(double x,double z){
        double cx=Math.max(0,Math.min(7,x)),cz=Math.max(0,Math.min(7,z));
        int ix=Math.min(6,(int)cx),iz=Math.min(6,(int)cz);double a=cx-ix,b=cz-iz;
        double h=grid[ix][iz],dx=grid[ix+1][iz]-h,dz=grid[ix][iz+1]-h,dxdz=grid[ix+1][iz+1]-h-dx-dz;
        double result=area[ix][iz]+a*columns[ix][iz]+a*a*.5*(columns[ix+1][iz]-columns[ix][iz])
            +b*rows[ix][iz]+b*b*.5*(rows[ix][iz+1]-rows[ix][iz])
            +a*b*(h+a*.5*dx+b*.5*dz+a*b*.25*dxdz);
        double extraX=x-cx,extraZ=z-cz;
        if(extraX!=0){int edge=cx==0?0:7;result+=extraX*(columns[edge][iz]+b*grid[edge][iz]+b*b*.5*(grid[edge][iz+1]-grid[edge][iz]));}
        if(extraZ!=0){int edge=cz==0?0:7;result+=extraZ*(rows[ix][edge]+a*grid[ix][edge]+a*a*.5*(grid[ix+1][edge]-grid[ix][edge]));}
        if(extraX!=0&&extraZ!=0)result+=extraX*extraZ*grid[cx==0?0:7][cz==0?0:7];
        return result;
    }
}
