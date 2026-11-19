package world.andromeda.topsync;

import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class DisplayAdapter {
    record Target(Object display,List<String> template,Set<Frame.Key> keys) { }
    private final Object service,coordinator;
    private final Executor executor;
    private final Set<CountDownLatch> gates=ConcurrentHashMap.newKeySet();
    private final Map<Object,List<String>> originals=new IdentityHashMap<>();
    DisplayAdapter(Plugin plugin) {
        if(!plugin.getPluginMeta().getVersion().equals("2.10.2"))throw new IllegalStateException("Supported DecentHolograms: 2.10.2");
        Class<?> api=Reflect.load(plugin.getClass().getClassLoader(),"eu.decentsoftware.holograms.api.DecentHologramsAPI");
        Object module=Reflect.call(Reflect.call(api,"get"),"getDisplayModule");
        service=Reflect.call(module,"getDisplayService");
        executor=(Executor)Reflect.field(Reflect.field(module,"displayUpdateScheduler"),"executor");
        // Public updateDisplay only prepares logical state. Flush its coordinator in the same commit.
        coordinator=Reflect.field(service,"renderCoordinator");
    }
    @SuppressWarnings("unchecked") List<Target> discover(Set<String> configured,Set<String> known) {
        List<Target> result=new ArrayList<>();
        Collection<Object> registered=(Collection<Object>)Reflect.call(service,"getRegisteredDisplays");
        originals.keySet().removeIf(d->!registered.contains(d));
        for(Object display:registered) {
            if(!display.getClass().getSimpleName().equals("TextDisplay"))continue;
            List<String> lines=originals.getOrDefault(display,List.copyOf((List<String>)Reflect.call(display,"getLines")));
            Set<Frame.Key> keys=Frame.keys(lines);
            if(keys.isEmpty() || !Frame.supported(lines) || keys.stream().anyMatch(k->!known.contains(k.board()) || !allowed(configured,k.board())))continue;
            originals.putIfAbsent(display,lines);result.add(new Target(display,lines,keys));
        }
        return List.copyOf(result);
    }
    @SuppressWarnings("unchecked") Publication.Change change(Target target,Frame frame) {
        List<String> before=List.copyOf((List<String>)Reflect.call(target.display,"getLines"));
        List<String> after=frame.render(target.template);
        return new Publication.Change() {
            public void validate() { if(Reflect.call(service,"getDisplay",Reflect.call(target.display,"getName"))!=target.display)throw new IllegalStateException("Display replaced during preparation"); }
            public void apply(){Reflect.call(target.display,"setLines",after);flush(target.display);}
            public void rollback(){Reflect.call(target.display,"setLines",before);flush(target.display);}
        };
    }
    private void flush(Object display){Reflect.call(service,"updateDisplay",display);Reflect.call(coordinator,"postProcess",display);}
    // Queue behind upstream renders, then park that one executor while the main tick commits.
    // The main thread never waits for the display executor; a delayed commit expires without writes.
    void exclusive(java.util.function.Consumer<Runnable> main,Runnable action,java.util.function.Consumer<Throwable> failure) {
        executor.execute(()->{
            CountDownLatch release=new CountDownLatch(1);AtomicInteger state=new AtomicInteger();gates.add(release);
            try {
                main.accept(()->{if(!state.compareAndSet(0,1))return;try{action.run();}finally{state.set(2);release.countDown();}});
                if(!release.await(5,TimeUnit.SECONDS) && state.compareAndSet(0,2))main.accept(()->failure.accept(new TimeoutException("Display commit missed deadline")));
                // Once the main tick has started, release only after its commit/rollback completes.
                if(state.get()==1)release.await();
            }catch(InterruptedException e){Thread.currentThread().interrupt();state.compareAndSet(0,2);}
            finally{gates.remove(release);}
        });
    }
    void releaseGates(){gates.forEach(CountDownLatch::countDown);}
    void restore() { for(var e:originals.entrySet()) { Reflect.call(e.getKey(),"setLines",e.getValue());flush(e.getKey()); } originals.clear(); }
    static boolean allowed(Set<String> configured,String board){return configured.contains("*")||configured.contains(board);}
}
