/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.backup;

import com.aoapps.hodgepodge.io.FileExistsRule;
import com.aoapps.hodgepodge.io.FilesystemIteratorRule;
import com.aoapps.net.DomainName;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.backup.PosixFileEnvironment;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.backup.BackupPartition;
import com.aoindustries.aoserv.client.backup.FileReplication;
import com.aoindustries.aoserv.client.backup.MysqlReplication;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.scm.CvsRepository;
import com.aoindustries.aoserv.client.web.HttpdServer;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An <code>LinuxServerEnvironment</code> controls the backup system on
 * an <code>Server</code>.
 *
 * <p>TODO: Save bandwidth by doing prelink -u --undo-output=(tmpfile) (do this to read the file instead of direct I/O).
 *       Can possibly use the distro data to know which ones are prelinked.</p>
 *
 * <p>TODO: Use LVM snapshots when is a domU - also do MySQL lock to get steady-state snapshot</p>
 *
 * <p>TODO: Should we use some tricky stuff to dump the databases straight out as we iterate?  (Backups only)
 * TODO: Or, just dump to disk and remove when completed?  (Backups only)</p>
 *
 * <p>TODO: Adhere to the d attribute?  man chattr</p>
 *
 * @see  Server
 *
 * @author  AO Industries, Inc.
 */
public class LinuxServerEnvironment extends PosixFileEnvironment {

  private static final Logger logger = Logger.getLogger(LinuxServerEnvironment.class.getName());

  @Override
  public AoservConnector getConnector() throws IOException, SQLException {
    return AoservDaemon.getConnector();
  }

  @Override
  public Host getThisHost() throws IOException, SQLException {
    return AoservDaemon.getThisServer().getHost();
  }

  @Override
  public void preBackup(FileReplication ffr) throws IOException, SQLException {
    super.preBackup(ffr);
    BackupManager.cleanVarOldaccounts();

    // TODO: BackupManager.backupMysqlDatabases();

    // TODO: BackupManager.backupPostgresDatabases();
  }

  private final Map<FileReplication, List<com.aoindustries.aoserv.client.mysql.Server.Name>> replicatedMysqlServerses = new HashMap<>();
  private final Map<FileReplication, List<String>> replicatedMysqlMinorVersionses = new HashMap<>();

  @Override
  public void init(FileReplication ffr) throws IOException, SQLException {
    super.init(ffr);
    // Determine which MySQL Servers are replicated (not mirrored with failover code)
    short retention = ffr.getRetention().getDays();
    if (retention == 1) {
      Server toServer = ffr.getBackupPartition().getLinuxServer();
      List<MysqlReplication> fmrs = ffr.getFailoverMysqlReplications();
      List<com.aoindustries.aoserv.client.mysql.Server.Name> replicatedMysqlServers = new ArrayList<>(fmrs.size());
      List<String> replicatedMysqlMinorVersions = new ArrayList<>(fmrs.size());
      Logger myLogger = getLogger();
      boolean isDebug = myLogger.isLoggable(Level.FINE);
      for (MysqlReplication fmr : fmrs) {
        com.aoindustries.aoserv.client.mysql.Server mysqlServer = fmr.getMysqlServer();
        com.aoindustries.aoserv.client.mysql.Server.Name name = mysqlServer.getName();
        String minorVersion = mysqlServer.getMinorVersion();
        replicatedMysqlServers.add(name);
        replicatedMysqlMinorVersions.add(minorVersion);
        if (isDebug) {
          myLogger.logp(Level.FINE, getClass().getName(), "init", "runFailoverCopy to " + toServer + ", replicatedMysqlServer: " + name);
          myLogger.logp(Level.FINE, getClass().getName(), "init", "runFailoverCopy to " + toServer + ", replicatedMysqlMinorVersion: " + minorVersion);
        }
      }
      synchronized (replicatedMysqlServerses) {
        replicatedMysqlServerses.put(ffr, replicatedMysqlServers);
      }
      synchronized (replicatedMysqlMinorVersionses) {
        replicatedMysqlMinorVersionses.put(ffr, replicatedMysqlMinorVersions);
      }
    }
  }

  @Override
  public void cleanup(FileReplication ffr) throws IOException, SQLException {
    try {
      short retention = ffr.getRetention().getDays();
      if (retention == 1) {
        synchronized (replicatedMysqlServerses) {
          replicatedMysqlServerses.remove(ffr);
        }
        synchronized (replicatedMysqlMinorVersionses) {
          replicatedMysqlMinorVersionses.remove(ffr);
        }
      }
    } finally {
      super.cleanup(ffr);
    }
  }

