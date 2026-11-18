package szserver.net;

import java.util.*;

/** Exact row intervals for catalogue knowledge; dense windows do not retain one boxed key per cell. */
final class ChunkCoverage {
    private static final class Row {final int z;final TreeMap<Integer,Integer> spans=new TreeMap<>();Row previous,next;Row(int z){this.z=z;}}
    private final Map<Integer,Row> rows=new HashMap<>();
    private Row first,last,prune,scanRow;private Integer after,scanX;private long count;
    boolean contains(long key){Row row=rows.get((int)key);if(row==null)return false;var span=row.spans.floorEntry((int)(key>>32));return span!=null&&span.getValue()>=(int)(key>>32);}
    boolean add(long key){
        int x=(int)(key>>32),z=(int)key;Row row=rows.get(z);if(row==null){row=new Row(z);row.previous=last;if(last==null)first=row;else last.next=row;last=row;rows.put(z,row);}
        var before=row.spans.floorEntry(x);if(before!=null&&before.getValue()>=x)return false;
        var next=row.spans.ceilingEntry(x);boolean left=before!=null&&(long)before.getValue()+1==x,right=next!=null&&(long)x+1==next.getKey();
        if(left){int end=right?next.getValue():x;row.spans.put(before.getKey(),end);if(right)row.spans.remove(next.getKey());}
        else if(right){int end=next.getValue();row.spans.remove(next.getKey());row.spans.put(x,end);}else row.spans.put(x,x);count++;return true;
    }
    void remove(long key){
        int x=(int)(key>>32);Row row=rows.get((int)key);if(row==null)return;var span=row.spans.floorEntry(x);if(span==null||span.getValue()<x)return;
        int from=span.getKey(),to=span.getValue();row.spans.remove(from);if(from<x)row.spans.put(from,x-1);if(x<to)row.spans.put(x+1,to);count--;if(row.spans.isEmpty())unlink(row);
    }
    private void unlink(Row row){
        Row next=row.next;if(row.previous==null)first=next;else row.previous.next=next;if(next==null)last=row.previous;else next.previous=row.previous;rows.remove(row.z);
        if(prune==row){prune=next==null?first:next;after=null;}
        if(scanRow==row){scanRow=next==null?first:next;scanX=null;}
    }
    /** Every step inspects only one interval. Movement never scans the whole loaded window. */
    void trim(ChunkWindow window,int budget){
        if(prune==null)prune=first;
        for(int n=0;n<budget&&prune!=null;n++){
            Row row=prune;var span=after==null?row.spans.firstEntry():row.spans.higherEntry(after);
            if(span==null){prune=row.next==null?first:row.next;after=null;continue;}
            int from=span.getKey(),to=span.getValue();after=from;int[] keep=window.intersections(row.z,from,to);
            if(keep.length==2&&keep[0]==from&&keep[1]==to)continue;
            row.spans.remove(from);count-=(long)to-from+1;
            for(int at=0;at<keep.length;at+=2){row.spans.put(keep[at],keep[at+1]);count+=(long)keep[at+1]-keep[at]+1;}
            if(row.spans.isEmpty())unlink(row);
        }
    }
    long size(){return count;}
    int rows(){return rows.size();}
    /** One covered coordinate per call; mutation-safe rotation without expanding dense intervals. */
    Long scan(){
        if(first==null)return null;if(scanRow==null){scanRow=first;scanX=null;}
        for(int step=0;step<2;step++){
            var span=scanX==null?scanRow.spans.firstEntry():scanRow.spans.floorEntry(scanX);
            if(scanX==null){scanX=span.getKey();return szserver.world.BlockOverlays.key(scanX,scanRow.z);}
            if(span!=null&&span.getValue()>scanX){scanX++;return szserver.world.BlockOverlays.key(scanX,scanRow.z);}
            span=scanRow.spans.higherEntry(scanX);if(span!=null){scanX=span.getKey();return szserver.world.BlockOverlays.key(scanX,scanRow.z);}
            scanRow=scanRow.next==null?first:scanRow.next;scanX=null;
        }
        throw new IllegalStateException("coverage_empty_row");
    }
    void clear(){rows.clear();first=last=prune=scanRow=null;after=scanX=null;count=0;}
}
