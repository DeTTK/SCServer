package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Serial command worker prepares transactions; the owner publishes large voxel edits together. */
final class BlockEditor {
    private record State(int[] voxels,TilePayload.Tile[] tiles,BitSet tileStates){}
    private record Plan(List<World.Block> cells,int[] before,int[] after,TilePayload.Tile[] beforeTiles,TilePayload.Tile[] afterTiles,BitSet managed,boolean legacyDense){
        Plan(List<World.Block> cells,int[] before,int[] after,TilePayload.Tile[] beforeTiles,TilePayload.Tile[] afterTiles,BitSet managed){this(cells,before,after,beforeTiles,afterTiles,managed,false);}
        boolean managesTile(int i){return legacyDense||managed.get(i);}
        boolean hasTiles(){return legacyDense||!managed.isEmpty();}
        boolean changed(int i){return before[i]!=after[i]||managesTile(i)&&!Objects.equals(beforeTiles[i],afterTiles[i]);}
        Plan reverse(){return new Plan(cells,after,before,afterTiles,beforeTiles,managed,legacyDense);}
    }
    private final WorldLoop world;
    private final CommandHistory history;
    private final BlockStateArchive archive;
    private final TileStateArchive tileArchive;
    private record Application(Plan plan,int[] values,TilePayload.Tile[] tiles,BitSet changed,BlockOverlays.Prepared root,SceneEditor.WorldMutation scenes){}
    private String preparedId;
    private Application prepared;
    
