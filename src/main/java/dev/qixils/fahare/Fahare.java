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
import org.bukkit.scoreboard.*;
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
    private String[] unicodeArrows = { "\u2193", "\u2190", "\u2191",  "\u2192" };
    private double arrowWindow = Math.PI * 0.25;
    private String[] worldNames = { "fworld", "fworld2" };
    private int worldIndex = 0;

    private static final Random RANDOM = new Random();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private List<World> worlds;
    private Path worldContainer;
    private boolean resetting = false;
    private long worldStart;
    
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
        worldStart = System.currentTimeMillis();

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

        var manager = Bukkit.getScoreboardManager();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long totalSeconds = (System.currentTimeMillis() - worldStart) / 1000;
            long seconds = totalSeconds % 60;
            long minutes = totalSeconds / 60;

            for (Player player : Bukkit.getOnlinePlayers()) {
                var playerLoc = player.getLocation();
                var playerDir = playerLoc.getDirection();
                var playerWorld = player.getWorld();
                var board = manager.getNewScoreboard();
                var objective = board.registerNewObjective("timer", "timer");

                playerDir.setY(0);
                playerDir.normalize();
                objective.setDisplayName("Timer - " + minutes + ":" + (seconds < 10 ? "0" + seconds : seconds));
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                var playerDirRight = playerDir.getCrossProduct(new org.bukkit.util.Vector(0, 1, 0));

                addScoreboardWaypoint(
                    objective, 
                    player.getLocation(), 
                    playerWorld.getSpawnLocation(), 
                    "Spawn", playerDir, playerDirRight);

                for (Player other : playerWorld.getPlayers())
                    if (player != other)
                        addScoreboardWaypoint(
                            objective,
                            player.getLocation(), 
                            other.getLocation(), 
                            other.getName(), playerDir, playerDirRight);
                        
                player.setScoreboard(board);
            }
        }, 1, 5);
    }

    private void addScoreboardWaypoint(
        Objective objective, 
        Location playerLoc, 
        Location otherLoc, 
        String name, 
        org.bukkit.util.Vector playerDir, 
        org.bukkit.util.Vector playerDirRight) 
    {
        var dir = new org.bukkit.util.Vector(
            playerLoc.getX() - otherLoc.getX(), 0, 
            playerLoc.getZ() - otherLoc.getZ());
        dir.normalize();

        var signAngle = playerDir.angle(dir);
        var signAngleRight = playerDirRight.angle(dir);
        var angleIndex = 0;

        if (Math.PI - signAngle <= arrowWindow)
            angleIndex = 2;
        else if (signAngleRight <= arrowWindow)
            angleIndex = 1;
        else if (Math.PI - signAngleRight <= arrowWindow)
            angleIndex = 3;

        var dispName = ChatColor.RED + name + " " + unicodeArrows[angleIndex];
        objective.getScore(dispName).setScore((int)otherLoc.distance(playerLoc));
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

        getLogger().info("reset");

        deaths.clear();
        resetting = true;
        worldIndex = worldIndex == 0 ? 1 : 0;
        worldStart = System.currentTimeMillis();
        worlds = Bukkit.getWorlds().stream().filter(w -> !w.getName()
            .equals("world") && !w.getName().equals(worldNames[worldIndex])).collect(Collectors.toList());

        Location spawn = foverworld().getSpawnLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0);
            player.setHealth(20);
            player.teleport(spawn);
            player.setFoodLevel(20);
            player.setSaturation(5);
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

        for (Player player : players)
            if (isAlive(player.getUniqueId()))
                return;

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
