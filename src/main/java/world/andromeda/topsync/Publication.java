package world.andromeda.topsync;

import java.util.*;

/** Validate every target first; stale preparations cannot publish after stop/restart. */
final class Publication {
    interface Change { void validate(); void apply(); void rollback(); }
    private long epoch;
    private Frame current;
    long begin() { return ++epoch; }
    void invalidate() { ++epoch; }
    Frame current() { return current; }
    boolean commit(long ticket,Frame frame,List<Change> changes) {
        if(ticket!=epoch)return false;
        changes.forEach(Change::validate);
        List<Change> applied=new ArrayList<>();
        try { for(Change change:changes) { applied.add(change);change.apply(); } }
        catch(RuntimeException failure) {
            Collections.reverse(applied);
            for(Change change:applied)try { change.rollback(); } catch(RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        current=frame;return true;
    }
}
