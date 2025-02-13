package com.lishid.openinv.internal.common.player;

import com.lishid.openinv.internal.ISpecialInventory;
import com.lishid.openinv.internal.common.container.OpenEnderChest;
import com.lishid.openinv.internal.common.container.OpenInventory;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Dynamic;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.logging.Logger;

public class PlayerManager implements com.lishid.openinv.internal.PlayerManager {

  protected final @NotNull Logger logger;
  private @Nullable Field bukkitEntity;

  public PlayerManager(@NotNull Logger logger) {
    this.logger = logger;
    try {
      bukkitEntity = Entity.class.getDeclaredField("bukkitEntity");
    } catch (NoSuchFieldException e) {
      logger.warning("Unable to obtain field to inject custom save process - certain player data may be lost when saving!");
      logger.log(java.util.logging.Level.WARNING, e.getMessage(), e);
      bukkitEntity = null;
    }
  }

  public static @NotNull ServerPlayer getHandle(final Player player) {
    if (player instanceof CraftPlayer) {
      return ((CraftPlayer) player).getHandle();
    }

    Server server = player.getServer();
    ServerPlayer nmsPlayer = null;

    if (server instanceof CraftServer) {
      nmsPlayer = ((CraftServer) server).getHandle().getPlayer(player.getUniqueId());
    }

    if (nmsPlayer == null) {
      // Could use reflection to examine fields, but it's honestly not worth the bother.
      throw new RuntimeException("Unable to fetch EntityPlayer from Player implementation " + player.getClass().getName());
    }

    return nmsPlayer;
  }

  @Override
  public @Nullable Player loadPlayer(@NotNull final OfflinePlayer offline) {
    MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
    ServerLevel worldServer = server.getLevel(Level.OVERWORLD);

    if (worldServer == null) {
      return null;
    }

    // Create a new ServerPlayer.
    ServerPlayer entity = createNewPlayer(server, worldServer, offline);

    // Stop listening for advancement progression - if this is not cleaned up, loading causes a memory leak.
    entity.getAdvancements().stopListening();

    // Try to load the player's data.
    if (loadData(entity)) {
      // If data is loaded successfully, return the Bukkit entity.
      return entity.getBukkitEntity();
    }

    return null;
  }

  protected @NotNull ServerPlayer createNewPlayer(
      @NotNull MinecraftServer server,
      @NotNull ServerLevel worldServer,
      @NotNull final OfflinePlayer offline) {
    // See net.minecraft.server.players.PlayerList#canPlayerLogin(ServerLoginPacketListenerImpl, GameProfile)
    // See net.minecraft.server.network.ServerLoginPacketListenerImpl#handleHello(ServerboundHelloPacket)
    GameProfile profile = new GameProfile(offline.getUniqueId(),
        offline.getName() != null ? offline.getName() : offline.getUniqueId().toString());

    ClientInformation dummyInfo = new ClientInformation(
        "en_us",
        1, // Reduce distance just in case.
        ChatVisiblity.HIDDEN, // Don't accept chat.
        false,
        ServerPlayer.DEFAULT_MODEL_CUSTOMIZATION,
        ServerPlayer.DEFAULT_MAIN_HAND,
        true,
        false, // Don't list in player list (not that this player is in the list anyway).
        ParticleStatus.MINIMAL
    );

    ServerPlayer entity = new ServerPlayer(server, worldServer, profile, dummyInfo);

    try {
      injectPlayer(entity);
    } catch (IllegalAccessException e) {
      logger.log(
          java.util.logging.Level.WARNING,
          e,
          () -> "Unable to inject ServerPlayer, certain player data may be lost when saving!");
    }

    return entity;
  }

  boolean loadData(@NotNull ServerPlayer player) {
    // See CraftPlayer#loadData
    CompoundTag loadedData = player.server.getPlayerList().playerIo.load(player).orElse(null);

    if (loadedData == null) {
      // Exceptions with loading are logged by Mojang.
      return false;
    }

    // Read basic data into the player.
    player.load(loadedData);
    // Also read "extra" data.
    player.readAdditionalSaveData(loadedData);
    // Game type settings are also loaded separately.
    player.loadGameTypes(loadedData);

    // World is not loaded by ServerPlayer#load(CompoundTag) on Paper.
    parseWorld(player, loadedData);

    return true;
  }

