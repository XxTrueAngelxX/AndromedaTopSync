package world.andromeda.topsync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

final class Skins {
    private record Cached(PlayerProfile profile,long expires) { }
    private final Map<UUID,Cached> cache=new HashMap<>();
    private final Executor io;
    private final int ttl,timeout;
    private final Object storage,skinStorage;
    private final Class<?> propertyUtils;

    Skins(Executor io,int ttl,int timeout,Plugin sr) {
        this.io=io;this.ttl=ttl;this.timeout=timeout;
        if(sr==null){storage=null;skinStorage=null;propertyUtils=null;return;}
        ClassLoader loader=sr.getClass().getClassLoader();
        Object api=Reflect.call(Reflect.load(loader,"net.skinsrestorer.api.SkinsRestorerProvider"),"get");
        storage=Reflect.call(api,"getPlayerStorage");skinStorage=Reflect.call(api,"getSkinStorage");
        propertyUtils=Reflect.load(loader,"net.skinsrestorer.api.PropertyUtils");
    }
    CompletableFuture<Map<UUID,PlayerProfile>> prepare(List<WorldTargets.Binding> targets) {
        long now=System.currentTimeMillis();cache.entrySet().removeIf(e->e.getValue().expires<now);
        Map<UUID,PlayerProfile> existing=WorldTargets.existingProfiles(targets);Map<UUID,Frame.Row> needs=new LinkedHashMap<>();
        for(var b:targets)if(b.row().id()!=null && (!b.skulls().isEmpty() || !b.stands().isEmpty()))needs.put(b.row().id(),b.row());
        Map<UUID,CompletableFuture<PlayerProfile>> futures=new LinkedHashMap<>();
        for(var e:needs.entrySet()) {
            UUID id=e.getKey();PlayerProfile ready=existing.get(id);Cached cached=cache.get(id);
            if(ready==null && cached!=null)ready=cached.profile;
            if(ready==null) { PlayerProfile offline=Bukkit.getOfflinePlayer(id).getPlayerProfile();if(!offline.getTextures().isEmpty())ready=offline; }
            if(ready!=null){futures.put(id,CompletableFuture.completedFuture(ready));continue;}
            PlayerProfile base=Bukkit.createPlayerProfile(id,e.getValue().name());
            CompletableFuture<PlayerProfile> prepared=CompletableFuture.supplyAsync(()->fromStoredSkin(base),io)
                    .thenCompose(p->p==null?base.update().thenApply(updated->(PlayerProfile)updated):CompletableFuture.completedFuture(p))
                    .orTimeout(timeout,TimeUnit.SECONDS).thenApply(p->{if(p.getTextures().isEmpty())throw new IllegalStateException("Skin not ready for leader");return p;});
            futures.put(id,prepared);
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenApply(ignored->{
            Map<UUID,PlayerProfile> result=new HashMap<>();futures.forEach((id,f)->result.put(id,f.join()));return Map.copyOf(result);
        });
    }
    @SuppressWarnings("unchecked") private PlayerProfile fromStoredSkin(PlayerProfile profile) {
        if(storage==null)return null;
        Optional<Object> id=(Optional<Object>)Reflect.call(storage,"getSkinIdOfPlayer",profile.getUniqueId());if(id.isEmpty())return null;
        Optional<Object> property=(Optional<Object>)Reflect.call(skinStorage,"getSkinDataByIdentifier",id.get());if(property.isEmpty())return null;
        String url=(String)Reflect.call(propertyUtils,"getSkinTextureUrl",property.get());
        URI uri=URI.create(url);
        if(!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())||!uri.getPath().matches("/texture/[0-9a-fA-F]+"))throw new IllegalStateException("Unexpected skin texture URL");
        try {
            PlayerProfile copy=profile.clone();PlayerTextures textures=copy.getTextures();
            String model=Reflect.call(propertyUtils,"getSkinVariant",property.get()).toString();
            textures.setSkin(URI.create("https://textures.minecraft.net"+uri.getPath()).toURL(),model.equalsIgnoreCase("SLIM")?PlayerTextures.SkinModel.SLIM:PlayerTextures.SkinModel.CLASSIC);
            copy.setTextures(textures);return copy;
        }catch(java.net.MalformedURLException e){throw new IllegalStateException(e);}
    }
    void remember(Map<UUID,PlayerProfile> profiles){long expires=System.currentTimeMillis()+ttl*1000L;profiles.forEach((id,p)->cache.put(id,new Cached(p,expires)));}
}
