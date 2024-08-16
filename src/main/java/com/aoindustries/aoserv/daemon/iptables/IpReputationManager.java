/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2012, 2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.iptables;

import com.aoapps.collections.AoCollections;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.math.SafeMath;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.account.Administrator;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.net.reputation.Host;
import com.aoindustries.aoserv.client.net.reputation.Network;
import com.aoindustries.aoserv.client.net.reputation.Set;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the IP reputation system.
 *
 * @author  AO Industries, Inc.
 */
// TODO: ROCKY_9_X86_64
public final class IpReputationManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(IpReputationManager.class.getName());

  private static IpReputationManager ipReputationManager;

  private static final String IPTABLES_DIR = "/etc/opt/aoserv-daemon/iptables";
  private static final String IPREPUTATION_SUBDIR = "ipreputation";

  /**
   * Gets the iptables directory, creating if necessary.
   */
  private static PosixFile getIptablesDir() throws IOException {
    PosixFile iptablesDir = new PosixFile(IPTABLES_DIR);
    if (!iptablesDir.getStat().exists()) {
      iptablesDir.mkdir(false, 0700);
    }
    return iptablesDir;
  }

  /**
   * Gets the iptables directory, creating if necessary.
   */
  private static PosixFile getIpreputationDir() throws IOException {
    PosixFile iptablesDir = getIptablesDir();
    PosixFile ipreputationDir = new PosixFile(iptablesDir.getPath() + "/" + IPREPUTATION_SUBDIR);
    if (!ipreputationDir.getStat().exists()) {
      ipreputationDir.mkdir(false, 0700);
    }
    return ipreputationDir;
  }

  private IpReputationManager() {
    // Do nothing
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
          // Nothing is done for these operating systems
          osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_X86_64
              // Check config after OS check so config entry not needed
              && AoservDaemonConfiguration.isManagerEnabled(IpReputationManager.class)
              && ipReputationManager == null
      ) {
        System.out.print("Starting IpReputationManager: ");
        // Must be a supported operating system
        if (
            // Only runs on Xen dom0 (firewalling done outside virtual servers)
            osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
                || osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          Administrator administrator = conn.getCurrentAdministrator();
          User mu = administrator.getMasterUser();
          if (mu == null) {
            throw new AssertionError("Administrator is not a User");
          }
          if (mu.isRouter()) {
            ipReputationManager = new IpReputationManager();
            conn.getNet().getReputation().getSet().addTableListener(ipReputationManager, 0);
            conn.getNet().getReputation().getHost().addTableListener(ipReputationManager, 0);
            conn.getNet().getReputation().getNetwork().addTableListener(ipReputationManager, 0);
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
  private static final Comparator<Host> badHostComparator = (Host host1, Host host2) -> {
    // Sort by effective reputation first
    int rep1 = host1.getReputation();
    int rep2 = host2.getReputation();
    if (rep1 < rep2) {
      return -1;
    }
    if (rep1 > rep2) {
      return 1;
    }
    // Sort by IP next
    int ip1 = host1.getHost();
    int ip2 = host2.getHost();
    if (ip1 < ip2) {
      return -1;
    }
    if (ip1 > ip2) {
      return 1;
    }
    return 0;
  };

  /**
   * Orders networks with best reputation first.
   */
  private static final Comparator<Network> goodNetworkComparator = (Network network1, Network network2) -> {
    // Sort by effective reputation first
    int count1 = network1.getCounter();
    int count2 = network2.getCounter();
    if (count1 > count2) {
      return -1;
    }
    if (count1 < count2) {
      return 1;
    }
    // Sort by IP next
    int ipNet1 = network1.getNetwork();
    int ipNet2 = network2.getNetwork();
    if (ipNet1 < ipNet2) {
      return -1;
    }
    if (ipNet1 > ipNet2) {
      return 1;
    }
    return 0;
  };

  /**
   * Orders hosts with best reputation first.
   */
  private static final Comparator<Host> goodHostComparator = (Host host1, Host host2) -> {
    // Sort by effective reputation first
    int rep1 = host1.getReputation();
    int rep2 = host2.getReputation();
    if (rep1 > rep2) {
      return -1;
    }
    if (rep1 < rep2) {
      return 1;
    }
    // Sort by IP next
    int ip1 = host1.getHost();
    int ip2 = host2.getHost();
    if (ip1 < ip2) {
      return -1;
    }
    if (ip1 > ip2) {
      return 1;
    }
    return 0;
  };

  /**
   * See ip_reputation_sets-create.sql for set name encoding
   *
   * @see  #synchronizeIpset
   */
  private static void synchronizeHostIpset(
      java.util.Set<Host> hosts,
      Set.ConfidenceType confidence,
      Set.ReputationType reputationType,
      String identifier,
      PosixFile setDir
  ) throws IOException {
    java.util.Set<Integer> entries = AoCollections.newLinkedHashSet(Math.min(Ipset.MAX_IPSET_SIZE + 1, hosts.size()));
    for (Host host : hosts) {
      entries.add(host.getHost());
      if (entries.size() > Ipset.MAX_IPSET_SIZE) {
        break;
      }
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
      java.util.Set<Network> networks,
      short networkPrefix,
      String identifier,
      PosixFile setDir
  ) throws IOException {
    java.util.Set<Integer> entries = AoCollections.newLinkedHashSet(Math.min(Ipset.MAX_IPSET_SIZE + 1, networks.size()));
    for (Network network : networks) {
      entries.add(network.getNetwork());
      if (entries.size() > Ipset.MAX_IPSET_SIZE) {
        break;
      }
    }
    Ipset.synchronize(
        entries,
        networkPrefix,
        Ipset.NamespacePrefix.R.name() + Set.ReputationType.GOOD.toChar() + "N_" + identifier,
        setDir
    );
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      AoservConnector conn = AoservDaemon.getConnector();
      Server thisServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (
          // Only runs on Xen dom0 (firewalling done outside virtual servers)
          osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
      ) {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      synchronized (rebuildLock) {
        final PosixFile ipreputationDir = getIpreputationDir();
        final Collection<Set> sets = conn.getNet().getReputation().getSet().getRows();

        // Track the names of each set, used to remove extra directories
        final java.util.Set<String> setIdentifiers = AoCollections.newHashSet(sets.size());

        for (Set set : sets) {
          // Set settings
          final String identifier       = set.getIdentifier();
          final short maxUncertainGood  = set.getMaxUncertainReputation();
          final short minUncertainBad   = SafeMath.castShort(-maxUncertainGood);

          // Make sure to not remove the directory
          setIdentifiers.add(identifier);

          // Create the set directory if missing
          PosixFile setDir = new PosixFile(ipreputationDir, identifier, true);
          if (!setDir.getStat().exists()) {
            setDir.mkdir(false, 0700);
          }

          // TODO: Use concurrency equal to minimum of four or half the cores on the server

          // Split the IP addresses into four classes based on the set's settings.
          SortedSet<Host>    definiteBadHosts   = new TreeSet<>(badHostComparator);
          SortedSet<Host>    uncertainBadHosts  = new TreeSet<>(badHostComparator);
          SortedSet<Host>    uncertainGoodHosts = new TreeSet<>(goodHostComparator);
          SortedSet<Host>    definiteGoodHosts  = new TreeSet<>(goodHostComparator);
          for (Host host : set.getHosts()) {
            short rep = host.getReputation();
            if (rep < minUncertainBad) {
              definiteBadHosts.add(host);
            } else if (rep < 0) {
              uncertainBadHosts.add(host);
            } else if (rep > maxUncertainGood) {
              definiteGoodHosts.add(host);
            } else if (rep >= 0) {
              uncertainGoodHosts.add(host);
            } else {
              throw new AssertionError("rep=" + rep);
            }
          }

          // Sort the networks by reputation
          SortedSet<Network> goodNetworks = new TreeSet<>(goodNetworkComparator);
          goodNetworks.addAll(set.getNetworks());

          // Synchronize both the in-kernel set as well as the on-disk representations
          synchronizeHostIpset(
              definiteBadHosts,
              Set.ConfidenceType.DEFINITE,
              Set.ReputationType.BAD,
              identifier,
              setDir
          );
          synchronizeHostIpset(
              uncertainBadHosts,
              Set.ConfidenceType.UNCERTAIN,
              Set.ReputationType.BAD,
              identifier,
              setDir
          );
          synchronizeHostIpset(
              uncertainGoodHosts,
              Set.ConfidenceType.UNCERTAIN,
              Set.ReputationType.GOOD,
              identifier,
              setDir
          );
          synchronizeHostIpset(
              definiteGoodHosts,
              Set.ConfidenceType.DEFINITE,
              Set.ReputationType.GOOD,
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
        if (list != null) {
          List<File> deleteFileList = new ArrayList<>();
          for (String filename : list) {
            if (!setIdentifiers.contains(filename)) {
              PosixFile extraUf = new PosixFile(ipreputationDir, filename, true);
              deleteFileList.add(extraUf.getFile());
            }
          }
          BackupManager.backupAndDeleteFiles(deleteFileList);
        }
      }
      return true;
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }
}
