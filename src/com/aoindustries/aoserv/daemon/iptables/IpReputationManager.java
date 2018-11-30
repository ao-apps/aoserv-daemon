/*
 * Copyright 2012-2013, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.iptables;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.account.BusinessAdministrator;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.AOServer;
import com.aoindustries.aoserv.client.master.MasterUser;
import com.aoindustries.aoserv.client.net.reputation.IpReputationSet;
import com.aoindustries.aoserv.client.net.reputation.IpReputationSetHost;
import com.aoindustries.aoserv.client.net.reputation.IpReputationSetNetwork;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.math.SafeMath;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Handles the IP reputation system.
 *
 * @author  AO Industries, Inc.
 */
final public class IpReputationManager extends BuilderThread {

	private static IpReputationManager ipReputationManager;

	private static final String IPTABLES_DIR = "/etc/opt/aoserv-daemon/iptables";
	private static final String IPREPUTATION_SUBDIR = "ipreputation";

	/**
	 * Gets the iptables directory, creating if necessary.
	 */
	private static UnixFile getIptablesDir() throws IOException {
		UnixFile iptablesDir = new UnixFile(IPTABLES_DIR);
		if(!iptablesDir.getStat().exists()) iptablesDir.mkdir(false, 0700);
		return iptablesDir;
	}

	/**
	 * Gets the iptables directory, creating if necessary.
	 */
	private static UnixFile getIpreputationDir() throws IOException {
		UnixFile iptablesDir = getIptablesDir();
		UnixFile ipreputationDir = new UnixFile(iptablesDir.getPath() + "/" + IPREPUTATION_SUBDIR);
		if(!ipreputationDir.getStat().exists()) ipreputationDir.mkdir(false, 0700);
		return ipreputationDir;
	}

