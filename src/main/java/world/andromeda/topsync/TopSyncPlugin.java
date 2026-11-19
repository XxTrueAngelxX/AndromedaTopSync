package world.andromeda.topsync;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Owns presentation only. AJ remains the sole collector and persistent source of statistics. */
public final class TopSyncPlugin extends JavaPlugin {
    private final Publication publication=new Publication();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("top-sync-io").factory());
    private AjAdapter aj;
    private DisplayAdapter displays;
    private Skins skins;
    private BukkitTask ticker;
    private boolean busy;
    private long nextRun,lastWarning;
    private int interval,timeout,signCount;
    private String failure="нет";
    private Set<String> reversed=Set.of();
    private Map<String,String> units=Map.of();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();getConfig().options().copyDefaults(true);saveConfig();
            interval=Math.max(20,getConfig().getInt("refresh-ticks",100));
            timeout=Math.clamp(getConfig().getInt("prepare-timeout-seconds",15),1,60);
            var manager=getServer().getPluginManager();
            var source=Objects.requireNonNull(manager.getPlugin("ajLeaderboards"));
            aj=new AjAdapter(source);
            displays=new DisplayAdapter(Objects.requireNonNull(manager.getPlugin("DecentHolograms")));
            skins=new Skins(worker,Math.clamp(getConfig().getInt("skin-cache-seconds",600),30,3600),timeout,manager.getPlugin("SkinsRestorer"));
            ticker=Bukkit.getScheduler().runTaskTimer(this,this::tick,20,5);
            getLogger().info("Presentation coordinator ready; ajLeaderboards 2.11.0 / DecentHolograms 2.10.2");
        } catch(Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE,"Cannot safely coordinate installed plugins",e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    private void tick() {
        try {
            if(!aj.pauseNative() || busy || System.currentTimeMillis()<nextRun)return;
            nextRun=System.currentTimeMillis()+interval*50L;
            // Read only public configuration values; never persist AJ configuration or cache.
            var config=YamlConfiguration.loadConfiguration(new File(getServer().getPluginManager().getPlugin("ajLeaderboards").getDataFolder(),"config.yml"));
            reversed=Set.copyOf(config.getStringList("reverse-sort"));
            Map<String,String> names=new HashMap<>();
            for(String raw:config.getStringList("value-names")){int split=raw.indexOf('%');if(split>0)names.put(raw.substring(0,split),raw.substring(split+1).split("%",2)[0]);}units=Map.copyOf(names);
            Set<String> known=aj.knownBoards();
            var signs=aj.signs().stream().filter(s->known.contains(s.key().board()) && s.position()>0 && s.position()<=100).toList();
            var targets=displays.discover(Set.of("*"),known);
            Set<Frame.Key> keys=new LinkedHashSet<>();signs.forEach(s->keys.add(s.key()));targets.forEach(t->keys.addAll(t.keys()));
            if(keys.isEmpty())return;
            long ticket=publication.begin();busy=true;Set<String> sort=reversed;
            CompletableFuture.supplyAsync(()->aj.fetch(ticket,keys,sort),worker).orTimeout(timeout,TimeUnit.SECONDS)
                    .whenComplete((frame,error)->onMain(()->{
                        if(error!=null){failed(error);return;}
                        try {
                            var bindings=WorldTargets.capture(signs,frame,aj,units);
                            skins.prepare(bindings).whenComplete((profiles,skinError)->onMain(()->{
                              if(skinError!=null){failed(skinError);return;}
                              displays.exclusive(this::onMain,()->{
                                try {
                                    if(!signs.equals(signsWithUnknownRemoved(known)))throw new IllegalStateException("AJ displays changed during preparation");
                                    var changes=WorldTargets.changes(bindings,profiles);
                                    targets.forEach(t->changes.add(displays.change(t,frame)));
                                    if(publication.commit(ticket,frame,changes)){skins.remember(profiles);signCount=bindings.size();failure="нет";}
                                    busy=false;
                                }catch(Exception e){failed(e);}
                              },this::failed);
                            }));
                        }catch(Exception e){failed(e);}
                    }));
        }catch(Exception e){failed(e);}
    }
    private List<AjAdapter.SignRef> signsWithUnknownRemoved(Set<String> known) {
        return aj.signs().stream().filter(s->known.contains(s.key().board()) && s.position()>0 && s.position()<=100).toList();
    }
    private void onMain(Runnable action) {
        if(!isEnabled())return;
        try { Bukkit.getScheduler().runTask(this,action); }catch(org.bukkit.plugin.IllegalPluginAccessException ignored){ /* stopping */ }
    }
    private void failed(Throwable error) {
        busy=false;Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
        failure=cause.getClass().getSimpleName()+": "+cause.getMessage();
        if(System.currentTimeMillis()-lastWarning>30_000){getLogger().log(java.util.logging.Level.WARNING,"Keeping previous complete leaderboard frame",error);lastWarning=System.currentTimeMillis();}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("andromeda.topsync.admin")){sender.sendMessage("Недостаточно прав.");return true;}
        if(args.length==1 && args[0].equalsIgnoreCase("refresh")){nextRun=0;sender.sendMessage("Запрошен новый согласованный снимок рейтингов.");return true;}
        Frame current=publication.current();sender.sendMessage("TopSync 1.0.0: snapshot="+(current==null?"ожидание":current.generation())+", boards="+(current==null?0:current.boards().size())+", signs="+signCount+", preparing="+busy+", error="+failure);return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        return sender.hasPermission("andromeda.topsync.admin") && args.length==1?List.of("status","refresh").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList():List.of();
    }
    @Override public void onDisable() {
        publication.invalidate();if(ticker!=null)ticker.cancel();worker.shutdownNow();if(displays!=null)displays.releaseGates();
        if(displays!=null)try{displays.restore();}catch(Exception e){getLogger().warning("Could not restore display templates: "+e.getMessage());}
        if(aj!=null)try{aj.restoreNative();}catch(Exception e){getLogger().warning("Could not restore native display timer: "+e.getMessage());}
    }
}
