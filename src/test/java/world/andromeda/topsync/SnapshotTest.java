package world.andromeda.topsync;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {
    private final Frame.Key key=new Frame.Key("statistic_jump","ALLTIME");
    private Frame frame(long generation,String name,String score) {
        return new Frame(generation,Map.of(key,List.of(new Frame.Row(1,UUID.randomUUID(),name,Double.parseDouble(score),Map.of("name",name,"value",score)))));
    }
    @Test void wrappedAndDirectPlaceholdersShareOneImmutableGeneration() {
        var lines=List.of("#1 %javascript_ajlbfallback_{ajlb_lb_statistic_jump_1_alltime_name}%", "%ajlb_lb_statistic_jump_1_alltime_value% / %statistic_jump%");
        assertEquals(Set.of(key),Frame.keys(lines));
        assertEquals(List.of("#1 NewLeader","42 / %statistic_jump%"),frame(2,"NewLeader","42").render(lines));
    }
    @Test void replacementValuesCannotBecomeRegexReplacementInstructions() {
        assertEquals(List.of("$name\\suffix"),frame(1,"$name\\suffix","1").render(List.of("%ajlb_lb_statistic_jump_1_alltime_name%")));
    }
    @Test void unsupportedFieldsAndPositionsRejectWholeDisplay() {
        assertFalse(Frame.supported(List.of("%ajlb_lb_statistic_jump_1_alltime_unknown%")));
        assertFalse(Frame.supported(List.of("%ajlb_lb_statistic_jump_101_alltime_name%")));
        assertTrue(Frame.supported(List.of("%ajlb_lb_statistic_jump_10_alltime_value_formatted%")));
    }
    @Test void snapshotCopiesInputCollections() {
        var rows=new ArrayList<>(frame(1,"A","1").boards().get(key));var source=new HashMap<Frame.Key,List<Frame.Row>>();source.put(key,rows);
        Frame snapshot=new Frame(1,source);rows.clear();source.clear();assertEquals("A",snapshot.boards().get(key).getFirst().name());
        assertThrows(UnsupportedOperationException.class,()->snapshot.boards().clear());
    }
    @Test void stalePreparationsNeverWrite() {
        Publication p=new Publication();long old=p.begin();long current=p.begin();List<String> events=new ArrayList<>();
        var c=change(events,"write",false);assertFalse(p.commit(old,frame(1,"A","1"),List.of(c)));assertTrue(events.isEmpty());
        assertTrue(p.commit(current,frame(2,"B","2"),List.of(c)));assertEquals(2,p.current().generation());
        p.invalidate();assertFalse(p.commit(current,frame(3,"C","3"),List.of(c)));
    }
    @Test void rollbackIncludesPartiallyAppliedChangeAndRetainsPreviousFrame() {
        Publication p=new Publication();Frame previous=frame(1,"A","1");p.commit(p.begin(),previous,List.of());
        List<String> events=new ArrayList<>();long ticket=p.begin();
        assertThrows(IllegalStateException.class,()->p.commit(ticket,frame(2,"B","2"),List.of(change(events,"sign",false),change(events,"text",true))));
        assertEquals(List.of("validate sign","validate text","apply sign","apply text","rollback text","rollback sign"),events);
        assertSame(previous,p.current());
    }
    @Test void validatesAllTargetsBeforeAnyWrite() {
        Publication p=new Publication();List<String> events=new ArrayList<>();
        Publication.Change invalid=new Publication.Change(){public void validate(){throw new IllegalStateException();}public void apply(){fail();}public void rollback(){fail();}};
        assertThrows(IllegalStateException.class,()->p.commit(p.begin(),frame(1,"A","1"),List.of(change(events,"sign",false),invalid)));
        assertEquals(List.of("validate sign"),events);assertNull(p.current());
    }
    @Test void reflectionPassesTokenArrayAsOneArgument() {
        assertEquals("path:a,b",Reflect.call(new Example(),"list","path",new String[]{"a","b"}));
    }
    public static class Example {public String list(String path,String... tokens){return path+":"+String.join(",",tokens);}}
    private Publication.Change change(List<String> events,String name,boolean fail) {
        return new Publication.Change(){public void validate(){events.add("validate "+name);}public void apply(){events.add("apply "+name);if(fail)throw new IllegalStateException("failure");}public void rollback(){events.add("rollback "+name);}};
    }
}
