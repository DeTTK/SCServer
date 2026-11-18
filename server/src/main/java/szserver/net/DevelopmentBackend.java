package szserver.net;

import szserver.commands.CommandService;
import szserver.persistence.*;
import java.nio.file.Path;
import java.util.*;

/** Lifecycle bridge: borrow native services, or own the same stores when game.enabled=false. */
public final class DevelopmentBackend implements AutoCloseable {
    private final boolean owns;
    private final LocalAccounts accounts;
    private final CharacterLocations locations;
    private final DeveloperBlocks blocks;
    private final DevelopmentService service;
    private boolean closed;
    public DevelopmentBackend(GameServer game,Path data,String worldIdentity,CommandService commands)throws Exception{this(game,Path.of(".").toAbsolutePath(),data,worldIdentity,commands);}
    public DevelopmentBackend(GameServer game,Path root,Path data,String worldIdentity,CommandService commands)throws Exception{
        ArrayDeque<AutoCloseable> rollback=new ArrayDeque<>();owns=game==null;
        try{accounts=owns?LocalAccounts.openStore(data.resolve("local-accounts.json")):game.developmentAccounts();if(owns)rollback.push(accounts);locations=owns?new CharacterLocations(data,worldIdentity):game.developmentLocations();if(owns)rollback.push(locations);if(owns&&accounts.hasPendingCharacterDeletions()){try(var inventory=new szserver.inventory.InventoryJournal(data.resolve("characters.sjournal"),new szserver.inventory.ItemCatalog(root.resolve("fixtures/item-definitions.json")))){accounts.recoverCharacterDeletions(id->inventory.deleteCharacter(id).thenCompose(ignored->locations.deleteCharacter(id))).get(30,java.util.concurrent.TimeUnit.SECONDS);}}blocks=new DeveloperBlocks(data);rollback.push(blocks);service=DevelopmentService.streaming(accounts,locations,blocks,commands::developmentSceneDocument);rollback.clear();}
        catch(Exception|Error e){while(!rollback.isEmpty())try{rollback.pop().close();}catch(Throwable cleanup){e.addSuppressed(cleanup);}throw e;}
    }
    public DevelopmentService service(){return service;}
    public boolean healthy(){return accounts.healthy()&&locations.healthy()&&blocks.healthy();}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"sharedNativeStores",!owns,"blocks",blocks.metrics());}
    @Override public synchronized void close()throws Exception{if(closed)return;closed=true;Exception failure=null;for(AutoCloseable store:owns?List.of(blocks,locations,accounts):List.of(blocks))try{store.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;}
}
