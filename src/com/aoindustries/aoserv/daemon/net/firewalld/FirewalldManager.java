/*
 * Copyright 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net.firewalld;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.FirewallZone;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.validator.FirewalldZoneName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.firewalld.Service;
import com.aoindustries.firewalld.ServiceSet;
import com.aoindustries.firewalld.Target;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.InetAddressPrefix;
import com.aoindustries.net.InetAddressPrefixes;
import com.aoindustries.net.Port;
import com.aoindustries.net.Protocol;
import com.aoindustries.util.Tuple2;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the configuration of firewalld.
 */
final public class FirewalldManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(FirewalldManager.class.getName());

	/**
	 * The zones for SSH, used as a fail-safe if SSH not added to any zone.
	 */
	private static final Set<FirewalldZoneName> sshFailsafeZones = Collections.unmodifiableSet(
		new LinkedHashSet<FirewalldZoneName>(
			Arrays.asList(
				FirewallZone.DMZ,
				FirewallZone.EXTERNAL,
				FirewallZone.HOME,
				FirewallZone.INTERNAL,
				FirewallZone.PUBLIC,
				FirewallZone.WORK
			)
		)
	);

	private static FirewalldManager firewalldManager;

	private FirewalldManager() {
	}

	/**
	 * Adds a target unless the net bind is on loopback device.
	 */
	private static void addTarget(Bind nb, Collection<Target> targets, Set<FirewalldZoneName> zones, List<Bind> firewalldNetBinds) throws SQLException, IOException, ValidationException {
		InetAddress ip = nb.getIpAddress().getInetAddress();
		// Assume can access self
		if(!ip.isLoopback()) {
			targets.add(
				new Target(
					InetAddressPrefix.valueOf(
						ip,
						ip.isUnspecified() ? 0 : ip.getAddressFamily().getMaxPrefix()
					),
					nb.getPort()
				)
			);
			zones.addAll(nb.getFirewalldZoneNames());
			firewalldNetBinds.add(nb);
		}
	}

	private static Set<String> toStringSet(Set<FirewalldZoneName> firewalldZones) {
		int size = firewalldZones.size();
		if(size == 0) {
			return Collections.emptySet();
		}
		if(size == 1) {
			return Collections.singleton(
				firewalldZones.iterator().next().toString()
			);
		}
		Set<String> strings = new LinkedHashSet<>(size*4/3+1);
		for(FirewalldZoneName firewalldZone : firewalldZones) {
			if(!strings.add(firewalldZone.toString())) throw new AssertionError();
		}
		return strings;
	}

	/**
	 * Warn net_bind exposed to more zones than expected.
	 */
	private static void warnZoneMismatch(Set<FirewalldZoneName> zones, List<Bind> firewalldNetBinds) throws IOException, SQLException {
		if(logger.isLoggable(Level.WARNING)) {
			for(Bind nb : firewalldNetBinds) {
				Set<FirewalldZoneName> expected = nb.getFirewalldZoneNames();
				if(!zones.equals(expected)) {
					logger.warning("Bind #" + nb.getPkey() + " (" + nb + ") opened on unexpected set of firewalld zones: expected=" + expected + ", zones=" + zones);
				}
			}
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			Server thisAoServer = AOServDaemon.getThisAOServer();
			Host thisServer = thisAoServer.getServer();
			OperatingSystemVersion osv = thisServer.getOperatingSystemVersion();
			int osvId = osv.getPkey();

			synchronized(rebuildLock) {
				if(
					osvId == OperatingSystemVersion.CENTOS_7_X86_64
					// Manage firewalld only when installed
					&& PackageManager.getInstalledPackage(PackageManager.PackageName.FIREWALLD) != null
				) {
					List<Bind> netBinds = thisServer.getNetBinds();
					if(logger.isLoggable(Level.FINE)) logger.fine("netBinds: " + netBinds);
					// TODO: The zones should be added per-port, but this release is constrained by the current implementation
					//       of the underlying ao-firewalld package.  Thus any single port associated with a zone will open that
					//       service on that zone for all ports.

					// Gather the set of firewalld zones per service name
					List<Tuple2<ServiceSet,Set<FirewalldZoneName>>> serviceSets = new ArrayList<>();
					// SSH
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.SSH)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("ssh targets: " + targets + ", zones: " + zones);
						ServiceSet serviceSet = ServiceSet.createOptimizedServiceSet("ssh", targets);
						// TODO: Include rate-limiting from public zone, as well as a zone for monitoring
						if(zones.isEmpty()) {
							if(logger.isLoggable(Level.WARNING)) logger.warning("ssh does not have any zones, using fail-safe zones: " + sshFailsafeZones);
							serviceSets.add(
								new Tuple2<>(
									serviceSet,
									sshFailsafeZones
								)
							);
						} else {
							warnZoneMismatch(zones, firewalldNetBinds);
							serviceSets.add(
								new Tuple2<>(
									serviceSet,
									zones
								)
							);
						}
					}
					// AOServ Daemon
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							String appProtocol = nb.getAppProtocol().getProtocol();
							if(
								appProtocol.equals(AppProtocol.AOSERV_DAEMON)
								|| appProtocol.equals(AppProtocol.AOSERV_DAEMON_SSL)
							) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("aoserv-daemon targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("aoserv-daemon", targets),
								zones
							)
						);
					}
					// AOServ Master
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							String appProtocol = nb.getAppProtocol().getProtocol();
							if(
								appProtocol.equals(AppProtocol.AOSERV_MASTER)
								|| appProtocol.equals(AppProtocol.AOSERV_MASTER_SSL)
							) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						// Only configure when either non-empty targets or system service exists
						if(logger.isLoggable(Level.FINE)) logger.fine("aoserv-master targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						Service template = Service.loadSystemService("aoserv-master");
						if(template != null) {
							serviceSets.add(
								new Tuple2<>(
									ServiceSet.createOptimizedServiceSet(template, targets),
									zones
								)
							);
						} else if(!targets.isEmpty()) {
							// Has net binds but no firewalld system service
							throw new SQLException("System service not found: aoserv-master");
						}
					}
					// DNS
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.DNS)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("named targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet(
									new Service(
										"named",
										null,
										"named",
										"Berkeley Internet Name Domain (DNS)",
										Arrays.asList(
											Port.valueOf(53, Protocol.TCP),
											Port.valueOf(53, Protocol.UDP)
										),
										Collections.emptySet(), // protocols
										Collections.emptySet(), // sourcePorts
										Collections.emptySet(), // modules
										InetAddressPrefixes.UNSPECIFIED_IPV4,
										InetAddressPrefixes.UNSPECIFIED_IPV6
									),
									targets
								),
								zones
							)
						);
					}
					// HTTP
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.HTTP)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("http targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("http", targets),
								zones
							)
						);
					}
					// HTTPS
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.HTTPS)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("https targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("https", targets),
								zones
							)
						);
					}
					// IMAP
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.IMAP2)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("imap targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("imap", targets),
								zones
							)
						);
					}
					// IMAPS
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.SIMAP)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("imaps targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("imaps", targets),
								zones
							)
						);
					}
					// Memcached
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.MEMCACHED)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("memcached targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						Service template = Service.loadSystemService("memcached");
						if(template != null) {
							serviceSets.add(
								new Tuple2<>(
									ServiceSet.createOptimizedServiceSet(template, targets),
									zones
								)
							);
						} else if(!targets.isEmpty()) {
							// Has net binds but no firewalld system service
							throw new SQLException("System service not found: memcached");
						}
					}
					// MySQL
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.MYSQL)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("mysql targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("mysql", targets),
								zones
							)
						);
					}
					// POP3
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.POP3)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("pop3 targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("pop3", targets),
								zones
							)
						);
					}
					// POP3S
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.SPOP3)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("pop3s targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("pop3s", targets),
								zones
							)
						);
					}
					// PostgreSQL
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.POSTGRESQL)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("postgresql targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("postgresql", targets),
								zones
							)
						);
					}
					// SMTP
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							String prot = nb.getAppProtocol().getProtocol();
							if(prot.equals(AppProtocol.SMTP)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("smtp targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("smtp", targets),
								zones
							)
						);
					}
					// SMTPS
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.SMTPS)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("smtps targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("smtps", targets),
								zones
							)
						);
					}
					// SUBMISSION
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							String prot = nb.getAppProtocol().getProtocol();
							if(prot.equals(AppProtocol.SUBMISSION)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("submission targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet(
									new Service(
										"submission",
										null,
										"submission",
										"Outgoing SMTP Mail",
										Collections.singletonList(
											Port.valueOf(587, Protocol.TCP)
										),
										Collections.emptySet(), // protocols
										Collections.emptySet(), // sourcePorts
										Collections.emptySet(), // modules
										InetAddressPrefixes.UNSPECIFIED_IPV4,
										InetAddressPrefixes.UNSPECIFIED_IPV6
									),
									targets
								),
								zones
							)
						);
					}
					// vnc-server
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewalldZoneName> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.RFB)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("vnc-server targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet("vnc-server", targets),
								zones
							)
						);
					}
					// Group service sets by unique set of targets
					Map<Set<FirewalldZoneName>,List<ServiceSet>> serviceSetsByZones = new LinkedHashMap<>();
					for(Tuple2<ServiceSet,Set<FirewalldZoneName>> tuple : serviceSets) {
						ServiceSet serviceSet = tuple.getElement1();
						Set<FirewalldZoneName> zones = tuple.getElement2();
						List<ServiceSet> list = serviceSetsByZones.get(zones);
						if(list == null) {
							list = new ArrayList<>();
							serviceSetsByZones.put(zones, list);
						}
						list.add(serviceSet);
					}
					// Commit all service sets
					for(Map.Entry<Set<FirewalldZoneName>,List<ServiceSet>> entry : serviceSetsByZones.entrySet()) {
						ServiceSet.commit(entry.getValue(), toStringSet(entry.getKey()));
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(FirewalldManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		synchronized(System.out) {
			if(
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(FirewalldManager.class)
				&& firewalldManager == null
			) {
				System.out.print("Starting FirewalldManager: ");
				// Must be a supported operating system
				if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					AOServConnector conn = AOServDaemon.getConnector();
					firewalldManager = new FirewalldManager();
					conn.getFirewalldZones().addTableListener(firewalldManager, 0);
					conn.getNetBinds().addTableListener(firewalldManager, 0);
					conn.getNetBindFirewalldZones().addTableListener(firewalldManager, 0);
					PackageManager.addPackageListener(firewalldManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild firewalld Configuration";
	}
}
