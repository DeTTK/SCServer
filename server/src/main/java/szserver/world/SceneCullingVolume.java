package szserver.world;

import java.util.UUID;

/** Old map-index volume policy: affine local [-.5,.5] bounds expanded to world-axis AABB. */
public record SceneCullingVolume(UUID id,String asset,World.Vec position,World.Vec halfExtent) {
    public static SceneCullingVolume from(SceneObject object){
        if(!object.asset().equals("culling_volume")&&!object.asset().equals("light_culling_volume"))return null;
        return new SceneCullingVolume(object.identity(),object.asset(),new World.Vec(object.x(),object.y(),object.z()),object.halfExtent());
    }
    /** Match the first native volume occurrence selected by the base catalog. */
    public static SceneCullingVolume from(ScenePayload.Entry entry){
        if(entry!=null)for(var occurrence:entry.occurrences()){var volume=from(occurrence.object());if(volume!=null)return volume;}return null;
    }
    public boolean contains(SceneObject object){return Math.abs(object.x()-position.x())<=halfExtent.x()+1e-4&&Math.abs(object.y()-position.y())<=halfExtent.y()+1e-4&&Math.abs(object.z()-position.z())<=halfExtent.z()+1e-4;}
    public boolean selected(double x,double z,double radius){return Math.hypot(position.x()-x,position.z()-z)<=radius;}
}
