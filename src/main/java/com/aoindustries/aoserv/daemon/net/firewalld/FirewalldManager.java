/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.net.firewalld;

import com.aoapps.collections.AoCollections;
import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.lang.validation.ValidationException;
import com.aoapps.net.InetAddress;
import com.aoapps.net.InetAddressPrefix;
import com.aoapps.net.InetAddressPrefixes;
import com.aoapps.net.Port;
import com.aoapps.net.Protocol;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.FirewallZone;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.firewalld.Service;
import com.aoindustries.firewalld.ServiceSet;
import com.aoindustries.firewalld.Target;
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
public final class FirewalldManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(FirewalldManager.class.getName());

	/**
	 * The zones for SSH, used as a fail-safe if SSH not added to any zone.
	 */
	private static final Set<FirewallZone.Name> sshFailsafeZones = Collections.unmodifiableSet(
		new LinkedHashSet<>(
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
	@SuppressWarnings("deprecation")
	private static void addTarget(Bind nb, Collection<Target> targets, Set<FirewallZone.Name> zones, List<Bind> firewalldNetBinds) throws SQLException, IOException, ValidationException {
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

	private static Set<String> toStringSet(Set<FirewallZone.Name> firewalldZones) {
		int size = firewalldZones.size();
		if(size == 0) {
			return Collections.emptySet();
		}
		if(size == 1) {
			return Collections.singleton(
				firewalldZones.iterator().next().toString()
			);
		}
		Set<String> strings = AoCollections.newLinkedHashSet(size);
		for(FirewallZone.Name firewalldZone : firewalldZones) {
			if(!strings.add(firewalldZone.toString())) throw new AssertionError();
		}
		return strings;
	}

	/**
	 * Warn net_bind exposed to more zones than expected.
	 */
	private static void warnZoneMismatch(Set<FirewallZone.Name> zones, List<Bind> firewalldNetBinds) throws IOException, SQLException {
		if(logger.isLoggable(Level.WARNING)) {
			for(Bind nb : firewalldNetBinds) {
				Set<FirewallZone.Name> expected = nb.getFirewalldZoneNames();
				if(!zones.equals(expected)) {
					logger.warning("Bind #" + nb.getPkey() + " (" + nb + ") opened on unexpected set of firewalld zones: expected=" + expected + ", zones=" + zones);
				}
			}
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			Host thisHost = thisServer.getHost();
			OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
			int osvId = osv.getPkey();

			synchronized(rebuildLock) {
				if(
					osvId == OperatingSystemVersion.CENTOS_7_X86_64
					// Manage firewalld only when installed
					&& PackageManager.getInstalledPackage(PackageManager.PackageName.FIREWALLD) != null
				) {
					List<Bind> netBinds = thisHost.getNetBinds();
					if(logger.isLoggable(Level.FINE)) logger.fine("netBinds: " + netBinds);
					// TODO: The zones should be added per-port, but this release is constrained by the current implementation
					//       of the underlying ao-firewalld package.  Thus any single port associated with a zone will open that
					//       service on that zone for all ports.

					// Gather the set of firewalld zones per service name
					List<Tuple2<ServiceSet, Set<FirewallZone.Name>>> serviceSets = new ArrayList<>();
					// SSH
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
					// Redis
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.REDIS)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("redis targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet(
									new Service(
										"redis",
										null,
										"Redis",
										"Redis client data port",
										Collections.singletonList(
											Port.valueOf(6379, Protocol.TCP)
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
					// Redis Cluster
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.REDIS_CLUSTER)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("redis-cluster targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet(
									new Service(
										"redis-cluster",
										null,
										"Redis Cluster bus",
										"Redis Cluster node-to-node communication",
										Collections.singletonList(
											Port.valueOf(16379, Protocol.TCP)
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
					// Redis Sentinel
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
						List<Bind> firewalldNetBinds = new ArrayList<>();
						for(Bind nb : netBinds) {
							if(nb.getAppProtocol().getProtocol().equals(AppProtocol.REDIS_SENTINEL)) {
								addTarget(nb, targets, zones, firewalldNetBinds);
							}
						}
						if(logger.isLoggable(Level.FINE)) logger.fine("redis-sentinel targets: " + targets + ", zones: " + zones);
						warnZoneMismatch(zones, firewalldNetBinds);
						serviceSets.add(
							new Tuple2<>(
								ServiceSet.createOptimizedServiceSet(
									new Service(
										"redis-sentinel",
										null,
										"Redis Sentinel",
										"Redis Sentinel node-to-node communication",
										Collections.singletonList(
											Port.valueOf(26379, Protocol.TCP)
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
					// SMTP
					{
						List<Target> targets = new ArrayList<>();
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
						Set<FirewallZone.Name> zones = new LinkedHashSet<>();
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
					Map<Set<FirewallZone.Name>, List<ServiceSet>> serviceSetsByZones = new LinkedHashMap<>();
					for(Tuple2<ServiceSet, Set<FirewallZone.Name>> tuple : serviceSets) {
						ServiceSet serviceSet = tuple.getElement1();
						Set<FirewallZone.Name> zones = tuple.getElement2();
						List<ServiceSet> list = serviceSetsByZones.get(zones);
						if(list == null) {
							list = new ArrayList<>();
							serviceSetsByZones.put(zones, list);
						}
						list.add(serviceSet);
					}
					// Commit all service sets
					for(Map.Entry<Set<FirewallZone.Name>, List<ServiceSet>> entry : serviceSetsByZones.entrySet()) {
						ServiceSet.commit(entry.getValue(), toStringSet(entry.getKey()));
					}
				}
			}
			return true;
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
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
				&& AOServDaemonConfiguration.isManagerEnabled(FirewalldManager.class)
				&& firewalldManager == null
			) {
				System.out.print("Starting FirewalldManager: ");
				// Must be a supported operating system
				if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					AOServConnector conn = AOServDaemon.getConnector();
					firewalldManager = new FirewalldManager();
					conn.getNet().getFirewallZone().addTableListener(firewalldManager, 0);
					conn.getNet().getBind().addTableListener(firewalldManager, 0);
					conn.getNet().getBindFirewallZone().addTableListener(firewalldManager, 0);
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
