/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2014, 2015, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon;

import com.aoapps.hodgepodge.io.stream.StreamableInput;
import com.aoapps.hodgepodge.io.stream.StreamableOutput;
import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.net.Port;
import com.aoapps.net.Protocol;
import com.aoapps.security.Key;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.linux.DaemonAcl;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.mysql.TableName;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.pki.Certificate;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AoservDaemonProtocol;
import com.aoindustries.aoserv.daemon.distro.DistroManager;
import com.aoindustries.aoserv.daemon.email.EmailListManager;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.email.ProcmailManager;
import com.aoindustries.aoserv.daemon.failover.FailoverFileReplicationManager;
import com.aoindustries.aoserv.daemon.httpd.AWStatsManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdServerManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.monitor.MrtgManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLDBUserManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLDatabaseManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLServerManager;
import com.aoindustries.aoserv.daemon.mysql.MySQLUserManager;
import com.aoindustries.aoserv.daemon.net.NetDeviceManager;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresDatabaseManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresServerManager;
import com.aoindustries.aoserv.daemon.postgres.PostgresUserManager;
import com.aoindustries.aoserv.daemon.server.PhysicalServerManager;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.server.VirtualServerManager;
import com.aoindustries.aoserv.daemon.ssl.SslCertificateManager;
import com.aoindustries.noc.monitor.portmon.PortMonitor;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLHandshakeException;

/**
 * The <code>AOServServerThread</code> handles a connection once it is accepted.
 *
 * @author  AO Industries, Inc.
 */
public final class AoservDaemonServerThread extends Thread {

  private static final Logger logger = Logger.getLogger(AoservDaemonServerThread.class.getName());

  /**
   * The set of supported versions, with the most preferred versions first.
   */
  // Matches AoservDaemonConnection.java
  private static final AoservDaemonProtocol.Version[] SUPPORTED_VERSIONS = {
      AoservDaemonProtocol.Version.VERSION_1_84_19,
      AoservDaemonProtocol.Version.VERSION_1_84_13,
      AoservDaemonProtocol.Version.VERSION_1_84_11,
      AoservDaemonProtocol.Version.VERSION_1_83_0,
      AoservDaemonProtocol.Version.VERSION_1_81_10,
      AoservDaemonProtocol.Version.VERSION_1_80_1,
      AoservDaemonProtocol.Version.VERSION_1_80_0,
      AoservDaemonProtocol.Version.VERSION_1_77
  };

  ///**
  // * The <code>AOServServer</code> that created this <code>AOServServerThread</code>.
  // */
  //private final AoservDaemonServer server;

  /**
   * The <code>Socket</code> that is connected.
   */
  private final Socket socket;

  /**
   * The <code>StreamableInput</code> that is being read from.
   */
  private final StreamableInput in;

  /**
   * The <code>StreamableOutput</code> that is being written to.
   */
  private final StreamableOutput out;

  /**
   * Creates a new, running <code>AOServServerThread</code>.
   */
  public AoservDaemonServerThread(AoservDaemonServer server, Socket socket) throws IOException {
    setName("AOServ Daemon Host Thread #" + getId() + " - " + socket.getInetAddress().getHostAddress());
    //this.server = server;
    this.socket = socket;
    this.in = new StreamableInput(new BufferedInputStream(socket.getInputStream()));
    this.out = new StreamableOutput(new BufferedOutputStream(socket.getOutputStream()));
    this.out.flush();
  }

