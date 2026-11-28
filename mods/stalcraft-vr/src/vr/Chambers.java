package vr;
/** Each physical chamber owns its live round or spent case independently. */
final class Chambers {
    private int count,live,spent;
    void configure(int count){this.count=count;live=spent=0;}
    int count(){return count;}
    int live(){return live;}
    int spent(){return spent;}
    int wire(){return live|(spent<<2);}
    boolean empty(int slot){return slot>=0&&slot<count&&((live|spent)&(1<<slot))==0;}
    void receive(int state){int mask=(1<<count)-1;live=state&mask;spent=(state>>2)&mask&~live;}
    void shot(){int bit=Integer.lowestOneBit(live);live&=~bit;spent|=bit;}
    void clearSpent(){spent=0;}
}
