package skinsrestorer.bukkit;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import skinsrestorer.bukkit.commands.SkinCommand;
import skinsrestorer.bukkit.commands.SrCommand;
import skinsrestorer.bukkit.packet.PacketListener;
import skinsrestorer.bukkit.packet.PacketListener17;
import skinsrestorer.bukkit.skinfactory.SkinFactory;
import skinsrestorer.bukkit.skinfactory.UniversalSkinFactory;
import skinsrestorer.shared.storage.Config;
import skinsrestorer.shared.storage.CooldownStorage;
import skinsrestorer.shared.storage.Locale;
import skinsrestorer.shared.storage.SkinStorage;
import skinsrestorer.shared.utils.C;
import skinsrestorer.shared.utils.MojangAPI;
import skinsrestorer.shared.utils.MojangAPI.SkinRequestException;
import skinsrestorer.shared.utils.MySQL;
import skinsrestorer.shared.utils.ReflectionUtil;

public class SkinsRestorer extends JavaPlugin {

	private static SkinsRestorer instance;
	private SkinFactory factory;
	private MySQL mysql;
	private boolean bungeeEnabled;
	private boolean outdated;
	private boolean deprecated;

	@Override
	public void onEnable() {
		instance = this;
		final ConsoleCommandSender console = Bukkit.getConsoleSender();
		deprecated = ReflectionUtil.serverVersion.contains("1_7");

		if (deprecated)
			for (Player p : Bukkit.getOnlinePlayers())
				PacketListener17.inject(p);
		else
			for (Player p : Bukkit.getOnlinePlayers())
				PacketListener.inject(p);

		try {
			Class.forName("net.minecraftforge.cauldron.CauldronHooks");
			console.sendMessage(C.c("&aSkinsRestorer doesn't support Cauldron, Thermos or KCauldron, Sorry :("));
			Bukkit.getPluginManager().disablePlugin(this);
			return;
		} catch (Exception e) {
			try {
				factory = (SkinFactory) Class
						.forName("skinsrestorer.bukkit.skinfactory.SkinFactory_" + ReflectionUtil.serverVersion)
						.newInstance();
			} catch (Exception ex) {
				factory = new UniversalSkinFactory();
			}
		}
		console.sendMessage(C.c("&aDetected Minecraft &e" + ReflectionUtil.serverVersion + "&a, using &e"
				+ factory.getClass().getSimpleName()));

		try {
			bungeeEnabled = YamlConfiguration.loadConfiguration(new File("spigot.yml"))
					.getBoolean("settings.bungeecord");
		} catch (Exception e) {
			bungeeEnabled = false;
		}

		if (bungeeEnabled) {

			Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
			Bukkit.getMessenger().registerIncomingPluginChannel(this, "SkinsRestorer", new PluginMessageListener() {
				@Override
				public void onPluginMessageReceived(String channel, final Player player, final byte[] message) {
					if (!channel.equals("SkinsRestorer"))
						return;

					Bukkit.getScheduler().runTaskAsynchronously(getInstance(), new Runnable() {

						@Override
						public void run() {

							DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));

							try {
								String subchannel = in.readUTF();

								if (subchannel.equalsIgnoreCase("SkinUpdate")) {
									try {
										Object textures = SkinStorage.createProperty(in.readUTF(), in.readUTF(),
												in.readUTF());

										Object ep = ReflectionUtil.invokeMethod(player.getClass(), player, "getHandle");
										Object profile = ReflectionUtil.invokeMethod(ep.getClass(), ep, "getProfile");
										Object propmap = ReflectionUtil.invokeMethod(profile.getClass(), profile,
												"getProperties");

										factory.applySkin(player, textures, propmap);
									} catch (Exception e) {
									}
									factory.updateSkin(player);
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					});
				}
			});

			Bukkit.getPluginManager().registerEvents(new Listener() {

				@EventHandler(priority = EventPriority.LOWEST)
				public void onJoin(PlayerJoinEvent e) {
					if (ReflectionUtil.serverVersion.contains("1_7"))
						PacketListener17.inject(e.getPlayer());
					else
						PacketListener.inject(e.getPlayer());

				}

			}, this);

			if (Config.UPDATER_ENABLED) {
				if (checkVersion().equals(getVersion())) {
					outdated = false;
					console.sendMessage("");
					console.sendMessage(ChatColor.GREEN + "    +===============+");
					console.sendMessage(ChatColor.GREEN + "    | SkinsRestorer |");
					console.sendMessage(ChatColor.GREEN + "    |---------------|");
					console.sendMessage(ChatColor.GREEN + "    |  Bungee Mode  |");
					console.sendMessage(ChatColor.GREEN + "    +===============+");
					console.sendMessage("");
					console.sendMessage(ChatColor.AQUA + "    Current version: " + ChatColor.GREEN + getVersion());
					console.sendMessage(ChatColor.GREEN + "    The latest version!");
					console.sendMessage("");
				} else {
					outdated = true;
					console.sendMessage("");
					console.sendMessage(ChatColor.GREEN + "    +===============+");
					console.sendMessage(ChatColor.GREEN + "    | SkinsRestorer |");
					console.sendMessage(ChatColor.GREEN + "    |---------------|");
					console.sendMessage(ChatColor.GREEN + "    |  Bungee Mode  |");
					console.sendMessage(ChatColor.GREEN + "    +===============+");
					console.sendMessage("");
					console.sendMessage(ChatColor.AQUA + "    Current version: " + ChatColor.RED + getVersion());
					console.sendMessage(ChatColor.RED + "    A new version is available! Download it at:");
					console.sendMessage(
							ChatColor.YELLOW + "    https://www.spigotmc.org/resources/skinsrestorer.2124/");
					console.sendMessage("");
				}
			}
			return;
		}

		Config.load(getResource("config.yml"));
		Locale.load();

		if (Config.USE_MYSQL)
			SkinStorage.init(mysql = new MySQL(Config.MYSQL_HOST, Config.MYSQL_PORT, Config.MYSQL_DATABASE,
					Config.MYSQL_USERNAME, Config.MYSQL_PASSWORD));
		else
			SkinStorage.init(getDataFolder());

		Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new CooldownStorage(), 0, 1 * 20);

		getCommand("skinsrestorer").setExecutor(new SrCommand());
		getCommand("skin").setExecutor(new SkinCommand());

		Bukkit.getPluginManager().registerEvents(new Listener() {
			@EventHandler(priority = EventPriority.HIGHEST)
			public void onJoin(final PlayerJoinEvent e) {
				final Player p = e.getPlayer();

				if (deprecated)
					PacketListener17.inject(p);
				else
					PacketListener.inject(p);

				if (Config.UPDATER_ENABLED && SkinsRestorer.getInstance().isOutdated()
						&& (p.isOp() || p.hasPermission("skinsrestorer.cmds")))
					p.sendMessage(C.c(Locale.OUTDATED));
			}
		}, this);

		Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {

				if (Config.UPDATER_ENABLED) {
					if (checkVersion().equals(getVersion())) {
						outdated = false;
						console.sendMessage("");
						console.sendMessage(ChatColor.GREEN + "    +===============+");
						console.sendMessage(ChatColor.GREEN + "    | SkinsRestorer |");
						console.sendMessage(ChatColor.GREEN + "    +===============+");
						console.sendMessage("");
						console.sendMessage(ChatColor.AQUA + "    Current version: " + ChatColor.RED + getVersion());
						console.sendMessage(ChatColor.GREEN + "    The latest version!");
						console.sendMessage("");
					} else {
						outdated = true;
						console.sendMessage("");
						console.sendMessage(ChatColor.GREEN + "    +===============+");
						console.sendMessage(ChatColor.GREEN + "    | SkinsRestorer |");
						console.sendMessage(ChatColor.GREEN + "    +===============+");
						console.sendMessage("");
						console.sendMessage(ChatColor.AQUA + "    Current version: " + ChatColor.RED + getVersion());
						console.sendMessage(ChatColor.RED + "    A new version is available! Download it at:");
						console.sendMessage(
								ChatColor.YELLOW + "    https://www.spigotmc.org/resources/skinsrestorer.2124/");
						console.sendMessage("");
					}
				}

				if (Config.DEFAULT_SKINS_ENABLED)
					for (String skin : Config.DEFAULT_SKINS) {
						try {
							SkinStorage.setSkinData(skin, MojangAPI.getSkinProperty(skin, MojangAPI.getUUID(skin)));
						} catch (SkinRequestException e) {
							if (SkinStorage.getSkinData(skin) == null)
								console.sendMessage(
										ChatColor.RED + "Default Skin '" + skin + "' request error: " + e.getReason());
						}
					}

				// For my testing, do not touch
				/*
				 * try { AuthSession as = MojangAuthAPI.authenticate("username",
				 * "password");
				 * 
				 * System.out.println(MojangAuthAPI.uploadSkin(as.getId(),
				 * as.getAuthToken(), true)); } catch (Exception e) { }
				 */
			}

		});

	}

	@Override
	public void onDisable() {
		if (deprecated)
			for (Player p : Bukkit.getOnlinePlayers())
				PacketListener17.uninject(p);
		else
			for (Player p : Bukkit.getOnlinePlayers())
				PacketListener.uninject(p);

	}

	public static SkinsRestorer getInstance() {
		return instance;
	}

	public boolean isDeprecated() {
		return outdated;
	}

	public boolean isOutdated() {
		return outdated;
	}

	public String checkVersion() {
		try {
			HttpURLConnection con = (HttpURLConnection) new URL("http://www.spigotmc.org/api/general.php")
					.openConnection();
			con.setDoOutput(true);
			con.setRequestMethod("POST");
			con.getOutputStream()
					.write(("key=98BE0FE67F88AB82B4C197FAF1DC3B69206EFDCC4D3B80FC83A00037510B99B4&resource=2124")
							.getBytes("UTF-8"));
			String version = new BufferedReader(new InputStreamReader(con.getInputStream())).readLine();
			if (version.length() <= 13) {
				return version;
			}
		} catch (Exception ex) {
			System.out.println("Failed to check for an update on spigot.");
		}
		return getVersion();
	}

	public SkinFactory getFactory() {
		return factory;
	}

	public String getVersion() {
		return getDescription().getVersion();
	}

	public MySQL getMySQL() {
		return mysql;
	}

	public boolean downloadUpdate() {
		try {
			InputStream in = new URL("http://api.spiget.org/v1/resources/2124/download").openStream();

			System.out.println(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());

			Path target = new File(getClass().getProtectionDomain().getCodeSource().getLocation().getPath()).toPath();

			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);

			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

}
