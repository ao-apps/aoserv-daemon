/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.dns;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.dns.Zone;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of name server processes and files.
 * <p>
 * TODO: Had to put "DISABLE_ZONE_CHECKING="yes"" in /etc/sysconfig/named due
 *       to check finding "has no address records" for MX records.  We should
 *       check for this and enable zone checking.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public final class DNSManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(DNSManager.class.getName());

  /**
   * Gets the IPs allowed to query non-AO names from {@link AoservDaemonConfiguration}.
   */
  private static String getAcl() {
    StringBuilder sb = new StringBuilder();
    for (String acl : AoservDaemonConfiguration.getDnsNamedAcl()) {
      if (sb.length() != 0) {
        sb.append(' ');
      }
      sb.append(acl).append(';');
    }
    return sb.toString();
  }

  private static final PosixFile newConfFile = new PosixFile("/etc/named.conf.new");
  private static final PosixFile confFile = new PosixFile("/etc/named.conf");

  private static final PosixFile namedZoneDir = new PosixFile("/var/named");

  /**
   * Files and directories in /var/named that are never removed.
   */
  private static final String[] staticFiles = {
      "data",
      "dynamic",
      "named.ca",
      "named.empty",
      "named.localhost",
      "named.loopback",
      "slaves"
  };

  private static DNSManager dnsManager;

  private DNSManager() {
    // Do nothing
  }

  /**
   * Each zone is only rebuild if the zone file does not exist or its serial has changed.
   */
  private static final Map<Zone, Long> zoneSerials = new HashMap<>();

  private static final Object rebuildLock = new Object();

  @Override
  protected boolean doRebuild() {
    try {
      AoservConnector connector = AoservDaemon.getConnector();
      Server thisServer = AoservDaemon.getThisServer();
      Host thisHost = thisServer.getHost();
      OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (osvId != OperatingSystemVersion.CENTOS_7_X86_64
          && osvId != OperatingSystemVersion.ROCKY_9_X86_64
      ) {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      int uidMin = thisServer.getUidMin().getId();

      int gidMin = thisServer.getGidMin().getId();

      synchronized (rebuildLock) {
        AppProtocol dns = AoservDaemon.getConnector().getNet().getAppProtocol().get(AppProtocol.DNS);
        if (dns == null) {
          throw new SQLException("Unable to find Protocol: " + AppProtocol.DNS);
        }
        List<Bind> netBinds = thisHost.getNetBinds(dns);
        if (!netBinds.isEmpty()) {
          final int namedGid;
            {
              GroupServer lsg = thisServer.getLinuxServerGroup(Group.NAMED);
              if (lsg == null) {
                throw new SQLException("Unable to find GroupServer: " + Group.NAMED + " on " + thisServer.getHostname());
              }
              namedGid = lsg.getGid().getId();
            }

          // Only restart when needed
          boolean[] needsRestart = {false};

          // Has binds, install package(s) as needed
          PackageManager.installPackage(
              PackageManager.PackageName.BIND,
              () -> {
                try {
                  AoservDaemon.exec("/usr/bin/systemctl", "enable", "named");
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
                needsRestart[0] = true;
              }
          );

          // Keep track of all files that should NOT be deleted in /var/named
          List<String> files = new ArrayList<>();

          // This buffer is used throughout the rest of the method
          ByteArrayOutputStream bout = new ByteArrayOutputStream();

          /*
           * Create the new /var/named files
           */
          // By getting the list first, we get a snap-shot of the data
          List<Zone> zones = connector.getDns().getZone().getRows();
          for (Zone zone : zones) {
            String file = zone.getFile();
            long serial = zone.getSerial();
            Long lastSerial = zoneSerials.get(zone);
            PosixFile realFile = new PosixFile(namedZoneDir, file, false);
            Stat realFileStat = realFile.getStat();
            if (
                lastSerial == null
                    || lastSerial != serial
                    || !realFileStat.exists()
            ) {
              // Build to a memory buffer
              byte[] newContents;
                {
                  bout.reset();
                  try (PrintWriter out = new PrintWriter(bout)) {
                    zone.printZoneFile(out);
                  }
                  newContents = bout.toByteArray();
                }
              if (!realFileStat.exists() || !realFile.contentEquals(newContents)) {
                PosixFile newFile = new PosixFile(namedZoneDir, file + ".new", false);
                try (OutputStream newOut = newFile.getSecureOutputStream(
                    PosixFile.ROOT_UID,
                    namedGid,
                    0640,
                    true,
                    uidMin,
                    gidMin
                )) {
                  newOut.write(newContents);
                }
                newFile.renameTo(realFile);
                needsRestart[0] = true;
              }
              zoneSerials.put(zone, serial);
            }
            files.add(file);
          }

          /*
           * Create the new /etc/named.conf file
           */
          byte[] newContents;
            {
              bout.reset();
              try (ChainWriter out = new ChainWriter(bout)) {
                final String acl = getAcl();
                out.print("//\n"
                    + "// named.conf\n"
                    + "//\n"
                    + "// Generated by ").print(DNSManager.class.getName()).print("\n"
                    + "//\n"
                    + "\n"
                    + "options {\n");
                // Find all unique InetAddresses per port
                Map<Integer, Set<InetAddress>> ipsPerPortV4 = new HashMap<>();
                Map<Integer, Set<InetAddress>> ipsPerPortV6 = new HashMap<>();
                for (Bind nb : netBinds) {
                  int port = nb.getPort().getPort();
                  InetAddress ip = nb.getIpAddress().getInetAddress();
                  Map<Integer, Set<InetAddress>> ipsPerPort;
                  ProtocolFamily family = ip.getProtocolFamily();
                  if (family.equals(StandardProtocolFamily.INET)) {
                    ipsPerPort = ipsPerPortV4;
                  } else if (family.equals(StandardProtocolFamily.INET6)) {
                    ipsPerPort = ipsPerPortV6;
                  } else {
                    throw new AssertionError("Unexpected family: " + family);
                  }
                  Set<InetAddress> ips = ipsPerPort.get(port);
                  if (ips == null) {
                    ipsPerPort.put(port, ips = new LinkedHashSet<>());
                  }
                  ips.add(ip);
                }
                for (Map.Entry<Integer, Set<InetAddress>> entry : ipsPerPortV4.entrySet()) {
                  out.print("\tlisten-on port ").print(entry.getKey()).print(" {");
                  for (InetAddress ip : entry.getValue()) {
                    assert ip.getProtocolFamily().equals(StandardProtocolFamily.INET);
                    out.print(' ').print(ip.toString()).print(';');
                  }
                  out.print(" };\n");
                }
                for (Map.Entry<Integer, Set<InetAddress>> entry : ipsPerPortV6.entrySet()) {
                  out.print("\tlisten-on-v6 port ").print(entry.getKey()).print(" {");
                  for (InetAddress ip : entry.getValue()) {
                    assert ip.getProtocolFamily().equals(StandardProtocolFamily.INET6);
                    out.print(' ').print(ip.toString()).print(';');
                  }
                  out.print(" };\n");
                }
                out.print("\tdirectory \t\"").print(namedZoneDir.getPath()).print("\";\n"
                    + "\tdump-file \t\"/var/named/data/cache_dump.db\";\n"
                    + "\tstatistics-file \"/var/named/data/named_stats.txt\";\n"
                    + "\tmemstatistics-file \"/var/named/data/named_mem_stats.txt\";\n");
                // recursing-file and secroots-file were added in CentOS 7.6
                // secroots-file put first in Rocky 9
                if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                  out.print("\tsecroots-file   \"/var/named/data/named.secroots\";\n");
                }
                out.print("\trecursing-file  \"/var/named/data/named.recursing\";\n");
                // secroots-file put second in CentOS 7
                if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                  out.print("\tsecroots-file   \"/var/named/data/named.secroots\";\n");
                }
                out.print("\n"
                    + "\tallow-query { " + acl + " };\n"
                    //+ "\trecursion yes;\n"
                    + "\tallow-recursion { " + acl + " };\n"
                    + "\tallow-query-cache { " + acl + " };\n"
                    + "\n"
                    + "\tallow-transfer { none; };\n"
                    + "\tnotify no;\n"
                    //+ "\talso-notify { none; };\n"
                    + "\n");
                if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                  out.print("\tdnssec-enable yes;\n");
                }
                out.print("\tdnssec-validation yes;\n"
                    + "\n");
                if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                  out.print("\t/* Path to ISC DLV key */\n"
                      + "\tbindkeys-file \"/etc/named.iscdlv.key\";\n"
                      + "\n");
                }
                out.print("\tmanaged-keys-directory \"/var/named/dynamic\";\n");
                if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                  out.print("\tgeoip-directory \"/usr/share/GeoIP\";\n");
                }
                out.print("\n"
                    + "\tpid-file \"/run/named/named.pid\";\n"
                    + "\tsession-keyfile \"/run/named/session.key\";\n");
                if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                  out.print("\n"
                      + "\t/* https://fedoraproject.org/wiki/Changes/CryptoPolicy */\n"
                      + "\tinclude \"/etc/crypto-policies/back-ends/bind.config\";\n");
                }
                out.print("};\n"
                    + "\n"
                    + "logging {\n"
                    + "\tchannel default_debug {\n"
                    + "\t\tfile \"data/named.run\";\n"
                    + "\t\tseverity dynamic;\n"
                    + "\t};\n"
                    + "};\n"
                    + "\n"
                    + "zone \".\" IN {\n"
                    + "\ttype hint;\n"
                    + "\tfile \"named.ca\";\n"
                    + "};\n"
                    + "\n"
                    + "include \"/etc/named.rfc1912.zones\";\n"
                    + "include \"/etc/named.root.key\";\n");
                for (Zone zone : zones) {
                  String file = zone.getFile();
                  out.print("\n"
                      + "zone \"").print(zone.getZone()).print("\" IN {\n"
                      + "\ttype master;\n"
                      + "\tfile \"").print(file).print("\";\n"
                      + "\tallow-query { any; };\n"
                      + "\tallow-update { none; };\n");
                  if (zone.isArpa()) {
                    // Allow notify HE slaves and allow transfer
                    // TODO: This should also be config/tables, not hard-coded
                    out.print("\tallow-transfer { 216.218.133.2; };\n"
                        + "\tnotify explicit;\n"
                        + "\talso-notify { 216.218.130.2; 216.218.131.2; 216.218.132.2; 216.66.1.2; 216.66.80.18; };\n");
                  }
                  out.print("};\n");
                }
              }
              newContents = bout.toByteArray();
            }
          if (!confFile.getStat().exists() || !confFile.contentEquals(newContents)) {
            needsRestart[0] = true;
            try (OutputStream newOut = newConfFile.getSecureOutputStream(
                PosixFile.ROOT_UID,
                namedGid,
                0640,
                true,
                uidMin,
                gidMin
            )) {
              newOut.write(newContents);
            }
            newConfFile.renameTo(confFile);
          }

          /*
           * Restart the daemon
           */
          if (needsRestart[0]) {
            restart();
          }

          /*
           * Remove any files that should no longer be in /var/named
           */
          files.addAll(Arrays.asList(staticFiles));
          FTPManager.trimFiles(namedZoneDir, files);
        } else if (AoservDaemonConfiguration.isPackageManagerUninstallEnabled()) {
          // No binds, uninstall package(s)
          PackageManager.removePackage(PackageManager.PackageName.BIND);
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

  private static final Object restartLock = new Object();

  private static void restart() throws IOException, SQLException {
    AppProtocol dns = AoservDaemon.getConnector().getNet().getAppProtocol().get(AppProtocol.DNS);
    if (dns == null) {
      throw new SQLException("Unable to find AppProtocol: " + AppProtocol.DNS);
    }
    Host thisHost = AoservDaemon.getThisServer().getHost();
    if (!thisHost.getNetBinds(dns).isEmpty()) {
      synchronized (restartLock) {
        AoservDaemon.exec("/usr/bin/systemctl", "reload-or-restart", "named");
      }
    }
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    OperatingSystemVersion osv = AoservDaemon.getThisServer().getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    synchronized (System.out) {
      if (
          // Nothing is done for these operating systems
          osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
              // Check config after OS check so config entry not needed
              && AoservDaemonConfiguration.isManagerEnabled(DNSManager.class)
              && dnsManager == null
      ) {
        System.out.print("Starting DNSManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          dnsManager = new DNSManager();
          conn.getDns().getZone().addTableListener(dnsManager, 0);
          conn.getDns().getRecord().addTableListener(dnsManager, 0);
          conn.getNet().getBind().addTableListener(dnsManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild DNS";
  }
}