  @Override
  public int getFailoverBatchSize(FileReplication ffr) throws IOException, SQLException {
    return AoservDaemon.getThisServer().getFailoverBatchSize();
  }

  @Override
  protected Map<String, FilesystemIteratorRule> getFilesystemIteratorRules(FileReplication ffr) throws IOException, SQLException {
    final Server thisServer = AoservDaemon.getThisServer();
    final short retention = ffr.getRetention().getDays();
    final OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    final int osvId = osv.getPkey();
    final Map<String, FilesystemIteratorRule> filesystemRules = new HashMap<>();
    if (
        osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
            && osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
            && osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            && osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
            && osvId != OperatingSystemVersion.CENTOS_7_X86_64
            && osvId != OperatingSystemVersion.ROCKY_9_X86_64
    ) {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
    filesystemRules.put("", FilesystemIteratorRule.OK); // Default to being included unless explicitly excluded
    filesystemRules.put("/.journal", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/aquota.group", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/aquota.user", FilesystemIteratorRule.SKIP);
    // filesystemRules.put("/backup", FilesystemIteratorRule.NO_RECURSE);
    filesystemRules.put("/boot/.journal", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/boot/lost+found", FilesystemIteratorRule.SKIP);
    if (
        osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
            || osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
            || osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
    ) {
      filesystemRules.put("/dev/log", FilesystemIteratorRule.SKIP);
      filesystemRules.put("/dev/pts/", FilesystemIteratorRule.SKIP);
      filesystemRules.put("/dev/shm/", FilesystemIteratorRule.SKIP);
    } else if (
        osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
            || osvId == OperatingSystemVersion.ROCKY_9_X86_64
    ) {
      // /dev is mounted devtmpfs now
      filesystemRules.put("/dev/", FilesystemIteratorRule.SKIP);
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
    filesystemRules.put("/etc/mail/statistics", FilesystemIteratorRule.SKIP);

    // Don't send /etc/lilo.conf for failovers - it can cause severe problems if a nested server has its kernel RPMs upgraded
    if (retention == 1) {
      filesystemRules.put("/etc/lilo.conf", FilesystemIteratorRule.SKIP);
    }

    filesystemRules.put(
        "/ao/",
        new FileExistsRule(
            new String[]{"/ao.aes128.img", "/ao.aes256.img", "/ao.copy.aes128.img", "/ao.copy.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put(
        "/ao.copy/",
        new FileExistsRule(
            new String[]{"/ao.aes128.img", "/ao.aes256.img", "/ao.copy.aes128.img", "/ao.copy.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put(
        "/encrypted/",
        new FileExistsRule(
            new String[]{"/encrypted.aes128.img", "/encrypted.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put(
        "/home/",
        new FileExistsRule(
            new String[]{"/home.aes128.img", "/home.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put(
        "/logs/",
        new FileExistsRule(
            new String[]{"/logs.aes128.img", "/logs.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put("/lost+found", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/mnt/cdrom", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/mnt/floppy", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/proc/", FilesystemIteratorRule.SKIP);
    if (
        osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
            || osvId == OperatingSystemVersion.ROCKY_9_X86_64
    ) {
      // /run is mounted tmpfs
      filesystemRules.put("/run/", FilesystemIteratorRule.SKIP);
    }
    filesystemRules.put("/selinux/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/swapfile.img", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/swapfile.aes128.img", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/swapfile.aes256.img", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/sys/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/tmp/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/usr/tmp/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/apache-mm/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/backup/", FilesystemIteratorRule.SKIP);
    // 2019-02-26: Now defaulting to including content of /var/backups, so they can be shipped off-site, too:
    // filesystemRules.put("/var/backups/", FilesystemIteratorRule.SKIP); // CentOS 7 /var/backups is a default
    filesystemRules.put("/var/failover/", FilesystemIteratorRule.SKIP);
    filesystemRules.put(
        CvsRepository.DEFAULT_CVS_DIRECTORY + "/",
        new FileExistsRule(
            new String[]{
                CvsRepository.DEFAULT_CVS_DIRECTORY + ".aes128.img",
                CvsRepository.DEFAULT_CVS_DIRECTORY + ".aes256.img"
            },
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put(
        "/var/git/",
        new FileExistsRule(
            new String[]{"/var/git.aes128.img", "/var/git.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put("/var/lib/fail2ban/fail2ban.sqlite3", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lib/mysql/.journal", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lib/mysql/lost+found", FilesystemIteratorRule.SKIP); // TODO: just iterate all mounts points and exclude lost+found instead?
    final DomainName hostname = thisServer.getHostname();
    List<com.aoindustries.aoserv.client.mysql.Server> mysqlServers = thisServer.getMysqlServers();
    for (com.aoindustries.aoserv.client.mysql.Server mysqlServer : mysqlServers)  {
      com.aoindustries.aoserv.client.mysql.Server.Name name = mysqlServer.getName();
      if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
        // Skip /var/lib/mysql/(name)/(hostname).pid
        filesystemRules.put("/var/lib/mysql/" + name + "/" + hostname + ".pid", FilesystemIteratorRule.SKIP);
        // Skip /var/lock/subsys/mysql-(name)
        filesystemRules.put("/var/lock/subsys/mysql-" + name, FilesystemIteratorRule.SKIP);
      } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64 || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
        // Skip /var/lib/mysql/tmp/(name)/
        filesystemRules.put(
            "/var/lib/mysql/tmp/" + mysqlServer.getName() + "/",
            FilesystemIteratorRule.SKIP
        );
      } else {
        throw new SQLException("Server found on unexpected operating system: " + osv);
      }
    }
    if (retention == 1) {
      // Failover-over mode
      List<com.aoindustries.aoserv.client.mysql.Server.Name> replicatedMysqlServers;
      synchronized (replicatedMysqlServerses) {
        replicatedMysqlServers = replicatedMysqlServerses.get(ffr);
      }
      // Skip files for any MySQL Host that is being replicated through MySQL replication
      for (com.aoindustries.aoserv.client.mysql.Server.Name name : replicatedMysqlServers) {
        // Skip /var/lib/mysql/(name)
        filesystemRules.put("/var/lib/mysql/" + name, FilesystemIteratorRule.SKIP);
        // Skip /var/log/mysql-(name)/
        filesystemRules.put("/var/log/mysql-" + name + "/", FilesystemIteratorRule.SKIP);
      }
    }
    if (
        osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
            || osvId == OperatingSystemVersion.ROCKY_9_X86_64 // TODO: Review once we have NFS mount in Rocky 9
    ) {
      // /var/lib/nfs/rpc_pipefs is mounted sunrpc
      filesystemRules.put("/var/lib/nfs/rpc_pipefs", FilesystemIteratorRule.SKIP);
    }
    filesystemRules.put(
        "/var/lib/pgsql/",
        new FileExistsRule(
            new String[]{"/var/lib/pgsql.aes128.img", "/var/lib/pgsql.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put("/var/lib/pgsql/.journal", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lib/pgsql/lost+found", FilesystemIteratorRule.SKIP); // TODO: just iterate all mounts points and exclude lost+found instead?
    List<com.aoindustries.aoserv.client.postgresql.Server> postgresServers = thisServer.getPostgresServers();
    for (com.aoindustries.aoserv.client.postgresql.Server postgresServer : postgresServers)  {
      com.aoindustries.aoserv.client.postgresql.Server.Name name = postgresServer.getName();
      // Skip /var/lib/pgsql/(name)/postmaster.pid
      filesystemRules.put("/var/lib/pgsql/" + name + "/postmaster.pid", FilesystemIteratorRule.SKIP);
      if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
        // Skip /var/lock/subsys/postgresql-(name)
        filesystemRules.put("/var/lock/subsys/postgresql-" + name, FilesystemIteratorRule.SKIP);
      } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64 || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
        // Nothing to skip
      } else {
        throw new SQLException("Server found on unexpected operating system: " + osv);
      }
    }
    filesystemRules.put("/var/lib/sasl2/saslauthd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lib/sasl2/mux.accept", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/aoserv-daemon", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/aoserv-jilter", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/clear_jvm_stats", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/clear_postgresql_pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/crond", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/daemons", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/kheader", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/httpd", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/identd", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/local", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/messagebus", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/network", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/numlock", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/route", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/saslauthd", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/sendmail", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/sm-client", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/spamd", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/spamassassin", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/sshd1", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/syslog", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/xfs", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/xinetd", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/lock/subsys/xvfb", FilesystemIteratorRule.SKIP);
    List<HttpdServer> httpdServers = thisServer.getHttpdServers();
    if (!httpdServers.isEmpty()) {
      if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
        for (HttpdServer hs : httpdServers) {
          String name = hs.getName();
          int num = name == null ? 1 : Integer.parseInt(name);
          filesystemRules.put("/var/log/httpd" + num + "/ssl_scache.sem", FilesystemIteratorRule.SKIP);
          filesystemRules.put("/var/lock/subsys/httpd" + num, FilesystemIteratorRule.SKIP);
          filesystemRules.put("/var/run/httpd" + num + ".pid", FilesystemIteratorRule.SKIP);
        }
      } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64 || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
        // Nothing to skip - Apache transient files in /run
      } else {
        throw new SQLException("HttpdServer found on unexpected operating system: " + osv);
      }
    }
    filesystemRules.put("/var/opt/aoserv-daemon/aoserv-daemon-java.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/opt/aoserv-daemon/aoserv-daemon.log", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/opt/aoserv-daemon/aoserv-daemon.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/opt/aoserv-daemon/oldaccounts/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/aoserv-daemon-java.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/aoserv-daemon.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/auditd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/crond.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/cron.reboot", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/dbus/system_bus_socket", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/gssproxy.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/gssproxy.sock", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/identd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/klogd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/lock/subsys/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/rpcbind.lock", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/rpcbind.sock", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/rpc.statd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/runlevel.dir", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/screen/", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/sendmail.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/sm-client.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/sm-notify.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/sshd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/syslogd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/tuned/tuned.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/xfs.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/xinetd.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/xtables.lock", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/var/run/xvfb.pid", FilesystemIteratorRule.SKIP);
    filesystemRules.put(
        "/var/spool/",
        new FileExistsRule(
            new String[]{"/var/spool.aes128.img", "/var/spool.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    if (retention > 1) {
      filesystemRules.put("/var/spool/clientmqueue/", FilesystemIteratorRule.SKIP);
    }
    filesystemRules.put("/var/spool/clientmqueue/sm-client.pid", FilesystemIteratorRule.SKIP);
    if (retention > 1) {
      filesystemRules.put("/var/spool/mqueue/", FilesystemIteratorRule.SKIP);
    }
    filesystemRules.put("/var/tmp/", FilesystemIteratorRule.SKIP);
    filesystemRules.put(
        "/www/",
        new FileExistsRule(
            new String[]{"/www.aes128.img", "/www.aes256.img"},
            FilesystemIteratorRule.SKIP,
            FilesystemIteratorRule.OK
        )
    );
    filesystemRules.put("/www/.journal", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/www/aquota.group", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/www/aquota.user", FilesystemIteratorRule.SKIP);
    filesystemRules.put("/www/lost+found", FilesystemIteratorRule.SKIP);
    // Do not replicate the backup directories
    for (BackupPartition bp : thisServer.getBackupPartitions()) {
      filesystemRules.put(bp.getPath().toString() + '/', FilesystemIteratorRule.SKIP);
    }
    // Manager-provided additional rules
    ImapManager.addFilesystemIteratorRules(ffr, filesystemRules);
    return filesystemRules;
  }

  @Override
  protected Map<String, FilesystemIteratorRule> getFilesystemIteratorPrefixRules(FileReplication ffr) throws IOException, SQLException {
    final Server thisServer = AoservDaemon.getThisServer();
    Map<String, FilesystemIteratorRule> filesystemPrefixRules = new HashMap<>();
    for (com.aoindustries.aoserv.client.mysql.Server mysqlServer : thisServer.getMysqlServers()) {
      com.aoindustries.aoserv.client.mysql.Server.Name name = mysqlServer.getName();
      filesystemPrefixRules.put("/var/lib/mysql/" + name + "/mysql-bin.", FilesystemIteratorRule.SKIP);
      filesystemPrefixRules.put("/var/lib/mysql/" + name + "/relay-log.", FilesystemIteratorRule.SKIP);
    }
    return filesystemPrefixRules;
  }

  @Override
  public InetAddress getDefaultSourceIpAddress() throws IOException, SQLException {
    Server thisServer = AoservDaemon.getThisServer();
    // Next, it will use the daemon bind address
    InetAddress sourceIpAddress = thisServer.getDaemonBind().getIpAddress().getInetAddress();
    // If daemon is binding to wildcard, then use source IP address of primary IP
    if (sourceIpAddress.isUnspecified()) {
      sourceIpAddress = thisServer.getPrimaryIpAddress().getInetAddress();
    }
    return sourceIpAddress;
  }

  @Override
  public List<com.aoindustries.aoserv.client.mysql.Server.Name> getReplicatedMysqlServers(FileReplication ffr) throws IOException, SQLException {
    synchronized (replicatedMysqlServerses) {
      return replicatedMysqlServerses.get(ffr);
    }
  }

  @Override
  public List<String> getReplicatedMysqlMinorVersions(FileReplication ffr) throws IOException, SQLException {
    synchronized (replicatedMysqlMinorVersionses) {
      return replicatedMysqlMinorVersionses.get(ffr);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Uses the fast random source from {@link AoservDaemon#getFastRandom()}.</p>
   */
  @Override
  public Random getFastRandom() {
    return AoservDaemon.getFastRandom();
  }

  @Override
  public Logger getLogger() {
    return logger;
  }
}
