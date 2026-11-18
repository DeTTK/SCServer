/** One pixel-quantized layout shared by both weapon slots and both states. */
final class HudCardGrid {
 record Box(int x,int y,int w,int h) {int right(){return x+w;}int bottom(){return y+h;}}
 final int width,fullHeight,compactHeight,line,padding,header;final double scale;
 final Box key,title,ammo,reserve,reload,ammoType,mode,image;
 HudCardGrid(int width){
  this.width=width;scale=width/194d;fullHeight=p(66);compactHeight=p(20);line=Math.max(1,p(1));padding=p(6);header=p(18);
  key=b(6,3.5,11,11);title=b(22,3,136,12);ammo=b(6,23,58,25);ammoType=b(65,23,23,25);reserve=b(6,51,35,11);reload=b(46,51,42,11);mode=b(163,3.5,25,11);image=b(90,25,98,35);
  for(Box box:new Box[]{key,title,ammo,reserve,reload,ammoType,mode,image})if(box.x()<padding||box.y()<0||box.right()>width-padding+1||box.bottom()>fullHeight)throw new IllegalStateException("Weapon cell outside shared grid");
  if(key.x()!=ammo.x()||ammo.x()!=reserve.x()||title.right()>mode.x()||ammo.right()>image.x())throw new IllegalStateException("Weapon grid alignment failure");
 }
 int p(double units){return (int)Math.round(units*scale);}
 Box b(double x,double y,double w,double h){int left=p(x),top=p(y);return new Box(left,top,p(x+w)-left,p(y+h)-top);}
}