    BlockEditor(WorldLoop world,CommandHistory history,BlockStateArchive archive)throws java.io.IOException{this.world=world;this.history=history;this.archive=archive;tileArchive=new TileStateArchive(archive.journal());}
    void validateExternalState(JsonObject event)throws java.io.IOException{if(handles(Json.text(event,"type",32))){archive.validateReferences(event);tileArchive.validateReferences(event);}}
    static boolean handles(String type){return Set.of("fill","copy","cut","paste","move","undo","tile_set","block_cas").contains(type);}
    private static boolean editable(String type){return Set.of("fill","cut","paste","move","tile_set","block_cas").contains(type);}
    private static void bounds(JsonObject event,List<World.Block> cells){event.add("min",Json.tree(cells.get(0)));event.add("max",Json.tree(cells.get(cells.size()-1)));}
    private static int[] repeated(int size,int value){int[] result=new int[size];Arrays.fill(result,value);return result;}
    private List<World.Block> destination(JsonObject event,List<World.Block> cells){JsonObject offset=Json.object(event,"offset");return BlockEdit.translated(cells,Json.integer(offset,"x",-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET),Json.integer(offset,"y",-255,255),Json.integer(offset,"z",-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET));}
    private List<World.Block> union(List<World.Block> source,List<World.Block> destination){return BlockCells.union(source,destination);}
    private BlockReadView source()throws Exception{return world.submit(World::blockReadView).get(5,TimeUnit.SECONDS);}
    private State read(List<World.Block> cells)throws Exception{BlockReadView.Values values=source().read(cells);return new State(values.voxels(),values.tiles(),values.tileStates());}
    private void validate(Plan plan,boolean checkCurrent)throws Exception{
        BlockReadView source=source();BlockReadView.Cursor cursor=source.cursor();int additions=0,tileAdditions=0,payloadDelta=0;long completeDelta=0;
        for(long word:BlockReadView.ordered(plan.cells)){
            int at=BlockReadView.index(word);World.Block p=plan.cells.get(at);int value=plan.after[at];if(!plan.changed(at))continue;
            if(checkCurrent&&(cursor.voxel(p)!=plan.before[at]||plan.managesTile(at)&&!Objects.equals(cursor.tile(p),plan.beforeTiles[at])))throw new IllegalArgumentException("undo_conflicts_with_later_edit");
            source.validateBlock(cursor,p,value);if(plan.before[at]!=value&&!cursor.hasOverride(p))additions++;
            if(plan.managesTile(at)&&!cursor.hasTileOverride(p))tileAdditions++;if(plan.managesTile(at)&&plan.afterTiles[at]!=null&&value>>>4==0)throw new IllegalArgumentException("tile_in_air");
            if(plan.managesTile(at))payloadDelta+=(plan.afterTiles[at]==null?0:1)-(cursor.hasTilePayloadOverride(p)?1:0);
            if(plan.managesTile(at))completeDelta+=(plan.afterTiles[at]==null?0:plan.afterTiles[at].completeStateSize())-cursor.completeTileBytes(p);
        }
        source.validateCapacity(additions,tileAdditions,payloadDelta,completeDelta);
        if(plan.hasTiles())validateChunkTiles(plan,source);
        source.checkCurrent();
    }
    private void validateChunkTiles(Plan plan,BlockReadView source)throws Exception{
        BlockReadView.Cursor cursor=source.cursor();long previous=Long.MIN_VALUE;Set<Integer> present=null;
        for(long word:BlockReadView.ordered(plan.cells)){
            int at=BlockReadView.index(word);if(!plan.changed(at)||!plan.managesTile(at))continue;World.Block p=plan.cells.get(at);int x=Math.floorDiv(p.x(),16),z=Math.floorDiv(p.z(),16);long key=BlockOverlays.key(x,z);
            if(present==null||key!=previous){
                if(present!=null&&present.size()>TilePayload.MAX_TILES)throw new IllegalArgumentException("tile_chunk_count_limit");
                previous=key;World.TileChunk chunk=cursor.tileChunk(x,z);present=new HashSet<>();
                for(var e:chunk.base().entrySet()){BlockOverlays.Voxel edit=chunk.overlay().cells().get(e.getKey());if(e.getValue().blockId()!=0&&(edit==null||edit.id()==e.getValue().blockId()))present.add(e.getKey());}
                for(var e:chunk.overlay().tiles().entrySet()){if(e.getValue()==null)present.remove(e.getKey());else present.add(e.getKey());}
            }
            int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);if(plan.afterTiles[at]==null)present.remove(cell);else present.add(cell);
        }
        if(present!=null&&present.size()>TilePayload.MAX_TILES)throw new IllegalArgumentException("tile_chunk_count_limit");
    }
    private static BitSet tileMask(BitSet known,TilePayload.Tile[] before,TilePayload.Tile[] after){BitSet mask=(BitSet)known.clone();for(int i=0;i<before.length;i++)if(before[i]!=null||after[i]!=null)mask.set(i);return mask;}
    private void saveTiles(JsonObject event,Plan plan)throws java.io.IOException{if(world.residentMap()!=null)TileStateArchive.saveMemory(event,plan.cells,plan.beforeTiles,plan.afterTiles,plan.managed);else tileArchive.save(event,plan.cells,plan.beforeTiles,plan.afterTiles,plan.managed);}
    private JsonElement encode(int[] values)throws java.io.IOException{return world.residentMap()==null?archive.encode(values):BlockStateArchive.memory(values);}
    JsonObject prepare(String type,JsonObject request,JsonObject event)throws Exception{
        if(type.equals("tile_set")||type.equals("block_cas")){
            World.Block at=new World.Block(Json.integer(request,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.integer(request,"y",0,255),Json.integer(request,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));int id=Json.integer(request,"blockId",0,4095),metadata=request.has("metadata")?Json.integer(request,"metadata",0,15):0;
            List<World.Block> cells=List.of(at);State old=read(cells);TilePayload.Tile tile=request.has("completeTile")&&!request.get("completeTile").isJsonNull()?szserver.client.CompleteTileState.decode(szserver.client.CompleteTileState.parts(request)):null;
            if(tile!=null&&(tile.x()!=at.x()||tile.y()!=at.y()||tile.z()!=at.z()))throw new IllegalArgumentException("tile_complete_position_mismatch");
            if(type.equals("block_cas")){int expectedId=Json.integer(request,"expectedBlockId",0,4095),expectedMetadata=Json.integer(request,"expectedMetadata",0,15);byte[] current=old.tiles[0]==null?null:old.tiles[0].completeState(),after=tile==null?null:tile.completeState();if(old.voxels[0]==((id<<4)|metadata)&&szserver.client.CompleteTileState.same(current,after)){event.addProperty("status","unchanged");return event;}if(old.voxels[0]!=((expectedId<<4)|expectedMetadata))throw new IllegalArgumentException("native_block_compare_failed");}
            if(type.equals("block_cas")&&request.has("expectedCompleteTile")){
                byte[] expected=null;TilePayload.Tile wanted=null;if(!request.get("expectedCompleteTile").isJsonNull()){JsonObject wrapped=new JsonObject();wrapped.add("completeTile",request.get("expectedCompleteTile"));expected=szserver.client.CompleteTileState.parts(wrapped);wanted=szserver.client.CompleteTileState.decode(expected);if(wanted.x()!=at.x()||wanted.y()!=at.y()||wanted.z()!=at.z())throw new IllegalArgumentException("tile_complete_position_mismatch");}
                var present=old.tiles[0];if((present==null)!=(wanted==null)||present!=null&&(present.completeStateSize()>0?!szserver.client.CompleteTileState.same(present.completeState(),expected):present.type()!=wanted.type()||present.shapeData()!=wanted.shapeData()||present.shapeExtension()!=wanted.shapeExtension()||present.centralCoverId()!=wanted.centralCoverId()))throw new IllegalArgumentException("native_tile_compare_failed");
            }
            if(request.has("actor"))event.addProperty("actor",UUID.fromString(Json.text(request,"actor",36)).toString());
            TilePayload.Tile[] nextTiles=new TilePayload.Tile[]{tile};Plan plan=new Plan(cells,old.voxels,new int[]{(id<<4)|metadata},old.tiles,nextTiles,tileMask(old.tileStates,old.tiles,nextTiles));validate(plan,false);bounds(event,cells);event.addProperty("blockId",id);event.addProperty("metadata",metadata);event.add("before",encode(old.voxels));saveTiles(event,plan);return event;
        }
        if(type.equals("undo")){
            String target=Json.text(request,"target",64);JsonObject original=history.get(target);
            if(original==null||!editable(Json.text(original,"type",32)))throw new IllegalArgumentException("undo_edit_not_found");
            if(history.undone(target))throw new IllegalArgumentException("edit_already_undone");
            Plan plan=plan(original);validate(plan.reverse(),true);event.addProperty("target",target);event.addProperty("cells",plan.cells.size());return event;
        }
        List<World.Block> cells;
        int[] after;TilePayload.Tile[] afterTiles;
        if(type.equals("paste")){
            String clipboard=Json.text(request,"clipboard",64);JsonObject saved=history.get(clipboard);
            if(saved==null||!Set.of("copy","cut").contains(Json.text(saved,"type",32)))throw new IllegalArgumentException("clipboard_not_found");
            List<World.Block> source=BlockEdit.cells(saved);JsonObject position=Json.object(request,"position");World.Block min=source.get(0);
            int x=Json.integer(position,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),y=Json.integer(position,"y",0,255),z=Json.integer(position,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL);
            cells=BlockEdit.translated(source,x-min.x(),y-min.y(),z-min.z());bounds(event,cells);after=archive.decode(saved,"data",source.size());event.add("data",encode(after));event.addProperty("clipboard",clipboard);afterTiles=tileArchive.read(saved,source).before();for(int i=0;i<afterTiles.length;i++)if(afterTiles[i]!=null)afterTiles[i]=afterTiles[i].at(cells.get(i));
        }else{
            cells=BlockEdit.cells(request);bounds(event,cells);
            if(type.equals("copy")||type.equals("cut")){
                State values=read(cells);event.add("data",encode(values.voxels));event.addProperty("cells",cells.size());
                Plan plan=new Plan(cells,values.voxels,type.equals("cut")?new int[cells.size()]:values.voxels,values.tiles,type.equals("cut")?new TilePayload.Tile[cells.size()]:values.tiles,tileMask(values.tileStates,values.tiles,values.tiles));
                if(type.equals("cut"))validate(plan,false);saveTiles(event,plan);
                return event;
            }
            if(type.equals("move")){
                event.add("offset",Json.object(request,"offset").deepCopy());List<World.Block> dest=destination(event,cells);List<World.Block> all=union(cells,dest);State state=read(all);int[] before=state.voxels;after=before.clone();afterTiles=state.tiles.clone();
                Arrays.fill(after,0,cells.size(),0);Arrays.fill(afterTiles,0,cells.size(),null);for(int i=0;i<cells.size();i++){World.Block to=dest.get(i);int at=all.indexOf(to);after[at]=before[i];afterTiles[at]=state.tiles[i]==null?null:state.tiles[i].at(to);}
                Plan plan=new Plan(all,before,after,state.tiles,afterTiles,tileMask(state.tileStates,state.tiles,afterTiles));validate(plan,false);saveTiles(event,plan);event.add("before",encode(before));event.add("after",encode(after));event.addProperty("cells",all.size());return event;
            }
            int id=Json.integer(request,"blockId",0,4095),metadata=request.has("metadata")?Json.integer(request,"metadata",0,15):0;
            event.addProperty("blockId",id);event.addProperty("metadata",metadata);after=repeated(cells.size(),(id<<4)|metadata);afterTiles=null;
        }
        State state=read(cells);int[] before=state.voxels;if(afterTiles==null){afterTiles=state.tiles.clone();for(int i=0;i<afterTiles.length;i++)if(after[i]>>>4==0||after[i]>>>4!=before[i]>>>4)afterTiles[i]=null;}
        Plan plan=new Plan(cells,before,after,state.tiles,afterTiles,tileMask(state.tileStates,state.tiles,afterTiles));validate(plan,false);saveTiles(event,plan);event.add("before",encode(before));event.addProperty("cells",cells.size());return event;
    }
    private Plan plan(JsonObject event)throws java.io.IOException{
        String type=Json.text(event,"type",32);List<World.Block> source=BlockEdit.cells(event),cells=source;int[] before,after;
        if(type.equals("move")){cells=union(source,destination(event,source));before=archive.decode(event,"before",cells.size());after=archive.decode(event,"after",cells.size());}
        else if(type.equals("cut")){before=archive.decode(event,"data",cells.size());after=new int[cells.size()];}
        else {before=archive.decode(event,"before",cells.size());after=type.equals("paste")?archive.decode(event,"data",cells.size()):repeated(cells.size(),(Json.integer(event,"blockId",0,4095)<<4)|Json.integer(event,"metadata",0,15));}
        TileStateArchive.States tiles=tileArchive.read(event,cells);return new Plan(cells,before,after,tiles.before(),tiles.after(),tiles.managed(),tiles.legacyDense());
    }
    /** Reuse reviewed before/after tile and voxel plans for native transactions, including undo. */
    List<ResidentBlockOperations.Delta> residentDeltas(JsonObject event)throws Exception{
        return residentDeltas(event,32);
    }
    List<ResidentBlockOperations.Delta> residentBulkDeltas(JsonObject event)throws Exception{return residentDeltas(event,2_000_000);}
    private List<ResidentBlockOperations.Delta> residentDeltas(JsonObject event,int limit)throws Exception{
        String type=Json.text(event,"type",32);if(type.equals("copy"))return List.of();JsonObject original=type.equals("undo")?history.get(Json.text(event,"target",64)):event;if(original==null)throw new IllegalArgumentException("undo_target_missing_in_journal");Plan plan=plan(original);if(plan.cells.size()>limit)throw new IllegalArgumentException("resident_interactive_batch_limit");List<ResidentBlockOperations.Delta> rows=new ArrayList<>();boolean inverse=type.equals("undo");
        for(int i=0;i<plan.cells.size();i++)if(plan.changed(i))rows.add(new ResidentBlockOperations.Delta(plan.cells.get(i),inverse?plan.after[i]:plan.before[i],inverse?plan.before[i]:plan.after[i],plan.managesTile(i)?(inverse?plan.afterTiles[i]:plan.beforeTiles[i]):null,plan.managesTile(i)?(inverse?plan.beforeTiles[i]:plan.afterTiles[i]):null));return List.copyOf(rows);
    }
    /** Project this transaction's voxels/tiles on the command worker, so mixed object edits
     * validate the combined native chunk rather than two independently acceptable frames. */
    Map<Long,BlockOverlays.Snapshot> projectedSceneOverlays(JsonObject event,Set<Long> requested)throws Exception{
        String type=Json.text(event,"type",32);if(type.equals("copy"))return Map.of();JsonObject original=type.equals("undo")?history.get(Json.text(event,"target",64)):event;Plan plan=plan(original);int[] values=type.equals("undo")?plan.before:plan.after;TilePayload.Tile[] tiles=type.equals("undo")?plan.beforeTiles:plan.afterTiles;
        BlockReadView source=source();BlockReadView.Cursor cursor=source.cursor();Map<Long,BlockOverlays.Snapshot> result=new HashMap<>();
        long key=Long.MIN_VALUE;BlockOverlays.Snapshot old=null;Map<Integer,BlockOverlays.Voxel> cells=null;Map<Integer,TilePayload.Tile> shape=null;
        for(long word:BlockReadView.ordered(plan.cells)){
            int i=BlockReadView.index(word);if(!plan.changed(i))continue;World.Block p=plan.cells.get(i);long next=BlockOverlays.key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));if(!requested.contains(next))continue;
            if(old==null||key!=next){
                if(old!=null)result.put(key,new BlockOverlays.Snapshot(old.revision(),Map.copyOf(cells),Collections.unmodifiableMap(shape),old.scenes()));
                key=next;old=cursor.overlay((int)(key>>32),(int)key);cells=new HashMap<>(old.cells());shape=new HashMap<>(old.tiles());
            }
            int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);if(plan.before[i]!=plan.after[i])cells.put(cell,new BlockOverlays.Voxel(values[i]>>>4,values[i]&15,old.revision()+1));if(plan.managesTile(i))shape.put(cell,tiles[i]);
        }
        if(old!=null)result.put(key,new BlockOverlays.Snapshot(old.revision(),Map.copyOf(cells),Collections.unmodifiableMap(shape),old.scenes()));
        source.checkCurrent();return result;
    }
    /** Complete all allocations before fsync. A failed journal write leaves the live root untouched. */
    void prepareApply(JsonObject event,SceneEditor.WorldMutation scenes)throws Exception{
        discardPrepared();String type=Json.text(event,"type",32);if(!handles(type)||type.equals("copy"))return;
        prepared=application(event,false,scenes);preparedId=Json.text(event,"commandId",64);
    }
    void discardPrepared(){prepared=null;preparedId=null;}
    private Application application(JsonObject event,boolean bootstrap,SceneEditor.WorldMutation scenes)throws Exception{
        if(scenes!=null)scenes.prepare();
        String type=Json.text(event,"type",32);
        JsonObject original=type.equals("undo")?history.get(Json.text(event,"target",64)):event;
        if(original==null||!editable(Json.text(original,"type",32)))throw new IllegalArgumentException("undo_target_missing_in_journal");
        Plan plan=plan(original);int[] values=type.equals("undo")?plan.before:plan.after;TilePayload.Tile[] tiles=type.equals("undo")?plan.beforeTiles:plan.afterTiles;
        BitSet changed=new BitSet(plan.cells.size());for(int i=0;i<plan.cells.size();i++)if(plan.changed(i))changed.set(i);
        BlockOverlays.Prepared root=null;
        if(changed.cardinality()>BlockEdit.BATCH||scenes!=null){
            BlockOverlays.Capture capture=bootstrap?world.bootstrap(w->w.overlayVersions().capture()):world.submit(w->w.overlayVersions().capture()).get(5,TimeUnit.SECONDS);
            Iterable<BlockOverlays.CellChange> changes=()->new Iterator<>(){
                int index=changed.nextSetBit(0);
                public boolean hasNext(){return index>=0;}
                public BlockOverlays.CellChange next(){if(index<0)throw new NoSuchElementException();int i=index;index=changed.nextSetBit(i+1);return new BlockOverlays.CellChange(plan.cells.get(i),values[i]>>>4,values[i]&15,plan.before[i]!=plan.after[i],plan.managesTile(i),tiles[i]);}
            };
            root=BlockOverlays.prepare(capture,changes,scenes==null?List.of():scenes.overlays());
        }
        return new Application(plan,values,tiles,changed,root,scenes);
    }
    void apply(JsonObject event,boolean bootstrap)throws Exception{
        apply(event,bootstrap,null);
    }
    void apply(JsonObject event,boolean bootstrap,SceneEditor.WorldMutation scenes)throws Exception{
        String type=Json.text(event,"type",32);
        if(type.equals("copy")){List<World.Block> cells=BlockEdit.cells(event);archive.decode(event,"data",cells.size());tileArchive.read(event,cells);return;}
        Application apply=!bootstrap&&prepared!=null&&Objects.equals(preparedId,Json.text(event,"commandId",64))?prepared:application(event,bootstrap,scenes);
        java.util.function.Function<World,Boolean> action=w->{
            if(apply.scenes!=null)apply.scenes.validate(w);
            if(apply.root!=null)w.overlayVersions().publish(apply.root);
            else for(int index=apply.changed.nextSetBit(0);index>=0;index=apply.changed.nextSetBit(index+1)){int value=apply.values[index];if(apply.plan.before[index]!=apply.plan.after[index])w.block(apply.plan.cells.get(index),value>>>4,value&15);if(apply.plan.managesTile(index))w.tile(apply.plan.cells.get(index),apply.tiles[index],value>>>4);}
            if(apply.scenes!=null)apply.scenes.apply(w,true);
            return true;
        };
        if(bootstrap)world.bootstrap(action);else world.submit(action).get(5,TimeUnit.SECONDS);
        if(type.equals("undo"))history.markUndone(Json.text(event,"target",64));
    }
}