  private void parseWorld(@NotNull ServerPlayer player, @NotNull CompoundTag loadedData) {
    // See PlayerList#placeNewPlayer
    World bukkitWorld;
    if (loadedData.contains("WorldUUIDMost") && loadedData.contains("WorldUUIDLeast")) {
      // Modern Bukkit world.
      bukkitWorld = Bukkit.getServer().getWorld(new UUID(loadedData.getLong("WorldUUIDMost"), loadedData.getLong("WorldUUIDLeast")));
    } else if (loadedData.contains("world", net.minecraft.nbt.Tag.TAG_STRING)) {
      // Legacy Bukkit world.
      bukkitWorld = Bukkit.getServer().getWorld(loadedData.getString("world"));
    } else {
      // Vanilla player data.
      DimensionType.parseLegacy(new Dynamic<>(NbtOps.INSTANCE, loadedData.get("Dimension")))
          .resultOrPartial(logger::warning)
          .map(player.server::getLevel)
          // If ServerLevel exists, set, otherwise move to spawn.
          .ifPresentOrElse(player::setServerLevel, () -> spawnInDefaultWorld(player));
      return;
    }
    if (bukkitWorld == null) {
      spawnInDefaultWorld(player);
      return;
    }
    player.setServerLevel(((CraftWorld) bukkitWorld).getHandle());
  }

  private void spawnInDefaultWorld(ServerPlayer player) {
    ServerLevel level = player.server.getLevel(Level.OVERWORLD);
    if (level != null) {
      player.spawnIn(level);
    } else {
      logger.warning("Tried to load player with invalid world when no fallback was available!");
    }
  }

  protected void injectPlayer(ServerPlayer player) throws IllegalAccessException {
    if (bukkitEntity == null) {
      return;
    }

    bukkitEntity.setAccessible(true);

    bukkitEntity.set(player, new OpenPlayer(player.server.server, player, this));
  }

  @Override
  public @NotNull Player inject(@NotNull Player player) {
    try {
      ServerPlayer nmsPlayer = getHandle(player);
      if (nmsPlayer.getBukkitEntity() instanceof OpenPlayer openPlayer) {
        return openPlayer;
      }
      injectPlayer(nmsPlayer);
      return nmsPlayer.getBukkitEntity();
    } catch (IllegalAccessException e) {
      logger.log(
          java.util.logging.Level.WARNING,
          e,
          () -> "Unable to inject ServerPlayer, certain player data may be lost when saving!");
      return player;
    }
  }

  @Override
  public @Nullable InventoryView openInventory(@NotNull Player bukkitPlayer, @NotNull ISpecialInventory inventory, boolean viewOnly) {
    ServerPlayer player = getHandle(bukkitPlayer);

    if (!OpenPlayer.isConnected(player.connection)) {
      return null;
    }

    // See net.minecraft.server.level.ServerPlayer#openMenu(MenuProvider)
    AbstractContainerMenu menu;
    Component title;
    if (inventory instanceof OpenInventory playerInv) {
      menu = playerInv.createMenu(player, player.nextContainerCounter(), viewOnly);
      title = playerInv.getTitle(player);
    } else if (inventory instanceof OpenEnderChest enderChest) {
      menu = enderChest.createMenu(player, player.nextContainerCounter(), viewOnly);
      title = enderChest.getTitle();
    } else {
      return null;
    }

    // Should never happen, player is a ServerPlayer with an active connection.
    if (menu == null) {
      return null;
    }

    // Set up title. Title can only be set once for a menu, and is set during the open process.
    // Further title changes are a hack where the client is sent a "new" inventory with the same ID,
    // resulting in a title change but no other state modifications (like cursor position).
    menu.setTitle(title);

    var pair = CraftEventFactory.callInventoryOpenEventWithTitle(player, menu);
    menu = pair.getSecond();

    // Menu is null if event is cancelled.
    if (menu == null) {
      return null;
    }

    var newTitle = pair.getFirst();
    if (newTitle != null) {
      title = PaperAdventure.asVanilla(newTitle);
    }

    player.containerMenu = menu;
    player.connection.send(new ClientboundOpenScreenPacket(menu.containerId, menu.getType(), title));
    player.initMenu(menu);

    return menu.getBukkitView();
  }

}
