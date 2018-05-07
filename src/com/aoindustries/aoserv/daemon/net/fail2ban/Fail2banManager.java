/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net.fail2ban;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import com.aoindustries.util.AoCollections;
import com.aoindustries.util.StringUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the configuration of Fail2ban.
 */
final public class Fail2banManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(Fail2banManager.class.getName());

	private static Fail2banManager fail2banManager;

	private static final UnixFile JAIL_D = new UnixFile("/etc/fail2ban/jail.d");

	private Fail2banManager() {
	}

	@SuppressWarnings({"unchecked"})
	private static <T> Set<T> getSet(T ... values) {
		return AoCollections.optimalUnmodifiableSet(
			new HashSet<>(
				Arrays.asList(values)
			)
		);
	}

	private enum Jail {
		CYRUS_IMAP(
			"cyrus-imap",
			getSet(
				Protocol.POP3,
				Protocol.IMAP2,
				Protocol.SIMAP,
				Protocol.SPOP3
			)
		),
		SENDMAIL_AUTH(
			"sendmail-auth",
			getSet(
				Protocol.SMTP,
				Protocol.SMTPS,
				Protocol.SUBMISSION
			)
		),
		SSHD(
			"sshd",
			getSet(Protocol.SSH)
		);

		private final String name;
		private final String jaildFilename;
		private final Set<String> protocols;

		private Jail(
			String name,
			Set<String> protocols,
			String jaildFilename
		) {
			this.name = name;
			this.protocols = protocols;
			this.jaildFilename = jaildFilename;
		}

		private Jail(
			String name,
			Set<String> protocols
		) {
			this(
				name,
				protocols,
				"50-" + name + ".conf"
			);
		}

		String getName() {
			return name;
		}

		/**
		 * The protocol names associated with this jail.
		 *
		 * @see  Protocol
		 */
		Set<String> getProtocols() {
			return protocols;
		}

		String getJaildFilename() {
			return jaildFilename;
		}
	}
	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			Server thisServer = thisAoServer.getServer();
			OperatingSystemVersion osv = thisServer.getOperatingSystemVersion();
			int osvId = osv.getPkey();

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
						Jail[] jails = Jail.values();
						if(logger.isLoggable(Level.FINE)) logger.fine("jails: " + Arrays.asList(jails));

						List<NetBind> netBinds = thisServer.getNetBinds();
						if(logger.isLoggable(Level.FINE)) logger.fine("netBinds: " + netBinds);

						// Resolves the unique ports for each supported jail
						Map<Jail,SortedSet<Integer>> jailPorts = new HashMap<>();
						for(NetBind nb : netBinds) {
							InetAddress ip = nb.getIPAddress().getInetAddress();
							if(!ip.isLoopback()) {
								for(Jail jail : jails) {
									if(jail.getProtocols().contains(nb.getAppProtocol().getProtocol())) {
										SortedSet<Integer> ports = jailPorts.get(jail);
										if(ports == null) jailPorts.put(jail, ports = new TreeSet<>());
										ports.add(nb.getPort().getPort());
									}
								}
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("jailPorts: " + jailPorts);

						boolean[] updated = {false};

						// Install any missing packages
						boolean firewalldInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.FIREWALLD) != null;
						boolean fail2banInstalled;
						if(jailPorts.isEmpty()) {
							fail2banInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.FAIL2BAN_SERVER) != null;
						} else {
							PackageManager.installPackage(
								PackageManager.PackageName.FAIL2BAN_SERVER,
								() -> updated[0] = true
							);
							fail2banInstalled = true;
							if(firewalldInstalled) {
								PackageManager.installPackage(
									PackageManager.PackageName.FAIL2BAN_FIREWALLD,
									() -> updated[0] = true
								);
							}
						}

						// Update configuration files if installed
						if(fail2banInstalled) {
							ByteArrayOutputStream bout = new ByteArrayOutputStream();
							for(Jail jail : jails) {
								UnixFile jailUF = new UnixFile(JAIL_D, jail.getJaildFilename(), true);
								SortedSet<Integer> ports = jailPorts.get(jail);
								if(ports == null) {
									if(jailUF.getStat().exists()) {
										jailUF.delete();
										updated[0] = true;
									}
								} else {
									bout.reset();
									try (ChainWriter out = new ChainWriter(bout)) {
										out.print("#\n");
										out.print("# Generated by ").print(Fail2banManager.class.getName()).print('\n');
										out.print("#\n");
										out.print('[').print(jail.getName()).print("]\n");
										out.print("enabled = true\n");
										out.print("port = ");
										assert !ports.isEmpty();
										StringUtility.join(ports, ",", out);
										out.print('\n');
									}
									if(
										DaemonFileUtils.atomicWrite(
											jailUF,
											bout.toByteArray(),
											0644,
											UnixFile.ROOT_UID,
											UnixFile.ROOT_GID,
											null,
											restorecon
										)
									) {
										updated[0] = true;
									}
								}
							}
						}

						// SELinux before next steps
						DaemonFileUtils.restorecon(restorecon);
						restorecon.clear();

						if(jailPorts.isEmpty()) {
							if(fail2banInstalled) {
								// Installed but not needed: stop and disable
								AOServDaemon.exec("/usr/bin/systemctl", "stop", "fail2ban.service");
								AOServDaemon.exec("/usr/bin/systemctl", "disable", "fail2ban.service");
							}
						} else {
							assert fail2banInstalled;
							// Enable if needed
							AOServDaemon.exec("/usr/bin/systemctl", "enable", "fail2ban.service");
							if(updated[0]) {
								// Restart if configuration updated
								AOServDaemon.exec("/usr/bin/systemctl", "restart", "fail2ban.service");
							} else {
								// Start if not running
								AOServDaemon.exec("/usr/bin/systemctl", "start", "fail2ban.service");
							}
						}
					} else {
						throw new AssertionError("Unexpected OperatingSystemVersion: " + osv);
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(Fail2banManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		synchronized(System.out) {
			if(
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(Fail2banManager.class)
				&& fail2banManager == null
			) {
				System.out.print("Starting Fail2banManager: ");
				// Must be a supported operating system
				if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					AOServConnector conn = AOServDaemon.getConnector();
					fail2banManager = new Fail2banManager();
					conn.getFirewalldZones().addTableListener(fail2banManager, 0);
					conn.getNetBinds().addTableListener(fail2banManager, 0);
					conn.getNetBindFirewalldZones().addTableListener(fail2banManager, 0);
					PackageManager.addPackageListener(fail2banManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Fail2ban Configuration";
	}
}
