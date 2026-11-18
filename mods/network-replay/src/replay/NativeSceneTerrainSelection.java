package replay;

import java.io.*;
import java.lang.reflect.Array;
import java.util.*;

/** Own terrain draw lists and GPU meshes. It never schedules native chunk builders. */
final class NativeSceneTerrainSelection implements AutoCloseable {
    final Object world,renderer;
    final String layout;
    Object grid;Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> previous=Map.of();
    final LinkedHashMap<SceneTracks.Bytes,Object> cells=new LinkedHashMap<>(16,.75f,true);
    final SceneIdentityAliases<Object> cellAliases=new SceneIdentityAliases<>(32768);
    final IdentityHashMap<Object,SceneTracks.Bytes> representatives=new IdentityHashMap<>();
    final IdentityHashMap<Object,SceneTerrainCodec.Coordinate> coordinates=new IdentityHashMap<>();
    final IdentityHashMap<Object,Long> sizes=new IdentityHashMap<>();
    SceneDomainMap<SceneTerrainCodec.Coordinate,Object> selectedCoordinates=new SceneDomainMap<>();
    SceneDomainMap<Object,Boolean> membership=SceneDomainMap.identityKeys();
    SceneTerrainChanges pendingChanges;
    Set<Object> selected=Set.of();long residentBytes,logicalResidentBytes,uploads,updates,canonicalChecks,gridReads,drawCalls,drawn;int lastVisible,emptyCells;
    boolean incremental,hasSelection;
    final long residentBudget;
    Map<SceneTracks.ObjectId,SceneTracks.ObjectState> prefetchedRows=Map.of();
    Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> prefetchedTerrain=Map.of();
    final ArrayDeque<SceneTracks.Bytes> prefetchQueue=new ArrayDeque<>();
    final Set<SceneTracks.Bytes> queued=Collections.newSetFromMap(new IdentityHashMap<>());
    final SceneTerrainPreparation preparation=new SceneTerrainPreparation();
    final SceneTerrainBuffers<Object> buffers=new SceneTerrainBuffers<>(new SceneTerrainBuffers.Access<>(){
        public Object create(SceneTerrainCodec.Pass pass)throws Exception{return SceneTerrainCodec.createPass(pass);}
        public void dispose(Object pass)throws Exception{Native.call(Native.field(pass,"double"),"while","void");}
    });
    final SceneTerrainOrder order=new SceneTerrainOrder();
    final SceneTerrainCull culling=new SceneTerrainCull();
    final SceneTerrainViews views=new SceneTerrainViews();
    record DrawArrays(Object culling,Object[] sorting){}
    DrawArrays selectedDraw;
    void prepareGrid()throws Exception {
        if(grid==null){Object original=Native.field(renderer,"strictfp");int exponent=((Number)Native.field(original,"import")).intValue();grid=Native.type("1102540382").getConstructor(Native.type("1054393445"),int.class,int.class,int.class,int.class).newInstance(world,exponent,0,1,0);((List<?>)Native.field(grid,"const")).clear();}
    }
    boolean compileView(Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> values,long deadline)throws Exception {
        prepareGrid();int size=((Number)Native.field(grid,"transient")).intValue();
        return views.prepare(values,size,new SceneTerrainViews.Cells(){
            public Object resolve(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes value)throws Exception {
                Object cell=cellAliases.get(value);if(cell==null)cell=cells.get(value);
                if(cell==null)throw new IOException("Unprepared terrain cell in compiled selection: "+coordinate);
                if(!coordinate.equals(coordinates.get(cell)))throw new IOException("Compiled terrain coordinate changed");return cell;
            }
            public boolean drawable(Object cell){return sizes.get(cell)>0;}
            public Object draw(List<Object> values)throws Exception {Object array=Array.newInstance(Native.type("949691181"),values.size());for(int n=0;n<values.size();n++)Array.set(array,n,values.get(n));return new DrawArrays(array,values.toArray());}
        },deadline,System::nanoTime);
    }
    static final class Pending extends Exception {Pending(){super(null,null,false,false);}}
    void prefetch(Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows,long deadline)throws Exception {
        if(rows==null)return;
        if(rows!=prefetchedRows){
            for(var id:SceneTracks.changedMapRows(prefetchedRows,rows)){var row=rows.get(id);if(row!=null&&row.type().equals("world-map-terrain")&&row.channels().get("value") instanceof SceneTracks.Bytes bytes&&cellAliases.get(bytes)==null&&queued.add(bytes))prefetchQueue.addLast(bytes);}
            prefetchedRows=rows;
        }
        pumpPrefetch(deadline);
    }
    void prefetchDecoded(Map<SceneTerrainCodec.Coordinate,SceneTracks.Bytes> values,long deadline)throws Exception {
        if(values!=prefetchedTerrain){
            int size=1<<((Number)Native.field(Native.field(renderer,"strictfp"),"import")).intValue();
            for(var change:SceneDomainMap.changes(prefetchedTerrain,values))if(change.after()!=null&&change.key().size()==size&&cellAliases.get(change.after())==null&&queued.add(change.after()))prefetchQueue.addLast(change.after());
            prefetchedTerrain=values;
        }
        pumpPrefetch(deadline);
    }
    private void pumpPrefetch(long deadline)throws Exception {
        int size=1<<((Number)Native.field(Native.field(renderer,"strictfp"),"import")).intValue();
        int attempts=Math.min(8,prefetchQueue.size());
        List<SceneTracks.Bytes> pending=new ArrayList<>(8);
        try{
        while(attempts-->0&&!prefetchQueue.isEmpty()){
            if(System.nanoTime()>=deadline)return;SceneTracks.Bytes bytes=prefetchQueue.removeFirst();queued.remove(bytes);
            if(cellAliases.get(bytes)!=null)continue;
            Object existing=cells.get(bytes);if(existing!=null){cellAliases.put(bytes,existing);continue;}
            var mesh=preparation.request(bytes,false);if(mesh==null){queued.add(bytes);pending.add(bytes);continue;}if(mesh.coordinate().size()!=size)continue;
            Object cell=SceneTerrainCodec.create(mesh,layout,buffers);cells.put(bytes,cell);long amount=mesh.passes().stream().filter(Objects::nonNull).mapToLong(p->p.vertexByteSize()).sum();sizes.put(cell,amount);logicalResidentBytes+=amount;if(amount==0)emptyCells++;residentBytes=buffers.residentBytes;uploads++;
            coordinates.put(cell,mesh.coordinate());representatives.put(cell,bytes);cellAliases.put(bytes,cell);
        }
        }finally{for(int n=pending.size()-1;n>=0;n--)prefetchQueue.addFirst(pending.get(n));}
    }
    NativeSceneTerrainSelection(Object world)throws Exception {
        this.world=world;renderer=Native.call(world,"throw","1376962110");layout=SceneTerrainCodec.nativeLayout();
        long budget=512L*1024*1024;
        Object capabilities=Native.call(Native.type("org.lwjgl.opengl.GL"),"getCapabilities","org.lwjgl.opengl.GLCapabilities");
        if(Boolean.TRUE.equals(Native.field(capabilities,"GL_NVX_gpu_memory_info"))){long available=((Number)Native.call(Native.type("org.lwjgl.opengl.GL11"),"glGetInteger","int",0x9049)).longValue()*1024;budget=Math.max(budget,Math.min(1536L*1024*1024,available/4));}
        residentBudget=budget;
    }
    void prepare(SceneMapIndex.Selection values,NativeScenePresenter.Frame frame)throws Exception {
        prepareGrid();
        if(!views.views.isEmpty()){
            var view=views.select(values.terrain());DrawArrays draw=(DrawArrays)view.draw();
            if(selectedDraw!=draw){Object list=Native.field(grid,"catch");SourceAdapters.put(list,"const",draw.sorting());SourceAdapters.put(list,"try",draw.sorting().length);SourceAdapters.put(grid,"case",draw.culling());selectedDraw=draw;order.invalidate();culling.invalidate();}
            selectedCoordinates=view.coordinates();membership=view.membership();selected=view.selected();previous=values.terrain();hasSelection=true;pendingChanges=null;
            frame.terrainRenderer=renderer;frame.terrain=this;return;
        }
        if(values.terrain()!=previous){
            long deadline=incremental?System.nanoTime()+2_000_000L:Long.MAX_VALUE;
            if(pendingChanges==null||!pendingChanges.matches(values.terrain(),selectedCoordinates,membership))pendingChanges=new SceneTerrainChanges(previous,values.terrain(),selectedCoordinates,membership);
            SceneTerrainChanges changes=pendingChanges;
            int size=((Number)Native.field(grid,"transient")).intValue();
            if(!changes.advance(size,new SceneTerrainChanges.Cells(){
                public void reserve(Collection<SceneTracks.Bytes> values){List<SceneTracks.Bytes> missing=new ArrayList<>();for(var value:values)if(cellAliases.get(value)==null)missing.add(value);preparation.reserve(missing);}
                public void prefetch(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes value)throws Exception{if(cellAliases.get(value)==null)preparation.request(value,false);}
                public Object resolve(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes value)throws Exception{return resolveCell(coordinate,value);}
                public boolean drawable(Object cell){return sizes.get(cell)>0;}
            },deadline,System::nanoTime))throw new Pending();
            var candidateCoordinates=changes.coordinates.finish();
            if(changes.drawChanged){
                order.invalidate();
                culling.invalidate();
                List<Object> candidate=new ArrayList<>();for(Object cell:candidateCoordinates.values())if(sizes.get(cell)>0)candidate.add(cell);
                Object list=Native.field(grid,"catch");Native.call(list,"clear","void");for(Object cell:candidate)Native.call(list,"add","boolean",cell);
                Object array=Array.newInstance(Native.type("949691181"),candidate.size());for(int i=0;i<candidate.size();i++)Array.set(array,i,candidate.get(i));SourceAdapters.put(grid,"case",array);
            }
            selectedCoordinates=candidateCoordinates;
            membership=changes.membership.finish();var selectedMembership=membership;
            selected=Collections.unmodifiableSet(new AbstractSet<>(){
                public int size(){return selectedMembership.size();}
                public boolean contains(Object cell){return selectedMembership.containsKey(cell);}
                public Iterator<Object> iterator(){return selectedMembership.keySet().iterator();}
            });previous=values.terrain();hasSelection=true;pendingChanges=null;preparation.reserve(List.of());
            // Unselected versions can be uploaded directly from their own output on a later seek.
            for(var iterator=cells.entrySet().iterator();(residentBytes>residentBudget||cells.size()>32768)&&iterator.hasNext();){var entry=iterator.next();if(selected.contains(entry.getValue()))continue;SceneTerrainCodec.dispose(entry.getValue(),buffers);cellAliases.removeTarget(entry.getValue());representatives.remove(entry.getValue());coordinates.remove(entry.getValue());long bytes=sizes.remove(entry.getValue());logicalResidentBytes-=bytes;if(bytes==0)emptyCells--;residentBytes=buffers.residentBytes;iterator.remove();}
        }else pendingChanges=null;
        frame.terrainRenderer=renderer;frame.terrain=this;
    }
    Object resolveCell(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes value)throws Exception {
        return resolveCell(coordinate,value,null);
    }
    void catalogue(SceneTerrainCatalog.Prepared prepared)throws Exception {
        int size=1<<((Number)Native.field(Native.field(renderer,"strictfp"),"import")).intValue();
        if(prepared.mesh().coordinate().size()!=size)return;
        resolveCell(prepared.mesh().coordinate(),prepared.value(),prepared.mesh());
        if(residentBytes>residentBudget)throw new IOException("Recorded terrain exceeds resident vertex budget: "+residentBytes+" > "+residentBudget);
    }
    Object resolveCell(SceneTerrainCodec.Coordinate coordinate,SceneTracks.Bytes value,SceneTerrainCodec.Mesh prepared)throws Exception {
        Object cell=cellAliases.get(value);boolean aliased=cell!=null;
        if(cell!=null)cells.get(representatives.get(cell));
        if(cell==null)cell=cells.get(value);
        if(cell==null){
            var mesh=prepared!=null?prepared:incremental?preparation.request(value,true):SceneTerrainCodec.decode(value);if(mesh==null)return null;
            if(!mesh.coordinate().equals(coordinate))throw new IOException("Terrain mesh identity changed");
            cell=SceneTerrainCodec.create(mesh,layout,buffers);boolean retained=false;
            try{
                if(Boolean.getBoolean("sz.replay.qa.scene-terrain-audit")){if(!SceneTerrainCodec.capture(cell,layout).equals(value))throw new IOException("Canonical selected terrain differs: "+coordinate);canonicalChecks++;}
                cells.put(value,cell);long bytes=mesh.passes().stream().filter(Objects::nonNull).mapToLong(p->p.vertexByteSize()).sum();sizes.put(cell,bytes);logicalResidentBytes+=bytes;if(bytes==0)emptyCells++;residentBytes=buffers.residentBytes;uploads++;retained=true;
            }finally{if(!retained){SceneTerrainCodec.dispose(cell,buffers);residentBytes=buffers.residentBytes;}}
        }
        if(!coordinates.containsKey(cell)){coordinates.put(cell,coordinate);representatives.put(cell,value);}
        if(!coordinate.equals(coordinates.get(cell)))throw new IOException("Cached terrain mesh identity changed");
        if(!aliased)cellAliases.put(value,cell);return cell;
    }
    Object grid(Object owner,Object original){if(owner==renderer&&grid!=null){gridReads++;return grid;}return original;}
    boolean update(Object original,Object view)throws Exception {
        // The canonical renderer invokes its old grid update; redirect only the selected view.
        if(original!=Native.field(renderer,"strictfp")&&original!=grid)return false;
        if(grid==null)return false;
        culling.update(grid,view);order.update(grid,view);updates++;return true;
    }
    Integer draw(Object selectedGrid,Object view,int pass)throws Exception {
        if(selectedGrid!=grid)return null;
        if(pass<0||pass>2)throw new IOException("Invalid selected terrain pass");
        // Visibility flags live on the cells and are shared by all views. A
        // shadow pass must never inherit the main camera's frustum (or vice versa).
        culling.update(grid,view);order.update(grid,view);
        Object camera=Native.call(view,"double","1738045740");int size=((Number)Native.call(Native.field(grid,"catch"),"size","int")).intValue();
        int count=((Number)Native.call(grid,"continue","int",camera,0,size,pass,false)).intValue();if(pass==0)SourceAdapters.put(grid,"public",count);drawCalls++;drawn+=count;return count;
    }
    Map<String,Object> diagnostic(){
        // Counting visibility is diagnostic work, not a draw-view update.
        lastVisible=0;if(grid!=null)try{for(Object cell:(Object[])Native.field(grid,"case"))if((boolean)Native.field(cell,"float"))lastVisible++;}catch(Exception failure){lastVisible=-1;}
        Map<String,Object> result=new LinkedHashMap<>(Map.of("selected_cells",selected.size(),"gpu_uploads",uploads,"owned_view_updates",updates,"resident_vertex_bytes",residentBytes,"native_builder_tasks",0,"canonical_mesh_checks",canonicalChecks,"full_map_geometry_verified",false));result.put("compiled_selections",views.compiled);result.put("prepared_draw_lists",views.drawLists);result.put("empty_cells_without_native_objects",emptyCells);result.put("shared_buffer_hits",buffers.hits);result.put("vertex_buffer_uploads",buffers.created);result.put("shared_vertex_buffers",buffers.entries.size());result.put("logical_vertex_bytes",logicalResidentBytes);result.put("resident_budget_bytes",residentBudget);result.put("prefetch_pending",prefetchQueue.size());result.put("owned_grid_reads",gridReads);result.put("owned_draw_calls",drawCalls);result.put("drawn_mesh_passes",drawn);result.put("visible_cells",lastVisible);return result;
    }
    public void close()throws Exception {
        Exception failure=null;pendingChanges=null;views.clear();selectedDraw=null;try{preparation.close();}catch(Exception error){failure=error;}prefetchQueue.clear();queued.clear();prefetchedRows=Map.of();
        for(Object cell:cells.values())try{SceneTerrainCodec.dispose(cell,buffers);}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}cells.clear();cellAliases.clear();representatives.clear();coordinates.clear();sizes.clear();selected=Set.of();selectedCoordinates=new SceneDomainMap<>();membership=SceneDomainMap.identityKeys();residentBytes=buffers.residentBytes;logicalResidentBytes=0;emptyCells=0;
        if(!buffers.entries.isEmpty()||!buffers.owners.isEmpty()){Exception error=new IOException("Terrain buffers outlived their cells");if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(grid!=null){
            // Its builder thread was deliberately never started. Native throw()
            // joins that thread and requires its finally block to have run.
            for(String resource:List.of("catch","const"))try{Native.call(Native.field(grid,resource),"clear","void");}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            try{Native.call(Native.field(grid,"switch"),"float","void");Native.call(Native.field(grid,"implements"),"catch","void");}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            try{Native.call(Native.type("org.lwjgl.opengl.GL33"),"glDeleteQueries","void",Native.field(grid,"volatile"));}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}finally{grid=null;}
        }
        if(failure!=null)throw failure;
    }
}
