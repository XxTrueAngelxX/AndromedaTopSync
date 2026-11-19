package world.andromeda.topsync;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.*;

final class WorldTargets {
    record Binding(AjAdapter.SignRef ref,Frame.Row row,List<String> text,List<Location> skulls,List<ArmorStand> stands) { }
    private static final LegacyComponentSerializer LEGACY=LegacyComponentSerializer.legacySection();
    static List<Binding> capture(List<AjAdapter.SignRef> signs,Frame frame,AjAdapter aj,Map<String,String> units) {
        List<Binding> result=new ArrayList<>();Map<String,UUID> claimed=new HashMap<>();
        for(var ref:signs) {
            if(!frame.boards().containsKey(ref.key()) || !loaded(ref.location()))continue;
            if(!(ref.location().getBlock().getState() instanceof Sign))continue;
            List<Frame.Row> rows=frame.boards().get(ref.key());
            Frame.Row row=ref.position()>0 && ref.position()<=rows.size()?rows.get(ref.position()-1):empty(ref.position());
            List<Location> skulls=new ArrayList<>();Set<ArmorStand> stands=new LinkedHashSet<>();
            for(Location loc:neighbours(ref.location())) {
                if(!loaded(loc))continue;
                Material material=loc.getBlock().getType();
                if(material==Material.PLAYER_HEAD || material==Material.PLAYER_WALL_HEAD)skulls.add(loc);
                for(var entity:loc.getWorld().getNearbyEntities(loc.clone().add(.5,.5,.5),.49,3,.49))
                    if(entity instanceof ArmorStand stand && stand.getLocation().getBlockX()==loc.getBlockX() && stand.getLocation().getBlockZ()==loc.getBlockZ())stands.add(stand);
            }
            if(stands.size()>1)throw new IllegalStateException("Ambiguous armor stands near "+ref.location());
            for(Location loc:skulls)claim(claimed,"block:"+loc,row.id());
            for(ArmorStand stand:stands)claim(claimed,"stand:"+stand.getUniqueId(),row.id());
            result.add(new Binding(ref,row,aj.signLines(row,ref.key(),units.getOrDefault(ref.key().board(),"")),List.copyOf(skulls),List.copyOf(stands)));
        }
        return List.copyOf(result);
    }
    private static void claim(Map<String,UUID> map,String key,UUID owner) {
        if(map.containsKey(key) && !Objects.equals(map.get(key),owner))throw new IllegalStateException("Conflicting display ownership: "+key);
        map.put(key,owner);
    }
    static Frame.Row empty(int position) {
        return new Frame.Row(position,null,"???",0,Map.of("name","???","displayname","???","value","???","value_formatted","???","value_time","???"));
    }
    static boolean loaded(Location loc){return loc.getWorld()!=null && loc.getWorld().isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4);}
    private static List<Location> neighbours(Location sign) {
        var data=sign.getBlock().getBlockData();BlockFace face=BlockFace.SELF;
        if(data instanceof org.bukkit.block.data.type.WallSign wall)face=wall.getFacing();
        else if(data instanceof org.bukkit.block.data.type.Sign standing)face=standing.getRotation();
        String f=face.name();int dx=0,dz=0;
        if(f.contains("NORTH"))dz=1;else if(f.contains("SOUTH"))dz=-1;else if(f.contains("EAST"))dx=-1;else if(f.contains("WEST"))dx=1;
        if(dx==0 && dz==0)return List.of();
        List<Location> result=new ArrayList<>();for(int offset=0;offset<2;offset++)for(int y=0;y<2;y++)result.add(sign.clone().add(dx*offset,y,dz*offset));return result;
    }
    static Map<UUID,PlayerProfile> existingProfiles(List<Binding> bindings) {
        Map<UUID,PlayerProfile> result=new HashMap<>();
        for(Binding b:bindings) {
            if(b.row.id()==null)continue;
            for(Location loc:b.skulls)if(loc.getBlock().getState() instanceof Skull skull)keep(result,b.row.id(),skull.getOwnerProfile());
            for(ArmorStand stand:b.stands) { ItemStack item=stand.getEquipment().getHelmet();if(item!=null && item.getItemMeta() instanceof SkullMeta meta)keep(result,b.row.id(),meta.getOwnerProfile()); }
        }
        return result;
    }
    private static void keep(Map<UUID,PlayerProfile> out,UUID id,PlayerProfile profile) {
        if(profile!=null && id.equals(profile.getUniqueId()) && !profile.getTextures().isEmpty())out.put(id,profile);
    }
    static List<Publication.Change> changes(List<Binding> bindings,Map<UUID,PlayerProfile> profiles) {
        List<Publication.Change> changes=new ArrayList<>();
        for(Binding b:bindings) {
            Location loc=b.ref.location();BlockState oldSign=loc.getBlock().getState();Material material=loc.getBlock().getType();
            changes.add(new Publication.Change() {
                public void validate(){if(!loaded(loc)||loc.getBlock().getType()!=material)throw new IllegalStateException("Sign changed while preparing");}
                public void apply(){Sign sign=(Sign)loc.getBlock().getState();for(int i=0;i<4;i++)sign.getSide(Side.FRONT).line(i,LEGACY.deserialize(b.text.get(i)));if(!sign.update(false,false))throw new IllegalStateException("Sign update rejected");}
                public void rollback(){oldSign.update(false,false);}
            });
            PlayerProfile profile=b.row.id()==null || (b.skulls.isEmpty() && b.stands.isEmpty())?null:Objects.requireNonNull(profiles.get(b.row.id()),"Unprepared skin");
            for(Location skullLoc:b.skulls) {
                BlockState previous=skullLoc.getBlock().getState();Material type=previous.getType();
                changes.add(new Publication.Change() {
                    public void validate(){if(!loaded(skullLoc)||skullLoc.getBlock().getType()!=type)throw new IllegalStateException("Head changed while preparing");}
                    public void apply(){Skull skull=(Skull)skullLoc.getBlock().getState();skull.setOwnerProfile(profile);if(!skull.update(false,false))throw new IllegalStateException("Skull update rejected");}
                    public void rollback(){previous.update(false,false);}
                });
            }
            for(ArmorStand stand:b.stands) {
                ItemStack before=stand.getEquipment().getHelmet();before=before==null?null:before.clone();ItemStack saved=before;
                ItemStack next=new ItemStack(profile==null?Material.AIR:Material.PLAYER_HEAD);
                if(profile!=null){SkullMeta meta=(SkullMeta)next.getItemMeta();meta.setOwnerProfile(profile);next.setItemMeta(meta);}
                changes.add(new Publication.Change(){
                    public void validate(){if(!stand.isValid())throw new IllegalStateException("Armor stand removed while preparing");}
                    public void apply(){stand.getEquipment().setHelmet(next);}
                    public void rollback(){if(stand.isValid())stand.getEquipment().setHelmet(saved);}
                });
            }
        }
        return changes;
    }
}
