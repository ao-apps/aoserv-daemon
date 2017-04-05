/*
 * Copyright 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net.firewalld;

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
import com.aoindustries.firewalld.Service;
import com.aoindustries.firewalld.ServiceSet;
import com.aoindustries.firewalld.Target;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.InetAddressPrefix;
import com.aoindustries.net.InetAddressPrefixes;
import com.aoindustries.net.Port;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the configuration of firewalld.
 */
final public class FirewalldManager extends BuilderThread {

	/**
	 * The zones for SSH.
	 */
	private static final Set<String> sshZones = Collections.unmodifiableSet(
		new LinkedHashSet<String>(
			Arrays.asList(
				"dmz",
				"external",
				"home",
				"internal",
				"public",
				"work"
			)
		)
	);

	/**
	 * The public zone.
	 */
	private static final Set<String> publicZone = Collections.singleton("public");

	private static FirewalldManager firewalldManager;

	private FirewalldManager() {
	}

	/**
	 * Adds a target unless the net bind is on loopback device.
	 */
	private static void addTarget(NetBind nb, Collection<Target> targets) throws SQLException, IOException, ValidationException {
		InetAddress ip = nb.getIPAddress().getInetAddress();
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
				if(
					osvId == OperatingSystemVersion.CENTOS_7_X86_64
					// Manage firewalld only when installed
					&& PackageManager.getInstalledPackage(PackageManager.PackageName.FIREWALLD) != null
				) {
					List<NetBind> netBinds = thisServer.getNetBinds();
					// SSH
					{
						List<Target> targets = new ArrayList<>();
						for(NetBind nb : netBinds) {
							if(nb.getAppProtocol().getName().equals(Protocol.SSH)) {
								addTarget(nb, targets);
							}
						}
						ServiceSet.createOptimizedServiceSet("ssh", targets).commit(sshZones);
						// TODO: Include rate-limiting from public zone, as well as a zone for monitoring
					}
					// All the services that are in the regular public zone
					List<ServiceSet> publicServiceSets = new ArrayList<>();
					// AOServ Daemon
					{
						List<Target> targets = new ArrayList<>();
						for(NetBind nb : netBinds) {
							String appProtocol = nb.getAppProtocol().getName();
							if(
								appProtocol.equals(Protocol.AOSERV_DAEMON)
								|| appProtocol.equals(Protocol.AOSERV_DAEMON_SSL)
							) {
								addTarget(nb, targets);
							}
						}
						publicServiceSets.add(ServiceSet.createOptimizedServiceSet("aoserv-daemon", targets));
					}
					// AOServ Master
					{
						List<Target> targets = new ArrayList<>();
						for(NetBind nb : netBinds) {
							String appProtocol = nb.getAppProtocol().getName();
							if(
								appProtocol.equals(Protocol.AOSERV_MASTER)
								|| appProtocol.equals(Protocol.AOSERV_MASTER_SSL)
							) {
								addTarget(nb, targets);
							}
						}
						publicServiceSets.add(ServiceSet.createOptimizedServiceSet("aoserv-master", targets));
					}
					// DNS
					{
						List<Target> targets = new ArrayList<>();
						for(NetBind nb : netBinds) {
							if(nb.getAppProtocol().getName().equals(Protocol.DNS)) {
								addTarget(nb, targets);
							}
						}
						publicServiceSets.add(
							ServiceSet.createOptimizedServiceSet(
								new Service(
									"named",
									null,
									"named",
									"Berkeley Internet Name Domain (DNS)",
									Arrays.asList(
										Port.valueOf(53, com.aoindustries.net.Protocol.TCP),
										Port.valueOf(53, com.aoindustries.net.Protocol.UDP)
									),
									Collections.emptySet(), // protocols
									Collections.emptySet(), // sourcePorts
									Collections.emptySet(), // modules
									InetAddressPrefixes.UNSPECIFIED_IPV4,
									InetAddressPrefixes.UNSPECIFIED_IPV6
								),
								targets
							)
						);
					}
					// MySQL
					{
						List<Target> targets = new ArrayList<>();
						for(NetBind nb : netBinds) {
							if(nb.getAppProtocol().getName().equals(Protocol.MYSQL)) {
								addTarget(nb, targets);
							}
						}
						publicServiceSets.add(ServiceSet.createOptimizedServiceSet("mysql", targets));
					}
					// PostgreSQL
					{
						List<Target> targets = new ArrayList<>();
						for(NetBind nb : netBinds) {
							if(nb.getAppProtocol().getName().equals(Protocol.POSTGRESQL)) {
								addTarget(nb, targets);
							}
						}
						publicServiceSets.add(ServiceSet.createOptimizedServiceSet("postgresql", targets));
					}
					// Commit all public service sets
					ServiceSet.commit(publicServiceSets, publicZone);
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
				&& AOServDaemonConfiguration.isManagerEnabled(FirewalldManager.class)
				&& firewalldManager == null
			) {
				System.out.print("Starting FirewalldManager: ");
				// Must be a supported operating system
				if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					AOServConnector conn = AOServDaemon.getConnector();
					firewalldManager = new FirewalldManager();
					conn.getNetBinds().addTableListener(firewalldManager, 0);
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
