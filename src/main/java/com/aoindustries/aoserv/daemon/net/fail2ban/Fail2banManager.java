/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.net.fail2ban;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.FirewallZone;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.collections.AoCollections;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.Strings;
import com.aoindustries.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
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
				AppProtocol.POP3,
				AppProtocol.IMAP2,
				AppProtocol.SIMAP,
				AppProtocol.SPOP3
			),
			true,
			null
		),
		SENDMAIL_AUTH(
			"sendmail-auth",
			getSet(
				AppProtocol.SMTP,
				AppProtocol.SMTPS,
				AppProtocol.SUBMISSION
			),
			true,
			null
		),
		SENDMAIL_DISCONNECT(
			"sendmail-disconnect",
			getSet(
				AppProtocol.SMTP,
				AppProtocol.SMTPS,
				AppProtocol.SUBMISSION
			),
			false,
			PackageManager.PackageName.FAIL2BAN_FILTER_SENDMAIL_DISCONNECT
		),
		SSHD(
			"sshd",
			getSet(AppProtocol.SSH),
			true,
			null
		);

		private final String name;
		private final Set<String> protocols;
		private final String jaildFilename;
		private final String removeOldJaildFilename;
		private final PackageManager.PackageName filterPackage;

		private Jail(
			String name,
			Set<String> protocols,
			String jaildFilename,
			String removeOldJaildFilename,
			PackageManager.PackageName filterPackage
		) {
			this.name = name;
			this.protocols = protocols;
			this.jaildFilename = jaildFilename;
			this.removeOldJaildFilename = removeOldJaildFilename;
			this.filterPackage = filterPackage;
		}

		private Jail(
			String name,
			Set<String> protocols,
			boolean removeOldJaildFile,
			PackageManager.PackageName filterPackage
		) {
			this(
				name,
				protocols,
				"50-" + name + ".local",
				removeOldJaildFile ? "50-" + name + ".conf" : null,
				filterPackage
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

		/**
		 * Used to remove old *.conf files that are now setup as *.local
		 */
		String getRemoveOldJaildFilename() {
			return removeOldJaildFilename;
		}

		/**
		 * Any package that needs to be installed to support this filter.
		 */
		PackageManager.PackageName getFilterPackage() {
			return filterPackage;
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			Host thisHost = thisServer.getHost();
			OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
			int osvId = osv.getPkey();

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
						boolean firewalldInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.FIREWALLD) != null;

						Jail[] jails = Jail.values();
						if(logger.isLoggable(Level.FINE)) logger.fine("jails: " + Arrays.asList(jails));

						List<Bind> netBinds = thisHost.getNetBinds();
						if(logger.isLoggable(Level.FINE)) logger.fine("netBinds: " + netBinds);

						// Resolves the unique ports for each supported jail
						Map<Jail, SortedSet<Integer>> jailPorts = new HashMap<>();
						for(Bind nb : netBinds) {
							InetAddress ip = nb.getIpAddress().getInetAddress();
							if(!ip.isLoopback()) {
								for(Jail jail : jails) {
									if(jail.getProtocols().contains(nb.getAppProtocol().getProtocol())) {
										// Must be part of at least one fail2ban firewalld zone
										boolean fail2ban;
										if(firewalldInstalled) {
											fail2ban = false;
											for(FirewallZone zone : nb.getFirewalldZones()) {
												if(zone.getFail2ban()) {
													fail2ban = true;
													break;
												}
											}
										} else {
											// Firewalld is not installed, fail2ban all ports
											fail2ban = true;
										}
										if(fail2ban) {
											SortedSet<Integer> ports = jailPorts.get(jail);
											if(ports == null) jailPorts.put(jail, ports = new TreeSet<>());
											ports.add(nb.getPort().getPort());
										}
									}
								}
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("jailPorts: " + jailPorts);

						boolean[] updated = {false};

						// Install any missing packages
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
							// Install package to handle secondary IMAP/POP3 services as-needed
							boolean hasSecondaryCyrusImapService = false;
							// Tracks which additional packages are required by the activated jails
							EnumSet<PackageManager.PackageName> allFilterPackages = EnumSet.noneOf(PackageManager.PackageName.class);
							EnumSet<PackageManager.PackageName> requiredPackages = EnumSet.noneOf(PackageManager.PackageName.class);
							ByteArrayOutputStream bout = new ByteArrayOutputStream();
							for(Jail jail : jails) {
								PackageManager.PackageName filterPackage = jail.getFilterPackage();
								if(filterPackage != null) allFilterPackages.add(filterPackage);
								UnixFile jailUF = new UnixFile(JAIL_D, jail.getJaildFilename(), true);
								SortedSet<Integer> ports = jailPorts.get(jail);
								if(ports == null) {
									if(jailUF.getStat().exists()) {
										jailUF.delete();
										updated[0] = true;
									}
								} else {
									// Install any required packages
									if(filterPackage != null && requiredPackages.add(filterPackage)) {
										PackageManager.installPackage(
											filterPackage,
											() -> updated[0] = true
										);
									}
									if(jail == Jail.CYRUS_IMAP) {
										hasSecondaryCyrusImapService = ImapManager.hasSecondaryService();
										if(hasSecondaryCyrusImapService) {
											PackageManager.installPackage(
												PackageManager.PackageName.FAIL2BAN_FILTER_CYRUS_IMAP_MORE_SERVICES,
												() -> updated[0] = true
											);
										}
									}
									bout.reset();
									try (ChainWriter out = new ChainWriter(bout)) {
										out.print("#\n");
										out.print("# Generated by ").print(Fail2banManager.class.getName()).print('\n');
										out.print("#\n");
										out.print('[').print(jail.getName()).print("]\n");
										out.print("enabled = true\n");
										out.print("port = ");
										assert !ports.isEmpty();
										Strings.join(ports, ",", out);
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
								// Remove any old file that was at *.conf and now moved to *.local
								String removeOldJaildFilename = jail.getRemoveOldJaildFilename();
								if(removeOldJaildFilename != null) {
									UnixFile oldJailUF = new UnixFile(JAIL_D, removeOldJaildFilename, true);
									if(oldJailUF.getStat().exists()) {
										oldJailUF.delete();
										updated[0] = true;
									}
								}
							}
							// Remove any filter packages that are no longer required
							if(AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
								for(PackageManager.PackageName filterPackage : allFilterPackages) {
									if(!requiredPackages.contains(filterPackage)) {
										if(PackageManager.removePackage(filterPackage)) {
											updated[0] = true;
										}
									}
								}
								// No secondary IMAP/POP3 services
								if(!hasSecondaryCyrusImapService && PackageManager.removePackage(PackageManager.PackageName.FAIL2BAN_FILTER_CYRUS_IMAP_MORE_SERVICES)) {
									updated[0] = true;
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
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
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
					conn.getNet().getFirewallZone().addTableListener(fail2banManager, 0);
					conn.getNet().getBind().addTableListener(fail2banManager, 0);
					conn.getNet().getBindFirewallZone().addTableListener(fail2banManager, 0);
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