  @Override
  public void run() {
    try {
      final AoservConnector connector = AoservDaemon.getConnector();
      final Server thisServer = AoservDaemon.getThisServer();

      final AoservDaemonProtocol.Version protocolVersion;
      final Key daemonKey;
      {
        // Read the most preferred version
        String preferredVersion;
        try {
          preferredVersion = in.readUTF();
        } catch (SSLHandshakeException err) {
          String message = err.getMessage();
          Level level;
          if (
              // Do not routinely log messages that are normal due to monitoring simply connecting only
              !(
                  // Java 11
                  "Remote host terminated the handshake".equals(message)
                      // Java 8
                      || "Remote host closed connection during handshake".equals(message)
                )
          ) {
            level = Level.SEVERE;
          } else {
            level = Level.FINE;
          }
          logger.log(level, null, err);
          return;
        }
        // Then connector key
        if (
            preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_77.getVersion())
                || preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_80_0.getVersion())
                || preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_80_1.getVersion())
                || preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_81_10.getVersion())
                || preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_83_0.getVersion())
                || preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_84_11.getVersion())
        ) {
          // Clients 1.84.11 and before send String
          String str = in.readNullUTF();
          daemonKey = (str == null) ? null : new Key(Base64.getDecoder().decode(str));
        } else {
          // Clients 1.84.13 and above send byte[]
          int len = in.readUnsignedShort();
          if (len == 0) {
            daemonKey = null;
          } else {
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            daemonKey = new Key(bytes);
          }
        }
        // Now additional versions.
        String[] versions;
        if (preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_77.getVersion())) {
          // Client 1.77 only sends the single preferred version
          versions = new String[]{preferredVersion};
        } else {
          versions = new String[1 + in.readCompressedInt()];
          versions[0] = preferredVersion;
          for (int i = 1; i < versions.length; i++) {
            versions[i] = in.readUTF();
          }
        }
        // Select the first supported version
        AoservDaemonProtocol.Version selectedVersion = null;
        SELECT_VERSION:
        for (String version : versions) {
          for (AoservDaemonProtocol.Version supportedVersion : SUPPORTED_VERSIONS) {
            if (supportedVersion.getVersion().equals(version)) {
              selectedVersion = supportedVersion;
              break SELECT_VERSION;
            }
          }
        }
        if (selectedVersion == null) {
          out.writeBoolean(false);
          out.writeUTF(SUPPORTED_VERSIONS[0].getVersion());
          if (!preferredVersion.equals(AoservDaemonProtocol.Version.VERSION_1_77.getVersion())) {
            // Client 1.77 only expects the single preferred version
            out.writeCompressedInt(SUPPORTED_VERSIONS.length - 1);
            for (int i = 1; i < SUPPORTED_VERSIONS.length; i++) {
              out.writeUTF(SUPPORTED_VERSIONS[i].getVersion());
            }
          }
          out.flush();
          return;
        }
        out.writeBoolean(true);
        protocolVersion = selectedVersion;
      }
      if (daemonKey != null) {
        // Must come from one of the hosts listed in the database
        String hostAddress = socket.getInetAddress().getHostAddress();
        boolean isOk = false;
        for (DaemonAcl allowedHost : thisServer.getAoserverDaemonHosts()) {
          String tempAddress = InetAddress.getByName(allowedHost.getHost().toString()).getHostAddress();
          if (tempAddress.equals(hostAddress)) {
            isOk = true;
            break;
          }
        }
        if (isOk) {
          // Authenticate the client first
          if (!AoservDaemonConfiguration.getDaemonKey().matches(daemonKey)) {
            System.err.println("Connection attempted from " + hostAddress + " with invalid key: " + daemonKey);
            out.writeBoolean(false);
            out.flush();
            return;
          }
        } else {
          logger.log(Level.WARNING, "Connection attempted from " + hostAddress + " but not listed in server_daemon_hosts");
          out.writeBoolean(false);
          out.flush();
          return;
        }
      }
      out.writeBoolean(true);
      // Command sequence starts at a random value
      final long startSeq;
      if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
        out.writeUTF(protocolVersion.getVersion());
        startSeq = AoservDaemon.getSecureRandom().nextLong();
        out.writeLong(startSeq);
      } else {
        startSeq = 0;
      }
      out.flush();

      long seq = startSeq;
      Loop:
      while (!Thread.currentThread().isInterrupted()) {
        // Verify client sends matching sequence
        if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
          long clientSeq = in.readLong();
          if (clientSeq != seq) {
            throw new IOException("Sequence mismatch: " + clientSeq + " != " + seq);
          }
        }
        // Send command sequence
        if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_1) >= 0) {
          out.writeLong(seq); // out is buffered, so no I/O created by writing this early
        }
        // Increment sequence
        seq++;
        // Continue with task
        int taskCode = in.readCompressedInt();
        if (taskCode == AoservDaemonProtocol.QUIT) {
          break Loop;
        }
        boolean logIoException = true;
        try {
          switch (taskCode) {
            case AoservDaemonProtocol.COMPARE_LINUX_ACCOUNT_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing COMPARE_LINUX_ACCOUNT_PASSWORD, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                String password = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may COMPARE_LINUX_ACCOUNT_PASSWORD");
                }
                boolean matches = LinuxAccountManager.comparePassword(username, password);
                out.write(AoservDaemonProtocol.DONE);
                out.writeBoolean(matches);
                break;
              }
            case AoservDaemonProtocol.DUMP_MYSQL_DATABASE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing DUMP_MYSQL_DATABASE, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                boolean gzip;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
                  gzip = in.readBoolean();
                } else {
                  gzip = false;
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may DUMP_MYSQL_DATABASE");
                }
                com.aoindustries.aoserv.client.mysql.Database md = connector.getMysql().getDatabase().get(id);
                if (md == null) {
                  throw new SQLException("Unable to find Database: " + id);
                }
                MySQLDatabaseManager.dumpDatabase(md, protocolVersion, out, gzip);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.DUMP_POSTGRES_DATABASE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing DUMP_POSTGRES_DATABASE, Thread=" + toString());
                }
                int pkey = in.readCompressedInt();
                boolean gzip;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
                  gzip = in.readBoolean();
                } else {
                  gzip = false;
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may DUMP_POSTGRES_DATABASE");
                }
                com.aoindustries.aoserv.client.postgresql.Database pd = connector.getPostgresql().getDatabase().get(pkey);
                if (pd == null) {
                  throw new SQLException("Unable to find Database: " + pkey);
                }
                PostgresDatabaseManager.dumpDatabase(pd, protocolVersion, out, gzip);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.FAILOVER_FILE_REPLICATION:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing FAILOVER_FILE_REPLICATION, Thread=" + toString());
                }
                long daemonAccessKey = in.readLong();
                boolean useCompression = in.readBoolean();
                short retention = in.readShort();
                short fromServerYear = in.readShort();
                short fromServerMonth = in.readShort();
                short fromServerDay = in.readShort();
                List<com.aoindustries.aoserv.client.mysql.Server.Name> replicatedMysqlServers;
                List<String> replicatedMysqlMinorVersions;
                if (retention == 1) {
                  int len = in.readCompressedInt();
                  replicatedMysqlServers = new ArrayList<>(len);
                  replicatedMysqlMinorVersions = new ArrayList<>(len);
                  for (int c = 0; c < len; c++) {
                    replicatedMysqlServers.add(com.aoindustries.aoserv.client.mysql.Server.Name.valueOf(in.readUTF()));
                    replicatedMysqlMinorVersions.add(in.readUTF());
                  }
                } else {
                  replicatedMysqlServers = Collections.emptyList();
                  replicatedMysqlMinorVersions = Collections.emptyList();
                }
                DaemonAccessEntry dae = AoservDaemonServer.getDaemonAccessEntry(daemonAccessKey);
                if (dae.command != AoservDaemonProtocol.FAILOVER_FILE_REPLICATION) {
                  throw new IOException("Mismatched DaemonAccessEntry command, dae.command != " + AoservDaemonProtocol.FAILOVER_FILE_REPLICATION);
                }
                FailoverFileReplicationManager.failoverServer(
                    socket,
                    in,
                    out,
                    protocolVersion,
                    Integer.parseInt(dae.param1), // failover_file_replication.pkey
                    dae.param2, // fromServer
                    useCompression,
                    retention,
                    dae.param3, // backupPartition
                    fromServerYear,
                    fromServerMonth,
                    fromServerDay,
                    replicatedMysqlServers,
                    replicatedMysqlMinorVersions,
                    dae.param4 == null ? -1 : Integer.parseInt(dae.param4) // quota_gid
                );
                break;
              }
            case AoservDaemonProtocol.GET_AUTORESPONDER_CONTENT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_AUTORESPONDER_CONTENT, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_AUTORESPONDER_CONTENT");
                }
                String content = LinuxAccountManager.getAutoresponderContent(path);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(content);
                break;
              }
            case AoservDaemonProtocol.GET_AWSTATS_FILE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_AWSTATS_FILE, Thread=" + toString());
                }
                String siteName = in.readUTF();
                String path = in.readUTF();
                String queryString = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_AWSTATS_FILE");
                }
                AWStatsManager.getAwstatsFile(siteName, path, queryString, out);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.GET_CRON_TABLE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_CRON_TABLE, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_CRON_TABLE");
                }
                String cronTable = LinuxAccountManager.getCronTable(username);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(cronTable);
                break;
              }
            case AoservDaemonProtocol.GET_FAILOVER_FILE_REPLICATION_ACTIVITY:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_FAILOVER_FILE_REPLICATION_ACTIVITY, Thread=" + toString());
                }
                int replication = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_FAILOVER_FILE_REPLICATION_ACTIVITY");
                }
                FailoverFileReplicationManager.Activity activity = FailoverFileReplicationManager.getActivity(replication);
                long timeSince;
                String message;
                synchronized (activity) {
                  long time = activity.getTime();
                  if (time == -1) {
                    timeSince = -1;
                    message = "";
                  } else {
                    timeSince = System.currentTimeMillis() - time;
                    if (timeSince < 0) {
                      timeSince = 0;
                    }
                    message = activity.getMessage();
                  }
                }
                out.write(AoservDaemonProtocol.DONE);
                out.writeLong(timeSince);
                out.writeUTF(message);
                break;
              }
            case AoservDaemonProtocol.GET_NET_DEVICE_BONDING_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_NET_DEVICE_BONDING_REPORT, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_NET_DEVICE_BONDING_REPORT");
                }
                Device netDevice = connector.getNet().getDevice().get(id);
                if (netDevice == null) {
                  throw new SQLException("Unable to find Device: " + id);
                }
                String report = NetDeviceManager.getNetDeviceBondingReport(netDevice);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_NET_DEVICE_STATISTICS_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_NET_DEVICE_STATISTICS_REPORT, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_NET_DEVICE_STATISTICS_REPORT");
                }
                Device netDevice = connector.getNet().getDevice().get(id);
                if (netDevice == null) {
                  throw new SQLException("Unable to find Device: " + id);
                }
                String report = NetDeviceManager.getNetDeviceStatisticsReport(netDevice);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_3WARE_RAID_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_3WARE_RAID_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_3WARE_RAID_REPORT");
                }
                String report = ServerManager.get3wareRaidReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_MD_STAT_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_MD_RAID_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_MD_RAID_REPORT");
                }
                String report = ServerManager.getMdStatReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_MD_MISMATCH_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_MD_STAT_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_MD_STAT_REPORT");
                }
                String report = ServerManager.getMdMismatchReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_DRBD_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_DRBD_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_DRBD_REPORT");
                }
                String report = ServerManager.getDrbdReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_LVM_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_LVM_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_LVM_REPORT");
                }
                String[] report = ServerManager.getLvmReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report[0]);
                out.writeUTF(report[1]);
                out.writeUTF(report[2]);
                break;
              }
            case AoservDaemonProtocol.GET_HDD_TEMP_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_HDD_TEMP_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_HDD_TEMP_REPORT");
                }
                String report = ServerManager.getHddTempReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_HDD_MODEL_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_HDD_MODEL_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_HDD_MODEL_REPORT");
                }
                String report = ServerManager.getHddModelReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_FILESYSTEMS_CSV_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_FILESYSTEMS_CSV_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_FILESYSTEMS_CSV_REPORT");
                }
                String report = ServerManager.getFilesystemsCsvReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_AO_SERVER_LOADAVG_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_AO_SERVER_LOADAVG_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_AO_SERVER_LOADAVG_REPORT");
                }
                String report = ServerManager.getLoadAvgReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_AO_SERVER_MEMINFO_REPORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_AO_SERVER_MEMINFO_REPORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_AO_SERVER_MEMINFO_REPORT");
                }
                String report = ServerManager.getMemInfoReport();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.CHECK_PORT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing CHECK_PORT, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may CHECK_PORT");
                }
                com.aoapps.net.InetAddress ipAddress = com.aoapps.net.InetAddress.valueOf(in.readUTF());
                Port port;
                {
                  int portNum = in.readCompressedInt();
                  Protocol protocol;
                  if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_0) < 0) {
                    // Old protocol transferred lowercase
                    protocol = Protocol.valueOf(in.readUTF().toUpperCase(Locale.ROOT));
                  } else {
                    protocol = in.readEnum(Protocol.class);
                  }
                  port = Port.valueOf(portNum, protocol);
                }
                String appProtocol = in.readUTF();
                String monitoringParameters = in.readUTF();
                PortMonitor portMonitor = PortMonitor.getPortMonitor(
                    ipAddress,
                    port,
                    appProtocol,
                    Bind.decodeParameters(monitoringParameters)
                );
                logIoException = false;
                String result = portMonitor.checkPort();
                logIoException = true;
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(result);
                break;
              }
            case AoservDaemonProtocol.CHECK_SMTP_BLACKLIST:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing CHECK_SMTP_BLACKLIST, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may CHECK_SMTP_BLACKLIST");
                }
                com.aoapps.net.InetAddress sourceIp = com.aoapps.net.InetAddress.valueOf(in.readUTF());
                com.aoapps.net.InetAddress connectIp = com.aoapps.net.InetAddress.valueOf(in.readUTF());
                String result = NetDeviceManager.checkSmtpBlacklist(sourceIp, connectIp);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(result);
                break;
              }
            case AoservDaemonProtocol.CHECK_SSL_CERTIFICATE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing CHECK_SSL_CERTIFICATE, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                boolean allowCached;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_83_0) < 0) {
                  allowCached = true;
                } else {
                  allowCached = in.readBoolean();
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may CHECK_SSL_CERTIFICATE");
                }
                Certificate certificate = connector.getPki().getCertificate().get(id);
                if (certificate == null) {
                  throw new SQLException("Unable to find Certificate: " + id);
                }
                List<Certificate.Check> results = SslCertificateManager.checkSslCertificate(certificate, allowCached);
                out.write(AoservDaemonProtocol.NEXT);
                int size = results.size();
                out.writeCompressedInt(size);
                for (int i = 0; i < size; i++) {
                  Certificate.Check check = results.get(i);
                  out.writeUTF(check.getCheck());
                  out.writeUTF(check.getValue());
                  out.writeUTF(check.getAlertLevel().name());
                  out.writeNullUTF(check.getMessage());
                }
                break;
              }
            case AoservDaemonProtocol.GET_AO_SERVER_SYSTEM_TIME_MILLIS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_AO_SERVER_SYSTEM_TIME_MILLIS, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_AO_SERVER_SYSTEM_TIME_MILLIS");
                }
                long currentTime = System.currentTimeMillis();
                out.write(AoservDaemonProtocol.DONE);
                out.writeLong(currentTime);
                break;
              }
            case AoservDaemonProtocol.IS_PROCMAIL_MANUAL:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing IS_PROCMAIL_MANUAL, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may IS_PROCMAIL_MANUAL");
                }
                UserServer lsa = connector.getLinux().getUserServer().get(id);
                if (lsa == null) {
                  throw new SQLException("Unable to find UserServer: " + id);
                }
                boolean isManual = ProcmailManager.isManual(lsa);
                out.write(AoservDaemonProtocol.DONE);
                out.writeBoolean(isManual);
                break;
              }
            case AoservDaemonProtocol.GET_DISK_DEVICE_TOTAL_SIZE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_DISK_DEVICE_TOTAL_SIZE, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_DISK_DEVICE_TOTAL_SIZE");
                }
                long bytes = BackupManager.getDiskDeviceTotalSize(path);
                out.write(AoservDaemonProtocol.DONE);
                out.writeLong(bytes);
                break;
              }
            case AoservDaemonProtocol.GET_DISK_DEVICE_USED_SIZE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_DISK_DEVICE_USED_SIZE, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_DISK_DEVICE_USED_SIZE");
                }
                long bytes = BackupManager.getDiskDeviceUsedSize(path);
                out.write(AoservDaemonProtocol.DONE);
                out.writeLong(bytes);
                break;
              }
            case AoservDaemonProtocol.GET_EMAIL_LIST_FILE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_EMAIL_LIST_FILE, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_EMAIL_LIST_FILE");
                }
                String file = EmailListManager.getEmailListFile(path);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(file);
                break;
              }
            case AoservDaemonProtocol.GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
                }
                Tuple2<String, Integer> encryptedPassword = LinuxAccountManager.getEncryptedPassword(username);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(encryptedPassword.getElement1());
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_1) >= 0) {
                  Integer changeDate = encryptedPassword.getElement2();
                  out.writeCompressedInt(changeDate == null ? -1 : changeDate);
                }
                break;
              }
            case AoservDaemonProtocol.GET_ENCRYPTED_MYSQL_USER_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_ENCRYPTED_MYSQL_USER_PASSWORD, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                com.aoindustries.aoserv.client.mysql.User.Name username = com.aoindustries.aoserv.client.mysql.User.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_ENCRYPTED_MYSQL_USER_PASSWORD");
                }
                com.aoindustries.aoserv.client.mysql.Server mysqlServer = connector.getMysql().getServer().get(id);
                if (mysqlServer == null) {
                  throw new SQLException("Unable to find Server: " + id);
                }
                String encryptedPassword = MySQLUserManager.getEncryptedPassword(mysqlServer, username);
                out.write(AoservDaemonProtocol.DONE);
                // TODO: How is this used?  Should it just be a flag true/false if set?
                // TODO: Does the master actually need the raw value?  Is it used to copy passwords between accounts?  Should a copy password command exist?
                out.writeUTF(encryptedPassword);
                break;
              }
            case AoservDaemonProtocol.GET_HTTPD_SERVER_CONCURRENCY:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_HTTPD_SERVER_CONCURRENCY, Thread=" + toString());
                }
                int httpdServer = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_HTTPD_SERVER_CONCURRENCY");
                }
                int concurrency = HttpdServerManager.getHttpdServerConcurrency(httpdServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeCompressedInt(concurrency);
                break;
              }
            case AoservDaemonProtocol.GET_IMAP_FOLDER_SIZES:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_IMAP_FOLDER_SIZES, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                int numFolders = in.readCompressedInt();
                String[] folderNames = new String[numFolders];
                for (int c = 0; c < numFolders; c++) {
                  folderNames[c] = in.readUTF();
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_IMAP_FOLDER_SIZES");
                }
                long[] sizes = ImapManager.getImapFolderSizes(username, folderNames);
                out.write(AoservDaemonProtocol.DONE);
                for (int c = 0; c < numFolders; c++) {
                  out.writeLong(sizes[c]);
                }
                break;
              }
            case AoservDaemonProtocol.GET_INBOX_ATTRIBUTES:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_INBOX_ATTRIBUTES, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_INBOX_ATTRIBUTES");
                }
                long fileSize = ImapManager.getInboxSize(username);
                long lastModified = ImapManager.getInboxModified(username);
                out.write(AoservDaemonProtocol.DONE);
                out.writeLong(fileSize);
                out.writeLong(lastModified);
                break;
              }
            case AoservDaemonProtocol.GET_MRTG_FILE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_MRTG_FILE, Thread=" + toString());
                }
                String filename = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_MRTG_FILE");
                }
                MrtgManager.getMrtgFile(filename, out);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.GET_UPS_STATUS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_UPS_STATUS, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_UPS_STATUS");
                }
                String report = PhysicalServerManager.getUpsStatus();
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(report);
                break;
              }
            case AoservDaemonProtocol.GET_MYSQL_MASTER_STATUS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_MYSQL_MASTER_STATUS, Thread=" + toString());
                }
                int mysqlServer = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_MYSQL_MASTER_STATUS");
                }
                MySQLDatabaseManager.getMasterStatus(mysqlServer, out);
                break;
              }
            case AoservDaemonProtocol.GET_MYSQL_SLAVE_STATUS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_MYSQL_SLAVE_STATUS, Thread=" + toString());
                }
                PosixPath failoverRoot;
                {
                  String failoverRootStr = in.readUTF();
                  failoverRoot = failoverRootStr.isEmpty() ? null : PosixPath.valueOf(failoverRootStr);
                }
                int nestedOperatingSystemVersion = in.readCompressedInt();
                com.aoindustries.aoserv.client.mysql.Server.Name serverName;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_84_11) < 0) {
                  serverName = null;
                } else {
                  serverName = com.aoindustries.aoserv.client.mysql.Server.Name.valueOf(in.readUTF());
                }
                Port port = Port.valueOf(
                    in.readCompressedInt(),
                    Protocol.TCP
                );
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_MYSQL_SLAVE_STATUS");
                }
                MySQLDatabaseManager.getSlaveStatus(failoverRoot, nestedOperatingSystemVersion, serverName, port, out);
                break;
              }
            case AoservDaemonProtocol.GET_MYSQL_TABLE_STATUS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_MYSQL_TABLE_STATUS, Thread=" + toString());
                }
                PosixPath failoverRoot;
                {
                  String failoverRootStr = in.readUTF();
                  failoverRoot = failoverRootStr.isEmpty() ? null : PosixPath.valueOf(failoverRootStr);
                }
                int nestedOperatingSystemVersion = in.readCompressedInt();
                com.aoindustries.aoserv.client.mysql.Server.Name serverName;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_84_11) < 0) {
                  serverName = null;
                } else {
                  serverName = com.aoindustries.aoserv.client.mysql.Server.Name.valueOf(in.readUTF());
                }
                Port port = Port.valueOf(
                    in.readCompressedInt(),
                    Protocol.TCP
                );
                com.aoindustries.aoserv.client.mysql.Database.Name databaseName = com.aoindustries.aoserv.client.mysql.Database.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_MYSQL_TABLE_STATUS");
                }
                MySQLDatabaseManager.getTableStatus(failoverRoot, nestedOperatingSystemVersion, serverName, port, databaseName, out);
                break;
              }
            case AoservDaemonProtocol.CHECK_MYSQL_TABLES:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing CHECK_MYSQL_TABLES, Thread=" + toString());
                }
                PosixPath failoverRoot;
                {
                  String failoverRootStr = in.readUTF();
                  failoverRoot = failoverRootStr.isEmpty() ? null : PosixPath.valueOf(failoverRootStr);
                }
                final int nestedOperatingSystemVersion = in.readCompressedInt();
                com.aoindustries.aoserv.client.mysql.Server.Name serverName;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_84_11) < 0) {
                  serverName = null;
                } else {
                  serverName = com.aoindustries.aoserv.client.mysql.Server.Name.valueOf(in.readUTF());
                }
                Port port = Port.valueOf(
                    in.readCompressedInt(),
                    Protocol.TCP
                );
                com.aoindustries.aoserv.client.mysql.Database.Name databaseName = com.aoindustries.aoserv.client.mysql.Database.Name.valueOf(in.readUTF());
                int numTables = in.readCompressedInt();
                List<TableName> tableNames = new ArrayList<>(numTables);
                for (int c = 0; c < numTables; c++) {
                  tableNames.add(TableName.valueOf(in.readUTF()));
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may CHECK_MYSQL_TABLES");
                }
                MySQLDatabaseManager.checkTables(failoverRoot, nestedOperatingSystemVersion, serverName, port, databaseName, tableNames, out);
                break;
              }
            case AoservDaemonProtocol.GET_POSTGRES_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_POSTGRES_PASSWORD, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_POSTGRES_PASSWORD");
                }
                com.aoindustries.aoserv.client.postgresql.UserServer psu = connector.getPostgresql().getUserServer().get(id);
                if (psu == null) {
                  throw new SQLException("Unable to find UserServer: " + id);
                }
                String password = PostgresUserManager.getPassword(psu);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(password);
                break;
              }
            case AoservDaemonProtocol.GET_XEN_AUTO_START_LINKS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_XEN_AUTO_START_LINKS, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_XEN_AUTO_START_LINKS");
                }
                String[] links = ServerManager.getXenAutoStartLinks();
                out.write(AoservDaemonProtocol.DONE);
                out.writeCompressedInt(links.length);
                for (String link : links) {
                  out.writeUTF(link);
                }
                break;
              }
            case AoservDaemonProtocol.GRANT_DAEMON_ACCESS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GRANT_DAEMON_ACCESS, Thread=" + toString());
                }
                long accessKey = in.readLong();
                int command = in.readCompressedInt();
                String param1 = in.readBoolean() ? in.readUTF() : null;
                String param2 = in.readBoolean() ? in.readUTF() : null;
                String param3 = in.readBoolean() ? in.readUTF() : null;
                String param4 = in.readBoolean() ? in.readUTF() : null;
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GRANT_DAEMON_ACCESS");
                }
                AoservDaemonServer.grantDaemonAccess(accessKey, command, param1, param2, param3, param4);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            /*case AoservDaemonProtocol.INITIALIZE_HTTPD_SITE_PASSWD_FILE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing INITIALIZE_HTTPD_SITE_PASSWD_FILE, Thread="+toString());
                }
                int sitePkey = in.readCompressedInt();
                String username = in.readUTF();
                String encPassword = in.readUTF();
                if (key == null) {
                  throw new IOException("Only the master server may INITIALIZE_HTTPD_SITE_PASSWD_FILE");
                }
                HttpdManager.initializeHttpdSitePasswdFile(
                  sitePkey,
                  username,
                  encPassword
                );
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
             */
            case AoservDaemonProtocol.REMOVE_EMAIL_LIST:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing REMOVE_EMAIL_LIST, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may REMOVE_EMAIL_LIST");
                }
                EmailListManager.removeEmailListAddresses(path);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.RESTART_APACHE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing RESTART_APACHE, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may RESTART_APACHE");
                }
                HttpdServerManager.restartApache();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.RESTART_CRON:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing RESTART_CRON, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may RESTART_CRON");
                }
                ServerManager.restartCron();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.RESTART_MYSQL:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing RESTART_MYSQL, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may RESTART_MYSQL");
                }
                com.aoindustries.aoserv.client.mysql.Server mysqlServer = connector.getMysql().getServer().get(id);
                if (mysqlServer == null) {
                  throw new SQLException("Unable to find Server: " + id);
                }
                MySQLServerManager.restartMysql(mysqlServer);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.RESTART_POSTGRES:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing RESTART_POSTGRES, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may RESTART_POSTGRES");
                }
                com.aoindustries.aoserv.client.postgresql.Server ps = connector.getPostgresql().getServer().get(id);
                if (ps == null) {
                  throw new SQLException("Unable to find Server: " + id);
                }
                PostgresServerManager.restartPostgresql(ps);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.RESTART_XFS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing RESTART_XFS, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may RESTART_XFS");
                }
                ServerManager.restartXfs();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.RESTART_XVFB:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing RESTART_XVFB, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may RESTART_XVFB");
                }
                ServerManager.restartXvfb();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_AUTORESPONDER_CONTENT:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_AUTORESPONDER_CONTENT, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                String content = in.readBoolean() ? in.readUTF() : null;
                int uid = in.readCompressedInt();
                int gid = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SET_AUTORESPONDER_CONTENT");
                }
                LinuxAccountManager.setAutoresponderContent(
                    path,
                    content,
                    uid,
                    gid
                );
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_CRON_TABLE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_CRON_TABLE, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                String cronTable = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SET_CRON_TABLE");
                }
                LinuxAccountManager.setCronTable(username, cronTable);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                String encryptedPassword = in.readUTF();
                Integer changedDate;
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_1) >= 0) {
                  int i = in.readCompressedInt();
                  changedDate = i == -1 ? null : i;
                } else {
                  changedDate = null;
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SET_ENCRYPTED_LINUX_ACCOUNT_PASSWORD");
                }
                LinuxAccountManager.setEncryptedPassword(username, encryptedPassword, changedDate);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_EMAIL_LIST_FILE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_EMAIL_LIST_FILE, Thread=" + toString());
                }
                PosixPath path = PosixPath.valueOf(in.readUTF());
                String file = in.readUTF();
                int uid = in.readCompressedInt();
                int gid = in.readCompressedInt();
                int mode = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SET_EMAIL_LIST_FILE");
                }
                EmailListManager.setEmailListFile(
                    path,
                    file,
                    uid,
                    gid,
                    mode
                );
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_LINUX_SERVER_ACCOUNT_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_LINUX_SERVER_ACCOUNT_PASSWORD, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                String plainPassword = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SET_LINUX_SERVER_ACCOUNT_PASSWORD");
                }
                LinuxAccountManager.setPassword(username, plainPassword, true);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_MYSQL_USER_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_MYSQL_USER_PASSWORD, Thread=" + toString());
                }
                int pkdy = in.readCompressedInt();
                com.aoindustries.aoserv.client.mysql.User.Name username = com.aoindustries.aoserv.client.mysql.User.Name.valueOf(in.readUTF());
                String password = in.readBoolean() ? in.readUTF() : null;
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SET_MYSQL_USER_PASSWORD");
                }
                com.aoindustries.aoserv.client.mysql.Server mysqlServer = connector.getMysql().getServer().get(pkdy);
                if (mysqlServer == null) {
                  throw new SQLException("Unable to find Server: " + pkdy);
                }
                MySQLUserManager.setPassword(mysqlServer, username, password);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.SET_POSTGRES_USER_PASSWORD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SET_POSTGRES_USER_PASSWORD, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                String password = in.readBoolean() ? in.readUTF() : null;
                com.aoindustries.aoserv.client.postgresql.UserServer psu = connector.getPostgresql().getUserServer().get(id);
                if (psu == null) {
                  throw new SQLException("Unable to find UserServer: " + id);
                }
                PostgresUserManager.setPassword(psu, password, false);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_APACHE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_APACHE, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_APACHE");
                }
                HttpdServerManager.startApache();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_CRON:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_CRON, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_CRON");
                }
                ServerManager.startCron();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_DISTRO:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_DISTRO, Thread=" + toString());
                }
                boolean includeUser = in.readBoolean();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_DISTRO");
                }
                DistroManager.startDistro(includeUser);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_JVM:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_JVM, Thread=" + toString());
                }
                int sitePkey = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_JVM");
                }
                String message = HttpdSiteManager.startHttpdSite(sitePkey);
                out.write(AoservDaemonProtocol.DONE);
                out.writeBoolean(message != null);
                if (message != null) {
                  out.writeUTF(message);
                }
                break;
              }
            case AoservDaemonProtocol.START_MYSQL:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_MYSQL, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_MYSQL");
                }
                com.aoindustries.aoserv.client.mysql.Server mysqlServer = connector.getMysql().getServer().get(id);
                if (mysqlServer == null) {
                  throw new SQLException("Unable to find Server: " + id);
                }
                MySQLServerManager.startMysql(mysqlServer);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_POSTGRESQL:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_POSTGRESQL, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_POSTGRESQL");
                }
                com.aoindustries.aoserv.client.postgresql.Server ps = connector.getPostgresql().getServer().get(id);
                if (ps == null) {
                  throw new SQLException("Unable to find Server: " + id);
                }
                PostgresServerManager.startPostgresql(ps);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_XFS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_XFS, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_XFS");
                }
                ServerManager.startXfs();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.START_XVFB:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing START_XVFB, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may START_XVFB");
                }
                ServerManager.startXvfb();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.STOP_APACHE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_APACHE, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_APACHE");
                }
                HttpdServerManager.stopApache();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.STOP_CRON:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_CRON, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_CRON");
                }
                ServerManager.stopCron();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.STOP_JVM:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_JVM, Thread=" + toString());
                }
                int sitePkey = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_JVM");
                }
                String message = HttpdSiteManager.stopHttpdSite(sitePkey);
                out.write(AoservDaemonProtocol.DONE);
                out.writeBoolean(message != null);
                if (message != null) {
                  out.writeUTF(message);
                }
                break;
              }
            case AoservDaemonProtocol.STOP_MYSQL:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_MYSQL, Thread=" + toString());
                }
                int pkey = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_MYSQL");
                }
                com.aoindustries.aoserv.client.mysql.Server mysqlServer = connector.getMysql().getServer().get(pkey);
                if (mysqlServer == null) {
                  throw new SQLException("Unable to find Server: " + pkey);
                }
                MySQLServerManager.stopMysql(mysqlServer);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.STOP_POSTGRESQL:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_POSTGRESQL, Thread=" + toString());
                }
                int id = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_POSTGRESQL");
                }
                com.aoindustries.aoserv.client.postgresql.Server ps = connector.getPostgresql().getServer().get(id);
                if (ps == null) {
                  throw new SQLException("Unable to find Server: " + id);
                }
                PostgresServerManager.stopPostgresql(ps);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.STOP_XFS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_XFS, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_XFS");
                }
                ServerManager.stopXfs();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.STOP_XVFB:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing STOP_XVFB, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may STOP_XVFB");
                }
                ServerManager.stopXvfb();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.TAR_HOME_DIRECTORY:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing TAR_HOME_DIRECTORY, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may TAR_HOME_DIRECTORY");
                }
                LinuxAccountManager.tarHomeDirectory(out, username);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.UNTAR_HOME_DIRECTORY:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing UNTAR_HOME_DIRECTORY, Thread=" + toString());
                }
                User.Name username = User.Name.valueOf(in.readUTF());
                if (daemonKey == null) {
                  throw new IOException("Only the master server may UNTAR_HOME_DIRECTORY");
                }
                LinuxAccountManager.untarHomeDirectory(in, username);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.OLD_WAIT_FOR_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing OLD_WAIT_FOR_REBUILD, Thread=" + toString());
                }
                int oldTableId = in.readCompressedInt();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may OLD_WAIT_FOR_REBUILD");
                }
                if (protocolVersion.compareTo(AoservDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
                  throw new IOException(
                      "OLD_WAIT_FOR_REBUILD should not be used by "
                          + AoservDaemonProtocol.Version.VERSION_1_80_0
                          + " or newer"
                  );
                }
                switch (oldTableId) {
                  case AoservDaemonProtocol.OLD_HTTPD_SITES_TABLE_ID:
                    HttpdManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_LINUX_ACCOUNTS_TABLE_ID:
                    LinuxAccountManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_MYSQL_DATABASES_TABLE_ID:
                    MySQLDatabaseManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_MYSQL_DB_USERS_TABLE_ID:
                    MySQLDBUserManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_MYSQL_USERS_TABLE_ID:
                    MySQLUserManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_POSTGRES_DATABASES_TABLE_ID:
                    PostgresDatabaseManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_POSTGRES_SERVERS_TABLE_ID:
                    PostgresServerManager.waitForRebuild();
                    break;
                  case AoservDaemonProtocol.OLD_POSTGRES_USERS_TABLE_ID:
                    PostgresUserManager.waitForRebuild();
                    break;
                  default:
                    throw new IOException("Unable to wait for rebuild on table " + oldTableId);
                }
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_HTTPD_SITE_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_HTTPD_SITE_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_HTTPD_SITE_REBUILD");
                }
                HttpdManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_LINUX_ACCOUNT_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_LINUX_ACCOUNT_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_LINUX_ACCOUNT_REBUILD");
                }
                LinuxAccountManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_MYSQL_DATABASE_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_MYSQL_DATABASE_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_MYSQL_DATABASE_REBUILD");
                }
                MySQLDatabaseManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_MYSQL_DB_USER_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_MYSQL_DB_USER_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_MYSQL_DB_USER_REBUILD");
                }
                MySQLDBUserManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_MYSQL_SERVER_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_MYSQL_SERVER_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_MYSQL_SERVER_REBUILD");
                }
                MySQLServerManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_MYSQL_USER_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_MYSQL_USER_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_MYSQL_USER_REBUILD");
                }
                MySQLUserManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_POSTGRES_DATABASE_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_POSTGRES_DATABASE_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_POSTGRES_DATABASE_REBUILD");
                }
                PostgresDatabaseManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_POSTGRES_SERVER_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_POSTGRES_SERVER_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_POSTGRES_SERVER_REBUILD");
                }
                PostgresServerManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            case AoservDaemonProtocol.WAIT_FOR_POSTGRES_USER_REBUILD:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing WAIT_FOR_POSTGRES_USER_REBUILD, Thread=" + toString());
                }
                if (daemonKey == null) {
                  throw new IOException("Only the master server may WAIT_FOR_POSTGRES_USER_REBUILD");
                }
                PostgresUserManager.waitForRebuild();
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            // <editor-fold desc="Virtual Disks" defaultstate="collapsed">
            case AoservDaemonProtocol.VERIFY_VIRTUAL_DISK:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing VERIFY_VIRTUAL_DISK, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                String device = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may VERIFY_VIRTUAL_DISK");
                }
                long lastVerified = VirtualServerManager.verifyVirtualDisk(virtualServer, device);
                out.write(AoservDaemonProtocol.DONE);
                out.writeLong(lastVerified);
                break;
              }
            case AoservDaemonProtocol.UPDATE_VIRTUAL_DISK_LAST_UPDATED:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing UPDATE_VIRTUAL_DISK_LAST_UPDATED, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                String device = in.readUTF();
                long lastVerified = in.readLong();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may UPDATE_VIRTUAL_DISK_LAST_UPDATED");
                }
                VirtualServerManager.updateVirtualDiskLastVerified(virtualServer, device, lastVerified);
                out.write(AoservDaemonProtocol.DONE);
                break;
              }
            // </editor-fold>
            // <editor-fold desc="Virtual Servers" defaultstate="collapsed">
            case AoservDaemonProtocol.VNC_CONSOLE:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing VNC_CONSOLE, Thread=" + toString());
                }
                long daemonAccessKey = in.readLong();
                DaemonAccessEntry dae = AoservDaemonServer.getDaemonAccessEntry(daemonAccessKey);
                if (dae.command != AoservDaemonProtocol.VNC_CONSOLE) {
                  throw new IOException("Mismatched DaemonAccessEntry command, dae.command != " + AoservDaemonProtocol.VNC_CONSOLE);
                }
                VirtualServerManager.vncConsole(
                    socket,
                    in,
                    out,
                    dae.param1 // server_name
                );
                break;
              }
            case AoservDaemonProtocol.CREATE_VIRTUAL_SERVER:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing CREATE_VIRTUAL_SERVER, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may CREATE_VIRTUAL_SERVER");
                }
                String output = VirtualServerManager.createVirtualServer(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(output);
                break;
              }
            case AoservDaemonProtocol.REBOOT_VIRTUAL_SERVER:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing REBOOT_VIRTUAL_SERVER, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may REBOOT_VIRTUAL_SERVER");
                }
                String output = VirtualServerManager.rebootVirtualServer(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(output);
                break;
              }
            case AoservDaemonProtocol.SHUTDOWN_VIRTUAL_SERVER:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing SHUTDOWN_VIRTUAL_SERVER, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may SHUTDOWN_VIRTUAL_SERVER");
                }
                String output = VirtualServerManager.shutdownVirtualServer(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(output);
                break;
              }
            case AoservDaemonProtocol.DESTROY_VIRTUAL_SERVER:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing DESTROY_VIRTUAL_SERVER, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may DESTROY_VIRTUAL_SERVER");
                }
                String output = VirtualServerManager.destroyVirtualServer(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(output);
                break;
              }
            case AoservDaemonProtocol.PAUSE_VIRTUAL_SERVER:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing PAUSE_VIRTUAL_SERVER, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may PAUSE_VIRTUAL_SERVER");
                }
                String output = VirtualServerManager.pauseVirtualServer(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(output);
                break;
              }
            case AoservDaemonProtocol.UNPAUSE_VIRTUAL_SERVER:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing UNPAUSE_VIRTUAL_SERVER, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may UNPAUSE_VIRTUAL_SERVER");
                }
                String output = VirtualServerManager.unpauseVirtualServer(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeUTF(output);
                break;
              }
            case AoservDaemonProtocol.GET_VIRTUAL_SERVER_STATUS:
              {
                if (AoservDaemon.DEBUG) {
                  System.out.println("DEBUG: AoservDaemonServerThread performing GET_VIRTUAL_SERVER_STATUS, Thread=" + toString());
                }
                String virtualServer = in.readUTF();
                if (daemonKey == null) {
                  throw new IOException("Only the master server may GET_VIRTUAL_SERVER_STATUS");
                }
                int status = VirtualServerManager.getVirtualServerStatus(virtualServer);
                out.write(AoservDaemonProtocol.DONE);
                out.writeCompressedInt(status);
                break;
              }
            // </editor-fold>
            default:
              break Loop;
          }
        } catch (IOException err) {
          String message = err.getMessage();
          if (
              logIoException
                  && !"Connection reset by peer".equals(message)
          ) {
            logger.log(Level.SEVERE, null, err);
          }
          out.write(AoservDaemonProtocol.IO_EXCEPTION);
          out.writeUTF(message == null ? "null" : message);
        } catch (SQLException err) {
          logger.log(Level.SEVERE, null, err);
          out.write(AoservDaemonProtocol.SQL_EXCEPTION);
          String message = err.getMessage();
          out.writeUTF(message == null ? "null" : message);
        }
        out.flush();
      }
    } catch (EOFException err) {
      // Normal for abrupt connection closing
    } catch (SocketException err) {
      String message = err.getMessage();
      if (
          !"Socket closed".equals(message)
              && !"Socket is closed".equals(message)
      ) {
        logger.log(Level.SEVERE, null, err);
      }
    } catch (Exception t) {
      logger.log(Level.SEVERE, null, t);
    } finally {
      // Close the socket
      try {
        socket.close();
      } catch (IOException err) {
        // Ignore any socket close problems
      }
    }
  }
}
