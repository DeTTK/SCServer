package szserver.net;

import com.google.gson.*;
import java.util.*;

/** Detached touched-row changes. Serialisation belongs to the account storage worker. */
record AccountDelta(long revision,List<AccountMeta> accounts,List<CharacterChange> characters,List<ProfileMeta> profiles,
                    List<AccountStoreSnapshot.Token> tokens,List<String> removedTokens,List<AccountStoreSnapshot.Ticket> tickets,List<String> removedTickets,List<UUID> pendingDeletions){
    record AccountMeta(UUID id,String hash){}record CharacterChange(UUID owner,String name,UUID id,boolean removed){}
    record ProfileMeta(String login,UUID owner,String session,String legal){}
    static String ticketKey(AccountStoreSnapshot.Ticket row){return row.grant().protocolId()+":"+row.session();}
    static AccountDelta capture(long revision,AccountStoreSnapshot before,AccountStoreSnapshot after,Set<UUID> touchedAccounts,Set<String> touchedProfiles){
        List<AccountMeta> accounts=new ArrayList<>();List<CharacterChange> chars=new ArrayList<>();List<ProfileMeta> profiles=new ArrayList<>();
        for(UUID id:touchedAccounts){var row=after.accounts.get(id);var prior=before.accounts.get(id);accounts.add(new AccountMeta(id,row.tokenHash()));
            if(prior!=null)for(var entry:prior.characters().entrySet())if(!entry.getValue().equals(row.characters().get(entry.getKey())))chars.add(new CharacterChange(id,entry.getKey(),entry.getValue(),true));
            for(var entry:row.characters().entrySet())if(prior==null||!entry.getValue().equals(prior.characters().get(entry.getKey())))chars.add(new CharacterChange(id,entry.getKey(),entry.getValue(),false));
        }
        for(String login:touchedProfiles){var row=after.profiles.get(login);var prior=before.profiles.get(login);if(prior!=null&&prior.accountId().equals(row.accountId())&&prior.session().equals(row.session())&&Objects.equals(prior.legal(),row.legal()))continue;profiles.add(new ProfileMeta(login,row.accountId(),row.session(),row.legal()));}
        Map<String,AccountStoreSnapshot.Token> oldTokens=new HashMap<>(),newTokens=new HashMap<>();before.tokens.forEach(row->oldTokens.put(row.token(),row));after.tokens.forEach(row->newTokens.put(row.token(),row));
        List<AccountStoreSnapshot.Token> tokens=new ArrayList<>();newTokens.forEach((key,row)->{if(!row.equals(oldTokens.get(key)))tokens.add(row);});List<String> removedTokens=oldTokens.keySet().stream().filter(key->!newTokens.containsKey(key)).toList();
        Map<String,AccountStoreSnapshot.Ticket> oldTickets=new HashMap<>(),newTickets=new HashMap<>();before.tickets.forEach(row->oldTickets.put(ticketKey(row),row));after.tickets.forEach(row->newTickets.put(ticketKey(row),row));
        List<AccountStoreSnapshot.Ticket> tickets=new ArrayList<>();newTickets.forEach((key,row)->{if(!row.equals(oldTickets.get(key)))tickets.add(row);});List<String> removedTickets=oldTickets.keySet().stream().filter(key->!newTickets.containsKey(key)).toList();
        return new AccountDelta(revision,List.copyOf(accounts),List.copyOf(chars),List.copyOf(profiles),List.copyOf(tokens),removedTokens,List.copyOf(tickets),removedTickets,List.copyOf(after.pendingDeletions));
    }
    JsonObject json(UUID store){JsonObject event=new JsonObject();event.addProperty("type","account-delta");event.addProperty("store",store.toString());event.addProperty("revision",revision);
        JsonArray values=new JsonArray();for(var row:accounts){JsonObject value=new JsonObject();value.addProperty("id",row.id().toString());value.addProperty("tokenHash",row.hash());values.add(value);}event.add("accounts",values);
        values=new JsonArray();for(var row:characters){JsonObject value=new JsonObject();value.addProperty("account",row.owner().toString());value.addProperty("name",row.name());value.addProperty("id",row.id().toString());value.addProperty("removed",row.removed());values.add(value);}event.add("characters",values);
        values=new JsonArray();for(var row:profiles){JsonObject value=new JsonObject();value.addProperty("login",row.login());value.addProperty("accountId",row.owner().toString());value.addProperty("session",row.session());value.add("legal",row.legal()==null?JsonNull.INSTANCE:new JsonPrimitive(row.legal()));values.add(value);}event.add("profiles",values);
        values=new JsonArray();for(var row:tokens){JsonObject value=new JsonObject();value.addProperty("token",row.token());value.addProperty("login",row.login());value.addProperty("name",row.name());value.addProperty("expires",row.expires());value.addProperty("worldHost",row.worldHost());values.add(value);}event.add("tokens",values);
        values=new JsonArray();removedTokens.forEach(values::add);event.add("removedTokens",values);
        values=new JsonArray();for(var row:tickets){JsonObject value=new JsonObject();var grant=row.grant();value.addProperty("protocol",grant.protocolId().toString());value.addProperty("character",grant.characterId().toString());value.addProperty("name",grant.name());value.addProperty("session",row.session());value.addProperty("expires",row.expires());value.addProperty("worldHost",grant.worldHost());if(row.tokenDigest()!=null){value.addProperty("login",row.login());value.addProperty("tokenDigest",row.tokenDigest());}values.add(value);}event.add("tickets",values);
        values=new JsonArray();removedTickets.forEach(values::add);event.add("removedTickets",values);values=new JsonArray();for(UUID id:pendingDeletions)values.add(id.toString());event.add("pendingCharacterDeletes",values);return event;
    }
}