	private IpReputationManager() {
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(IpReputationManager.class)
				&& ipReputationManager == null
			) {
				System.out.print("Starting IpReputationManager: ");
				// Must be a supported operating system
				if(
					// Only runs on Xen dom0 (firewalling done outside virtual servers)
					osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
					|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					BusinessAdministrator ba = conn.getThisBusinessAdministrator();
					MasterUser mu = ba.getMasterUser();
					if(mu == null) throw new AssertionError("BusinessAdministrator is not a MasterUser");
					if(mu.isRouter()) {
						ipReputationManager = new IpReputationManager();
						conn.getIpReputationSets().addTableListener(ipReputationManager, 0);
						conn.getIpReputationSetHosts().addTableListener(ipReputationManager, 0);
						conn.getIpReputationSetNetworks().addTableListener(ipReputationManager, 0);
						System.out.println("Done");
					} else {
						System.out.println("Disabled: This is not a router");
					}
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild IP Reputation Sets";
	}

	/**
	 * Orders hosts with worse reputation first.
	 */
	private static final Comparator<IpReputationSetHost> badHostComparator = (IpReputationSetHost host1, IpReputationSetHost host2) -> {
		// Sort by effective reputation first
		int rep1 = host1.getReputation();
		int rep2 = host2.getReputation();
		if(rep1<rep2) return -1;
		if(rep1>rep2) return 1;
		// Sort by IP next
		int ip1 = host1.getHost();
		int ip2 = host2.getHost();
		if(ip1<ip2) return -1;
		if(ip1>ip2) return 1;
		return 0;
	};

	/**
	 * Orders networks with best reputation first.
	 */
	private static final Comparator<IpReputationSetNetwork> goodNetworkComparator = (IpReputationSetNetwork network1, IpReputationSetNetwork network2) -> {
		// Sort by effective reputation first
		int count1 = network1.getCounter();
		int count2 = network2.getCounter();
		if(count1>count2) return -1;
		if(count1<count2) return 1;
		// Sort by IP next
		int ipNet1 = network1.getNetwork();
		int ipNet2 = network2.getNetwork();
		if(ipNet1<ipNet2) return -1;
		if(ipNet1>ipNet2) return 1;
		return 0;
	};

	/**
	 * Orders hosts with best reputation first.
	 */
	private static final Comparator<IpReputationSetHost> goodHostComparator = (IpReputationSetHost host1, IpReputationSetHost host2) -> {
		// Sort by effective reputation first
		int rep1 = host1.getReputation();
		int rep2 = host2.getReputation();
		if(rep1>rep2) return -1;
		if(rep1<rep2) return 1;
		// Sort by IP next
		int ip1 = host1.getHost();
		int ip2 = host2.getHost();
		if(ip1<ip2) return -1;
		if(ip1>ip2) return 1;
		return 0;
	};

	/**
	 * See ip_reputation_sets-create.sql for set name encoding
	 *
	 * @see  #synchronizeIpset
	 */
	private static void synchronizeHostIpset(
		Set<IpReputationSetHost> hosts,
		IpReputationSet.ConfidenceType confidence,
		IpReputationSet.ReputationType reputationType,
		String identifier,
		UnixFile setDir
	) throws IOException {
		Set<Integer> entries = new LinkedHashSet<>(Math.min(Ipset.MAX_IPSET_SIZE+1, hosts.size())*4/3+1);
		for(IpReputationSetHost host : hosts) {
			entries.add(host.getHost());
			if(entries.size()>Ipset.MAX_IPSET_SIZE) break;
		}
		Ipset.synchronize(
			entries,
			Ipset.HOST_NETWORK_PREFIX,
			Ipset.NamespacePrefix.R.name() + reputationType.toChar() + confidence.toChar() + '_' + identifier,
			setDir
		);
	}

	/**
	 * See ip_reputation_sets-create.sql for set name encoding
	 *
	 * @see  #synchronizeIpset
	 */
	private static void synchronizeNetworkIpset(
		Set<IpReputationSetNetwork> networks,
		short networkPrefix,
		String identifier,
		UnixFile setDir
	) throws IOException {
		Set<Integer> entries = new LinkedHashSet<>(Math.min(Ipset.MAX_IPSET_SIZE+1, networks.size())*4/3+1);
		for(IpReputationSetNetwork network : networks) {
			entries.add(network.getNetwork());
			if(entries.size()>Ipset.MAX_IPSET_SIZE) break;
		}
		Ipset.synchronize(
			entries,
			networkPrefix,
			Ipset.NamespacePrefix.R.name()+IpReputationSet.ReputationType.GOOD.toChar()+"N_" + identifier,
			setDir
		);
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServConnector conn = AOServDaemon.getConnector();
			AOServer thisAOServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				// Only runs on Xen dom0 (firewalling done outside virtual servers)
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				final UnixFile ipreputationDir = getIpreputationDir();
				final Collection<IpReputationSet> sets = conn.getIpReputationSets().getRows();

				// Track the names of each set, used to remove extra directories
				final Set<String> setIdentifiers = new HashSet<>(sets.size()*4/3+1);

				for(IpReputationSet set : sets) {
					// Set settings
					final String identifier       = set.getIdentifier();
					final short maxUncertainGood  = set.getMaxUncertainReputation();
					final short minUncertainBad   = SafeMath.castShort(-maxUncertainGood);

					// Make sure to not remove the directory
					setIdentifiers.add(identifier);

					// Create the set directory if missing
					UnixFile setDir = new UnixFile(ipreputationDir, identifier, true);
					if(!setDir.getStat().exists()) setDir.mkdir(false, 0700);

					// TODO: Use concurrency equal to minimum of four or half the cores on the server

					// Split the IP addresses into four classes based on the set's settings.
					SortedSet<IpReputationSetHost>    definiteBadHosts   = new TreeSet<>(badHostComparator);
					SortedSet<IpReputationSetHost>    uncertainBadHosts  = new TreeSet<>(badHostComparator);
					SortedSet<IpReputationSetHost>    uncertainGoodHosts = new TreeSet<>(goodHostComparator);
					SortedSet<IpReputationSetHost>    definiteGoodHosts  = new TreeSet<>(goodHostComparator);
					for(IpReputationSetHost host : set.getHosts()) {
						short rep = host.getReputation();
						if(rep < minUncertainBad) {
							definiteBadHosts.add(host);
						} else if(rep < 0) {
							uncertainBadHosts.add(host);
						} else if(rep > maxUncertainGood) {
							definiteGoodHosts.add(host);
						} else if(rep >= 0) {
							uncertainGoodHosts.add(host);
						} else {
							throw new AssertionError("rep="+rep);
						}
					}

					// Sort the networks by reputation
					SortedSet<IpReputationSetNetwork> goodNetworks = new TreeSet<>(goodNetworkComparator);
					goodNetworks.addAll(set.getNetworks());

					// Synchronize both the in-kernel set as well as the on-disk representations
					synchronizeHostIpset(
						definiteBadHosts,
						IpReputationSet.ConfidenceType.DEFINITE,
						IpReputationSet.ReputationType.BAD,
						identifier,
						setDir
					);
					synchronizeHostIpset(
						uncertainBadHosts,
						IpReputationSet.ConfidenceType.UNCERTAIN,
						IpReputationSet.ReputationType.BAD,
						identifier,
						setDir
					);
					synchronizeHostIpset(
						uncertainGoodHosts,
						IpReputationSet.ConfidenceType.UNCERTAIN,
						IpReputationSet.ReputationType.GOOD,
						identifier,
						setDir
					);
					synchronizeHostIpset(
						definiteGoodHosts,
						IpReputationSet.ConfidenceType.DEFINITE,
						IpReputationSet.ReputationType.GOOD,
						identifier,
						setDir
					);
					synchronizeNetworkIpset(
						goodNetworks,
						set.getNetworkPrefix(),
						identifier,
						setDir
					);
				}

				// TODO: Delete any sets that have reputation prefixes from the kernel
				//       as long as has zero references.  Don't delete files when still
				//       has references.

				// Delete any extra directories, after backing-up
				String[] list = ipreputationDir.list();
				if(list!=null) {
					List<File> deleteFileList=new ArrayList<>();
					for(String filename : list) {
						if(!setIdentifiers.contains(filename)) {
							UnixFile extraUf = new UnixFile(ipreputationDir, filename, true);
							deleteFileList.add(extraUf.getFile());
						}
					}
					BackupManager.backupAndDeleteFiles(deleteFileList);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(IpReputationManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}
}
