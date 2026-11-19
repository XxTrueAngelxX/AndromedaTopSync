package world.andromeda.topsync;

import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Constructor;
import java.util.*;

final class AjAdapter {
    record SignRef(Frame.Key key,int position,Location location) { }
    private final Plugin plugin;
    private final Object manager,cache,messages,serializer;
    private final Class<?> typeClass;
    private final Constructor<?> entryConstructor;
    private Object pausedTask;

    AjAdapter(Plugin plugin) throws ReflectiveOperationException {
        this.plugin=plugin;
        if(!plugin.getPluginMeta().getVersion().equals("2.11.0"))throw new IllegalStateException("Supported ajLeaderboards: 2.11.0");
        manager=Reflect.call(plugin,"getSignManager");cache=Reflect.call(plugin,"getCache");messages=Reflect.call(plugin,"getMessages");
        // Upstream has no public pause API. Version-gated access is limited to this scheduler handle.
        manager.getClass().getDeclaredField("updateInterval");
        ClassLoader loader=plugin.getClass().getClassLoader();
        typeClass=Reflect.load(loader,"us.ajg0702.leaderboards.boards.TimedType");
        Class<?> entry=Reflect.load(loader,"us.ajg0702.leaderboards.boards.StatEntry");
        entryConstructor=entry.getConstructor(int.class,String.class,String.class,String.class,String.class,UUID.class,String.class,double.class,typeClass);
        Class<?> legacy=Reflect.load(loader,"us.ajg0702.leaderboards.libs.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
        Object builder=Reflect.call(legacy,"builder");Reflect.call(builder,"hexColors");Reflect.call(builder,"useUnusualXRepeatedCharacterHexFormat");serializer=Reflect.call(builder,"build");
    }
    boolean pauseNative() {
        Object task=Reflect.field(manager,"updateInterval");
        if(task==null)return false;
        if(task!=pausedTask){Reflect.call(task,"cancel");pausedTask=task;}
        return true;
    }
    @SuppressWarnings("unchecked") List<SignRef> signs() {
        List<SignRef> result=new ArrayList<>();
        for(Object sign:(List<Object>)Reflect.call(manager,"getSigns")) {
            if(Boolean.TRUE.equals(Reflect.call(sign,"isRemoved")))continue;
            result.add(new SignRef(new Frame.Key((String)Reflect.call(sign,"getBoard"),Reflect.call(sign,"getType").toString()),
                    (int)Reflect.call(sign,"getPosition"),((Location)Reflect.call(sign,"getLocation")).clone()));
        }
        return List.copyOf(result);
    }
    @SuppressWarnings("unchecked") Set<String> knownBoards() {
        return Set.copyOf((List<String>)Reflect.call(Reflect.call(plugin,"getTopManager"),"getBoards"));
    }
    @SuppressWarnings("unchecked") Frame fetch(long generation,Set<Frame.Key> keys,Set<String> reversed) {
        Map<String,List<Object>> fetched=new HashMap<>();Map<Frame.Key,List<Frame.Row>> result=new LinkedHashMap<>();
        for(Frame.Key key:keys) {
            List<Object> rows=fetched.computeIfAbsent(key.board(),board->(List<Object>)Reflect.call(cache,"getRows",board));
            if(rows.size()>100_000)throw new IllegalStateException("Board too large: "+key.board());
            Object type=Reflect.call(typeClass,"valueOf",key.type());
            List<Object> sorted=new ArrayList<>(rows);
            java.util.function.ToDoubleFunction<Object> score=row->key.type().equals("ALLTIME")?(double)Reflect.call(row,"getValue"):
                    ((Map<Object,Double>)Reflect.call(row,"getDeltas")).getOrDefault(type,0.0);
            Comparator<Object> comparator=Comparator.comparingDouble(score);
            if(!reversed.contains(key.board()))comparator=comparator.reversed();
            comparator=comparator.thenComparing(row->Reflect.call(row,"getId").toString());sorted.sort(comparator);
            List<Frame.Row> output=new ArrayList<>();
            for(Object row:sorted.stream().limit(100).toList()) {
                int position=output.size()+1;double value=score.applyAsDouble(row);
                if(!Double.isFinite(value))throw new IllegalStateException("Non-finite score: "+key.board());
                UUID id=(UUID)Reflect.call(row,"getId");String name=string(Reflect.call(row,"getNamecache"));
                Object entry;
                try { entry=entryConstructor.newInstance(position,key.board(),string(Reflect.call(row,"getPrefixcache")),name,
                        string(Reflect.call(row,"getDisplaynamecache")),id,string(Reflect.call(row,"getSuffixcache")),value,type); }
                catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
                Map<String,String> fields=new HashMap<>();
                fields.put("name",name);fields.put("displayname",string(Reflect.call(entry,"getPlayerDisplayName")));
                fields.put("prefix",string(Reflect.call(entry,"getPrefix")));fields.put("suffix",string(Reflect.call(entry,"getSuffix")));
                fields.put("value",string(Reflect.call(entry,"getScorePretty")));fields.put("value_formatted",string(Reflect.call(entry,"getScoreFormatted")));
                fields.put("value_raw",Double.toString(value));fields.put("value_time",string(Reflect.call(entry,"getTime")));fields.put("position",Integer.toString(position));
                output.add(new Frame.Row(position,id,name,value,fields));
            }
            result.put(key,List.copyOf(output));
        }
        return new Frame(generation,result);
    }
    @SuppressWarnings("unchecked") List<String> signLines(Frame.Row row,Frame.Key key,String unit) {
        String[] tokens={"POSITION:"+row.position(),"NAME:"+row.name(),"DISPLAYNAME:"+row.field("displayname"),
                "VALUE:"+row.field("value"),"FVALUE:"+row.field("value_formatted"),"TVALUE:"+row.field("value_time"),
                "VALUENAME:"+java.util.regex.Matcher.quoteReplacement(unit),"TIMEDTYPE:"+key.type().toLowerCase(Locale.ROOT)};
        String path="signs.top."+key.board();if(!Boolean.TRUE.equals(Reflect.call(messages,"hasMessage",path)))path="signs.top.default";
        List<Object> components=(List<Object>)Reflect.call(messages,"getComponentList",path,tokens);
        List<String> lines=new ArrayList<>();for(Object c:components)lines.add((String)Reflect.call(serializer,"serialize",c));
        while(lines.size()<4)lines.add("");return List.copyOf(lines.subList(0,4));
    }
    void restoreNative() { if(pausedTask!=null && plugin.isEnabled() && !Bukkit.isStopping())Reflect.call(manager,"reload"); }
    private static String string(Object o){return Objects.toString(o,"");}
}
