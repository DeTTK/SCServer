import math, json, struct
from pathlib import Path
STRETCH=set(json.loads(Path(__file__).with_name("stretch-ids.json").read_bytes()))
import numpy as np
BOUNDS=json.loads(Path(__file__).with_name("model-bounds.json").read_bytes())

def fits_model_bounds(source, target, entry_id):
    bounds=BOUNDS.get(str(entry_id))
    if bounds is None:return False
    # Catalogue bounds are cell-local. The placement origin is the bottom centre.
    extent=np.maximum(abs(np.array(bounds[:3])-[.5,0,.5]),abs(np.array(bounds[3:])-[.5,0,.5]))
    # Conservative world-space error over the entire box, including its corners.
    return np.linalg.norm(abs(source-target)@extent) <= 1/1024

def placement(m,entry,ident):
    linear=m[:3,:3].T;scales=np.linalg.norm(linear,axis=0);scale=float(np.mean(scales))
    if not np.all(np.isfinite(m)) or min(scales)<=0:return None
    uniform=0.25<=scale<=3 and np.ptp(scales)<=1e-4*max(scale,1)
    r=linear/scales
    curve=None
    if not uniform:
        if entry['id'] not in STRETCH:return None
        lower=max(.25,max(scales)/8);upper=min(3,min(scales)*10)
        if lower>upper:return None
        scale=float(np.clip(scale,lower,upper))
        factors=scales/scale
        if min(factors)<.1-1e-7 or max(factors)>8+1e-7:return None
        factors=np.clip(factors,.1,8)
        curve=struct.pack('>?qii36f',True,ident or 1,0,1,*factors,*([0.0]*33))
    if np.linalg.det(r)<=0:return None
    orthogonal=np.allclose(r.T@r,np.eye(3),atol=1e-4)
    source_rotation=r
    fit=r
    if not orthogonal and str(entry['id']) in BOUNDS:
        bounds=BOUNDS[str(entry['id'])]
        extent=np.maximum(abs(np.array(bounds[:3])-[.5,0,.5]),abs(np.array(bounds[3:])-[.5,0,.5]))
        # Fit the longest physical dimension first on tall or shallow models.
        # The independent box-wide error test below still governs acceptance.
        fit=r*np.maximum(scales*extent,1e-8)**2
    u,_,vh=np.linalg.svd(fit)
    r=u@vh
    pitch=math.atan2(-r[1,2],math.hypot(r[0,2],r[2,2]))
    if abs(math.cos(pitch))>1e-6:yaw=math.atan2(r[0,2],r[2,2]);roll=math.atan2(r[1,0],r[1,1])
    else:yaw=math.atan2(-r[2,0],r[0,0]);roll=0
    cy,sy,cp,sp,cr,sr=math.cos(yaw),math.sin(yaw),math.cos(pitch),math.sin(pitch),math.cos(roll),math.sin(roll)
    rebuilt=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])@np.array([[1,0,0],[0,cp,-sp],[0,sp,cp]])@np.array([[cr,-sr,0],[sr,cr,0],[0,0,1]])
    if not orthogonal or not np.allclose(rebuilt,source_rotation,atol=2e-4):
        applied=scales if curve else np.full(3, scale)
        if not fits_model_bounds(linear,rebuilt*applied,entry['id']):return None
    p=m[3,:3];cell=np.floor(p-[.5,0,.5]).astype(int);off=p-cell-[.5,0,.5]
    if not 0<=cell[1]<256:return None
    return dict(Curve=curve,Pid=ident,Entry=entry['id'],CellX=int(cell[0]),CellY=int(cell[1]),CellZ=int(cell[2]),OffX=float(off[0]),OffY=float(off[1]),OffZ=float(off[2]),Yaw=math.degrees(yaw),Pitch=math.degrees(pitch),Roll=math.degrees(roll),Scale=scale,Ver=1)
