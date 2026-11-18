package szserver.net;

import java.nio.file.*;

/** Offline explicit checkpoint of a stopped independent realm. Never prints credentials or player rows. */
public final class AccountStoreTool {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!java.util.Set.of("checkpoint","compact-index").contains(args[0]))throw new IllegalArgumentException("usage: checkpoint|compact-index <account-json>");
        Path file=Path.of(args[1]).toRealPath();LocalAccounts store=LocalAccounts.openStore(file);try{if(args[0].equals("compact-index"))store.compactIndex().get();else store.compact().get();System.out.println("PASS source-bound account checkpoint:accounts="+store.metrics().get("accounts")+",characters="+store.metrics().get("characters"));}finally{store.close();}if(!store.metrics().get("storageError").equals(""))throw new IllegalStateException("account_checkpoint_close_failed");
    }
}
