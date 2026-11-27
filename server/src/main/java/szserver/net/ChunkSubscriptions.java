package szserver.net;

import java.util.*;

/** Transport-owned loaded chunks with a mutation-safe rotating scan; no whole-window tick walks. */
public final class ChunkSubscriptions extends AbstractSet<Long> {
    private static final class Node {final long key;Node previous,next;Node(long key){this.key=key;}}
    private final Map<Long,Node> nodes=new HashMap<>();private Node first,last;private final Node[] cursors=new Node[2];
    @Override public int size(){return nodes.size();}
    @Override public boolean contains(Object key){return nodes.containsKey(key);}
    List<Long> around(int x,int z,int radius){
        List<Long> result=new ArrayList<>();
        for(var points=ChunkWindow.coordinates(x,z,radius);points.hasNext();){
            long key=points.next();
            if(nodes.containsKey(key))result.add(key);
        }
        return result;
    }
    @Override public boolean add(Long key){if(nodes.containsKey(key))return false;Node n=new Node(key);n.previous=last;if(last==null)first=n;else last.next=n;last=n;nodes.put(key,n);return true;}
    @Override public boolean remove(Object key){Node n=nodes.remove(key);if(n==null)return false;if(n.previous==null)first=n.next;else n.previous.next=n.next;if(n.next==null)last=n.previous;else n.next.previous=n.previous;for(int i=0;i<cursors.length;i++)if(cursors[i]==n)cursors[i]=n.next==null?first:n.next;return true;}
    public Long scan(){return scan(0);}
    public Long scan(int lane){if(first==null)return null;if(cursors[lane]==null)cursors[lane]=first;Node n=cursors[lane];cursors[lane]=n.next==null?first:n.next;return n.key;}
    @Override public void clear(){nodes.clear();first=last=null;Arrays.fill(cursors,null);}
    @Override public Iterator<Long> iterator(){return new Iterator<>(){Node next=first;Long previous;public boolean hasNext(){return next!=null;}public Long next(){if(next==null)throw new NoSuchElementException();Node n=next;next=n.next;previous=n.key;return n.key;}public void remove(){if(previous==null)throw new IllegalStateException();ChunkSubscriptions.this.remove(previous);previous=null;}};}
}
