package szserver.net;

import com.google.gson.stream.JsonWriter;
import com.google.gson.*;
import java.lang.reflect.Type;
import java.util.function.Function;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Immutable account-store publication. Copy touched buckets, stream JSON only on storage workers. */
final class AccountStoreSnapshot {
    static final long MAX_BYTES=32L*1024*1024;
    private static final int PARTS=64;
    /** Persistent hash trie: mutation copies at most seven bounded32-way nodes, never a catalog bucket. */
    static final class Index<K,V> {
        private record Entry<K,V>(K key,V value){}
        private static final class Node {final Object[] slots;Node(Object[] slots){this.slots=slots;}}
        private record Leaf<K,V>(int hash,List<Entry<K,V>> rows){}
        private final Node root;
        private final DiskAccountTree disk;private final long offset;private final Type valueType;private final Function<String,K> decode;private final String table;
        private static final Gson ROW_JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
        Index(){root=new Node(new Object[32]);disk=null;offset=0;valueType=null;decode=null;table=null;}private Index(Node root){this.root=root;disk=null;offset=0;valueType=null;decode=null;table=null;}
        private Index(DiskAccountTree disk,long offset,Type type,Function<String,K> decode,String table){root=null;this.disk=disk;this.offset=offset;valueType=type;this.decode=decode;this.table=table;}
        static <K,V> Index<K,V> disk(DiskAccountTree disk,Type type,Function<String,K> decode){return disk(disk,type,decode,null);}
        static <K,V> Index<K,V> disk(DiskAccountTree disk,Type type,Function<String,K> decode,String table){return new Index<>(disk,0,type,decode,table);}
        long offset(){return offset;}
        Index<K,V> relocate(DiskAccountTree next,long root){return new Index<>(next,root,valueType,decode,table);}
        long size(){if(disk!=null)return disk.size(offset);long[] n={0};forEach((k,v)->n[0]++);return n[0];}
        Iterator<Map.Entry<K,V>> entries(){if(disk==null){List<Map.Entry<K,V>> all=new ArrayList<>();forEach((k,v)->all.add(Map.entry(k,v)));return all.iterator();}return new Iterator<>(){final Iterator<DiskAccountTree.Row> rows=disk.iterator(offset);public boolean hasNext(){return rows.hasNext();}public Map.Entry<K,V> next(){var row=rows.next();return Map.entry(decode.apply(row.key()),ROW_JSON.fromJson(new String(row.value(),StandardCharsets.UTF_8),valueType));}};}
        private static int hash(Object key){int value=key.hashCode();return value^(value>>>16);}
        @SuppressWarnings("unchecked") V get(K key){if(disk!=null){byte[] data=disk.get(offset,key.toString());return data==null?null:ROW_JSON.fromJson(new String(data,StandardCharsets.UTF_8),valueType);}int hash=hash(key),shift=0;Node node=root;for(;;){Object value=node.slots[(hash>>>shift)&31];if(value==null)return null;if(value instanceof Node child){node=child;shift+=5;continue;}Leaf<K,V> leaf=(Leaf<K,V>)value;if(leaf.hash()!=hash)return null;for(var row:leaf.rows())if(row.key().equals(key))return row.value();return null;}}
        boolean contains(K key){return get(key)!=null;}
        @SuppressWarnings("unchecked") private static <K,V> Node change(Node node,int hash,int shift,K key,V value){
            int at=(hash>>>shift)&31;Object previous=node.slots[at],next;
            if(previous instanceof Node child){Node changed=change(child,hash,shift+5,key,value);next=Arrays.stream(changed.slots).allMatch(Objects::isNull)?null:changed;}
            else if(previous==null){if(value==null)return node;next=new Leaf<K,V>(hash,List.of(new Entry<>(key,value)));}
            else{Leaf<K,V> leaf=(Leaf<K,V>)previous;if(leaf.hash()!=hash){if(value==null)return node;Node child=new Node(new Object[32]);for(var row:leaf.rows())child=change(child,leaf.hash(),shift+5,row.key(),row.value());next=change(child,hash,shift+5,key,value);}
                else{List<Entry<K,V>> rows=new ArrayList<>(leaf.rows());boolean found=false;for(int i=0;i<rows.size();i++)if(rows.get(i).key().equals(key)){if(value==null)rows.remove(i);else rows.set(i,new Entry<>(key,value));found=true;break;}if(!found){if(value==null)return node;rows.add(new Entry<>(key,value));}next=rows.isEmpty()?null:new Leaf<K,V>(hash,List.copyOf(rows));}}
            if(next==previous)return node;Object[] slots=node.slots.clone();slots[at]=next;return new Node(slots);
        }
        Index<K,V> put(K key,V value){if(disk!=null){byte[] data=value==null?null:ROW_JSON.toJson(value,valueType).getBytes(StandardCharsets.UTF_8);long next=disk.put(offset,key.toString(),data);disk.changed(table,key.toString(),data);return new Index<>(disk,next,valueType,decode,table);}Node next=change(root,hash(key),0,key,value);return next==root?this:new Index<>(next);}
        @SuppressWarnings("unchecked") private void visit(Node node,BiConsumer<K,V> consumer){for(Object value:node.slots)if(value instanceof Node child)visit(child,consumer);else if(value!=null)for(var row:((Leaf<K,V>)value).rows())consumer.accept(row.key(),row.value());}
        void forEach(BiConsumer<K,V> consumer){if(disk!=null){var rows=disk.iterator(offset);while(rows.hasNext()){var row=rows.next();consumer.accept(decode.apply(row.key()),ROW_JSON.fromJson(new String(row.value(),StandardCharsets.UTF_8),valueType));}return;}visit(root,consumer);}
        Iterable<V> values(){if(disk!=null)return ()->new Iterator<>(){final Iterator<DiskAccountTree.Row> rows=disk.iterator(offset);public boolean hasNext(){return rows.hasNext();}public V next(){return ROW_JSON.fromJson(new String(rows.next().value(),StandardCharsets.UTF_8),valueType);}};return ()->new Iterator<>(){
            final ArrayDeque<Iterator<Object>> nodes=new ArrayDeque<>();Iterator<Entry<K,V>> rows=Collections.emptyIterator();{nodes.push(Arrays.asList(root.slots).iterator());}
            @SuppressWarnings("unchecked") public boolean hasNext(){while(!rows.hasNext()&&!nodes.isEmpty()){var cursor=nodes.peek();if(!cursor.hasNext()){nodes.pop();continue;}Object value=cursor.next();if(value instanceof Node child)nodes.push(Arrays.asList(child.slots).iterator());else if(value!=null)rows=((Leaf<K,V>)value).rows().iterator();}return rows.hasNext();}
            public V next(){if(!hasNext())throw new NoSuchElementException();return rows.next().value();}
        };}
    }
    record Account(UUID id,String tokenHash,Map<String,UUID> characters,List<LocalAccounts.CharacterIdentity> identities) {
        Account {characters=Collections.unmodifiableMap(new LinkedHashMap<>(characters));identities=List.copyOf(identities);}
        Account(UUID id,String hash,Map<String,UUID> characters){this(id,hash,characters,characters.entrySet().stream().sorted((a,b)->AccountIdentity.compareNames(a.getKey(),b.getKey())).map(e->new LocalAccounts.CharacterIdentity(e.getValue(),e.getKey())).toList());}
    }
    record Profile(String login,UUID accountId,String session,String legal,Map<String,UUID> characters) {}
    record Token(String token,String login,String name,long expires,String worldHost) {}
    record Ticket(LocalAccounts.Grant grant,String session,long expires,String login,String tokenDigest) {}
    final Index<UUID,Account> accounts;
    final Index<String,Profile> profiles;
    final Index<UUID,UUID> characters;
    final List<Token> tokens;
    final List<Ticket> tickets;
    final List<UUID> pendingDeletions;
    AccountStoreSnapshot(){this(new Index<>(),new Index<>(),new Index<>(),List.of(),List.of());}
    AccountStoreSnapshot(Index<UUID,Account> accounts,Index<String,Profile> profiles,Index<UUID,UUID> characters,List<Token> tokens,List<Ticket> tickets){this(accounts,profiles,characters,tokens,tickets,List.of());}
    AccountStoreSnapshot(Index<UUID,Account> accounts,Index<String,Profile> profiles,Index<UUID,UUID> characters,List<Token> tokens,List<Ticket> tickets,Collection<UUID> pendingDeletions){this.accounts=accounts;this.profiles=profiles;this.characters=characters;this.tokens=List.copyOf(tokens);this.tickets=List.copyOf(tickets);this.pendingDeletions=List.copyOf(pendingDeletions);}
    AutoCloseable retain(){return accounts.disk==null?()->{}:accounts.disk.pin();}
    AccountStoreSnapshot relocate(DiskAccountTree next,Map<String,Long> roots){return new AccountStoreSnapshot(accounts.relocate(next,roots.get("snapshotAccounts")),profiles.relocate(next,roots.get("snapshotProfiles")),characters.relocate(next,roots.get("snapshotCharacters")),tokens,tickets,pendingDeletions);}
    Map<String,Long> roots(){return Map.of("snapshotAccounts",accounts.offset,"snapshotProfiles",profiles.offset,"snapshotCharacters",characters.offset);}
    List<LocalAccounts.CharacterIdentity> identities(UUID owner){Account row=accounts.get(owner);return row==null?null:row.identities;}
    boolean characterExists(UUID id){return characters.contains(id);}
    private static void characters(JsonWriter writer,Map<String,UUID> rows)throws IOException {
        writer.name("characters").beginObject();for(var e:rows.entrySet())writer.name(e.getKey()).value(e.getValue().toString());writer.endObject();
    }
    /** Same v6 schema as the previous writer. No whole-store JsonObject/String/byte array. */
    long write(Path path)throws IOException{return write(path,null,0);}
    long write(Path path,UUID journalId,long revision)throws IOException {
        String owner=Thread.currentThread().getName();if(owner.equals("world-tick")||owner.equals("native-game-owner"))throw new IOException("account_snapshot_owner_thread");
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            OutputStream bounded=new FilterOutputStream(Channels.newOutputStream(file)) {
                long count;
                private void admit(int length)throws IOException{count=Math.addExact(count,length);}
                @Override public void write(int value)throws IOException{admit(1);out.write(value);}
                @Override public void write(byte[] data,int at,int length)throws IOException{admit(length);out.write(data,at,length);}
            };
            try(JsonWriter writer=new JsonWriter(new BufferedWriter(new OutputStreamWriter(bounded,StandardCharsets.UTF_8),16384))){
                writer.setSerializeNulls(true);writer.setHtmlSafe(false);writer.beginObject().name("version").value(6);if(journalId!=null)writer.name("accountJournalId").value(journalId.toString()).name("accountJournalRevision").value(revision);
                writer.name("accounts").beginArray();
                for(Account row:accounts.values()){writer.beginObject().name("id").value(row.id.toString()).name("tokenHash").value(row.tokenHash);characters(writer,row.characters);writer.endObject();}writer.endArray();
                writer.name("profiles").beginArray();
                for(Profile row:profiles.values()){writer.beginObject().name("login").value(row.login).name("accountId").value(row.accountId.toString()).name("session").value(row.session).name("legal").value(row.legal);characters(writer,row.characters);writer.endObject();}writer.endArray();
                writer.name("tokens").beginArray();
                for(Token row:tokens){writer.beginObject().name("token").value(row.token).name("login").value(row.login).name("name").value(row.name).name("expires").value(row.expires).name("worldHost").value(row.worldHost).endObject();}writer.endArray();
                writer.name("tickets").beginArray();
                for(Ticket row:tickets){var grant=row.grant;writer.beginObject().name("protocol").value(grant.protocolId().toString()).name("character").value(grant.characterId().toString()).name("name").value(grant.name()).name("session").value(row.session).name("expires").value(row.expires).name("worldHost").value(grant.worldHost());if(row.tokenDigest!=null)writer.name("login").value(row.login).name("tokenDigest").value(row.tokenDigest);writer.endObject();}writer.endArray();
                writer.name("pendingCharacterDeletes").beginArray();for(UUID id:pendingDeletions)writer.value(id.toString());writer.endArray();
                writer.endObject();writer.flush();file.force(true);return file.position();
            }
        }
    }
}
