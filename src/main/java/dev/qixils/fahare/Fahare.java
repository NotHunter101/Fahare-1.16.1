package dev.qixils.fahare;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import cloud.commandframework.Command;
import cloud.commandframework.bukkit.CloudBukkitCapabilities;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.minecraft.extras.MinecraftExceptionHandler;
import cloud.commandframework.paper.PaperCommandManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.util.UTF8ResourceBundleControl;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static net.kyori.adventure.text.Component.text;

public final class Fahare extends JavaPlugin implements Listener {
    private String[] worldNames = { "fworld", "fworld2" };
    private int worldIndex = 0;

    private static final Random RANDOM = new Random();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private List<World> worlds;
    private Path worldContainer;
    private World limboWorld;
    private boolean resetting = false;
    
    private boolean autoReset = true;
    private boolean anyDeath = false;
    private int lives = 1;

    private @NotNull World createFakeOverworld() {
        long seed = RANDOM.nextLong();
        WorldCreator creator = new WorldCreator(worldNames[worldIndex]).copy(overworld()).seed(seed);
        World world = Objects.requireNonNull(creator.createWorld(), "Could not load fake overworld");
        world.setDifficulty(overworld().getDifficulty());

        return world;
    }

    private @NotNull World overworld() {
        return Objects.requireNonNull(Bukkit.getWorld("world"), "Overworld not found");
    }
    
    private @NotNull World foverworld() {
        return Objects.requireNonNullElseGet(Bukkit.getWorld(worldNames[worldIndex]), this::createFakeOverworld);
    }

    @Override
    public void onEnable() {
        loadConfig();

        worldContainer = Bukkit.getWorldContainer().toPath();
        limboWorld = new WorldCreator("limbo")
                .type(WorldType.FLAT)
                .generatorSettings("{\"structures\":{\"structures\":{}},\"layers\":[{\"block\":\"air\",\"height\":1}],\"biome\":\"plains\"}")
                .createWorld();

        try {
            final PaperCommandManager<CommandSender> commandManager = PaperCommandManager.createNative(this, CommandExecutionCoordinator.simpleCoordinator());
            if (commandManager.hasCapability(CloudBukkitCapabilities.BRIGADIER)) {
                try {
                    commandManager.registerBrigadier();
                } catch (Exception ignored) {
                }
            }

            Command.Builder<CommandSender> cmd = commandManager.commandBuilder("fahare");
            commandManager.command(cmd
                    .literal("reset")
                    .permission("fahare.reset")
                    .handler(c -> {
                        reset();
                    }));

        } catch (Exception e) {
            getLogger().info(e.toString());
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Location destination = foverworld().getSpawnLocation();
            for (Player player : overworld().getPlayers())
                player.teleport(destination);
        }, 1, 1);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            getLogger().info("hello");

            

        }, 1, 20);
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        var config = getConfig();
        autoReset = config.getBoolean("auto-reset", autoReset);
        anyDeath = config.getBoolean("any-death", anyDeath);
        lives = Math.max(1, config.getInt("lives", lives));
    }

    public int getDeathsFor(UUID player) {
        return deaths.getOrDefault(player, 0);
    }

    public void addDeathTo(UUID player) {
        deaths.put(player, getDeathsFor(player)+1);
    }

    public boolean isDead(UUID player) {
        return getDeathsFor(player) >= lives;
    }

    public boolean isAlive(UUID player) {
        return !isDead(player);
    }

    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        if (!resetting)
            return;

        if (worlds.isEmpty()) {
            resetting = false;
            return;
        }

        World world = worlds.remove(0);
        String worldName = world.getName();
        WorldCreator creator = new WorldCreator(worldName);
        long seed = RANDOM.nextLong();
        creator.copy(world).seed(seed);

        if (Bukkit.unloadWorld(world, false)) {
            try {
                IOUtils.deleteDirectory(worldContainer.resolve(worldName));
                creator.createWorld();
                getLogger().info("New world");
            } catch (Exception e) {
                getLogger().info(e.toString());
            }
        }
    }

    public synchronized void reset() {
        if (resetting)
            return;

        Location destination = new Location(limboWorld, 0, 0, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0);
            player.setHealth(20);
            player.teleport(destination);
            player.setFoodLevel(20);
            player.setSaturation(5);
        }

        deaths.clear();
        resetting = true;
        worldIndex = worldIndex == 0 ? 1 : 0;
        worlds = Bukkit.getWorlds().stream().filter(w -> !w.getName().equals("limbo") && 
            !w.getName().equals("world") && !w.getName().equals(worldNames[worldIndex])).collect(Collectors.toList());

        Location spawn = foverworld().getSpawnLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            player.teleport(spawn);
        }
    }

    public void resetCheck(boolean death) {
        if (!autoReset)
            return;
        if (anyDeath && death) {
            reset();
            return;
        }
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty())
            return;
        for (Player player : players) {
            if (isAlive(player.getUniqueId()))
                return;
        }
        reset();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        var player = event.getPlayer();
        var playerLoc = player.getLocation();
        var cause = event.getCause();
        var environment = player.getWorld().getEnvironment();

        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            var inNether = environment.equals(World.Environment.NETHER);
            event.setTo(new Location(inNether ? foverworld() : Bukkit.getWorld("world_nether"), 
                inNether ? playerLoc.getX() * 8.0f : playerLoc.getX() / 8.0f,
                inNether ? playerLoc.getY() * 8.0f : playerLoc.getY() / 8.0f,
                inNether ? playerLoc.getZ() * 8.0f : playerLoc.getZ() / 8.0f));
        } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            var inEnd = environment.equals(World.Environment.THE_END);
            event.setTo(inEnd ? foverworld().getSpawnLocation() : 
                new Location(Bukkit.getWorld("world_the_end"), 100, 50, 0));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        addDeathTo(player.getUniqueId());
        if (isAlive(player.getUniqueId()))
            return;
            
        Bukkit.getScheduler().runTaskLater(this, () -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.spigot().respawn();
            resetCheck(true);
        }, 1);
    }
}
