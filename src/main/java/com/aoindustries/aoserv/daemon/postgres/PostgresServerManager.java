/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.postgres;

import com.aoapps.cron.CronDaemon;
import com.aoapps.cron.CronJob;
import com.aoapps.cron.Schedule;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.InetAddress;
import com.aoapps.net.Port;
import com.aoapps.sql.pool.AOConnectionPool;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.postgresql.Database;
import com.aoindustries.aoserv.client.postgresql.Server;
import com.aoindustries.aoserv.client.postgresql.User;
import com.aoindustries.aoserv.client.postgresql.Version;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.selinux.SEManagePort;
import java.io.File;
import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the PostgreSQL servers.
 *
 * @author  AO Industries, Inc.
 */
public final class PostgresServerManager extends BuilderThread implements CronJob {

  private static final Logger logger = Logger.getLogger(PostgresServerManager.class.getName());

  /**
   * The SELinux type for PostgreSQL.
   */
  private static final String SELINUX_TYPE = "postgresql_port_t";

  public static final File pgsqlDirectory = new File(Server.DATA_BASE_DIR.toString());

  private PostgresServerManager() {
    // Do nothing
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      com.aoindustries.aoserv.client.linux.Server thisServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (
          osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_X86_64
              && osvId != OperatingSystemVersion.ROCKY_9_X86_64
      ) {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      synchronized (rebuildLock) {
        Set<Port> postgresqlPorts = new HashSet<>();
        for (Server postgresServer : thisServer.getPostgresServers()) {
          postgresqlPorts.add(postgresServer.getBind().getPort());
          // TODO: Add and initialize any missing /var/lib/pgsql/name
          // TODO: Add/update any /etc/rc.d/init.d/postgresql-name
        }
        // Add any other local MySQL port (such as tunneled)
        for (Bind nb : thisServer.getHost().getNetBinds()) {
          String protocol = nb.getAppProtocol().getProtocol();
          if (AppProtocol.POSTGRESQL.equals(protocol)) {
            postgresqlPorts.add(nb.getPort());
          }
        }

        // Set postgresql_port_t SELinux ports.
        switch (osvId) {
          case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64:
            // SELinux left in Permissive state, not configured here
            break;
          case OperatingSystemVersion.CENTOS_7_X86_64:
          case OperatingSystemVersion.ROCKY_9_X86_64:
            {
              // Install /usr/sbin/semanage if missing
              if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON);
              } else if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON_UTILS);
              } else {
                throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
              }
              // Reconfigure SELinux ports
              if (SEManagePort.configure(postgresqlPorts, SELINUX_TYPE)) {
                // TODO: serversNeedingReloaded.addAll(...);
              }
              break;
            }
          default:
            throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }

        // TODO: restart any that need started/restarted
      }
      return true;
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  private static final Map<Integer, AOConnectionPool> pools = new HashMap<>();

  static AOConnectionPool getPool(Server ps) throws IOException, SQLException {
    AoservConnector connector  = AoservDaemon.getConnector();
    String version = ps.getVersion().getTechnologyVersion(connector).getVersion();
    synchronized (pools) {
      Integer pkeyObj = ps.getPkey();
      AOConnectionPool pool = pools.get(pkeyObj);
      if (pool == null) {
        Database pd = ps.getPostgresDatabase(Database.AOSERV);
        if (pd == null) {
          throw new SQLException("Unable to find Database: " + Database.AOSERV + " on " + ps.toString());
        }
        String jdbcUrl;
        if (
            version.startsWith(Version.VERSION_7_1 + '.')
                || version.startsWith(Version.VERSION_7_2 + '.')
                || version.startsWith(Version.VERSION_7_3 + '.')
                || version.startsWith(Version.VERSION_8_1 + '.')
                || version.startsWith(Version.VERSION_8_3 + '.')
                || version.startsWith(Version.VERSION_8_3 + 'R')
        ) {
          // Connect to IP (no 127.0.0.1/::1-only support)
          jdbcUrl = pd.getJdbcUrl(true);
        } else if (
            version.startsWith(Version.VERSION_9_4 + '.')
                || version.startsWith(Version.VERSION_9_4 + 'R')
                || version.startsWith(Version.VERSION_9_5 + '.')
                || version.startsWith(Version.VERSION_9_5 + 'R')
                || version.startsWith(Version.VERSION_9_6 + '.')
                || version.startsWith(Version.VERSION_9_6 + 'R')
                || version.startsWith(Version.VERSION_10 + '.')
                || version.startsWith(Version.VERSION_10 + 'R')
                || version.startsWith(Version.VERSION_11 + '.')
                || version.startsWith(Version.VERSION_11 + 'R')
                || version.startsWith(Version.VERSION_12 + '.')
                || version.startsWith(Version.VERSION_12 + 'R')
                || version.startsWith(Version.VERSION_13 + '.')
                || version.startsWith(Version.VERSION_13 + 'R')
                || version.startsWith(Version.VERSION_14 + '.')
                || version.startsWith(Version.VERSION_14 + 'R')
                || version.startsWith(Version.VERSION_15 + '.')
                || version.startsWith(Version.VERSION_15 + 'R')
        ) {
          // Connect to 127.0.0.1 or ::1
          StringBuilder jdbcUrlSb = new StringBuilder();
          jdbcUrlSb.append("jdbc:postgresql://");
          Bind nb = ps.getBind();
          InetAddress ia = nb.getIpAddress().getInetAddress();
          ProtocolFamily family = ia.getProtocolFamily();
          if (family.equals(StandardProtocolFamily.INET)) {
            jdbcUrlSb.append(InetAddress.LOOPBACK_IPV4.toBracketedString());
          } else if (family.equals(StandardProtocolFamily.INET6)) {
            jdbcUrlSb.append(InetAddress.LOOPBACK_IPV6.toBracketedString());
          } else {
            throw new AssertionError("Unexpected family: " + family);
          }
          Port port = nb.getPort();
          if (!port.equals(Server.DEFAULT_PORT)) {
            jdbcUrlSb
                .append(':')
                .append(port.getPort());
          }
          jdbcUrlSb
              .append('/')
              .append(URLEncoder.encode(pd.getName().toString(), StandardCharsets.UTF_8));
          jdbcUrl = jdbcUrlSb.toString();
        } else {
          throw new RuntimeException("Unexpected version of PostgreSQL: " + version);
        }
        Server.Name serverName = ps.getName();
        pool = new AOConnectionPool(
            pd.getJdbcDriver(),
            jdbcUrl,
            User.POSTGRES.toString(),
            AoservDaemonConfiguration.getPostgresPassword(serverName),
            AoservDaemonConfiguration.getPostgresConnections(serverName),
            AoservDaemonConfiguration.getPostgresMaxConnectionAge(serverName),
            logger
        );
        pools.put(pkeyObj, pool);
      }
      return pool;
    }
  }

  private static PostgresServerManager postgresServerManager;

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    com.aoindustries.aoserv.client.linux.Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
          // Nothing is done for these operating systems
          osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
              // Check config after OS check so config entry not needed
              && AoservDaemonConfiguration.isManagerEnabled(PostgresServerManager.class)
              && postgresServerManager == null
      ) {
        System.out.print("Starting PostgresServerManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          postgresServerManager = new PostgresServerManager();
          conn.getPostgresql().getServer().addTableListener(postgresServerManager, 0);
          // Register in CronDaemon
          CronDaemon.addCronJob(postgresServerManager, logger);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  public static void waitForRebuild() {
    if (postgresServerManager != null) {
      postgresServerManager.waitForBuild();
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild PostgreSQL Servers";
  }

  public static void restartPostgresql(Server ps) throws IOException, SQLException {
    ServerManager.controlProcess("postgresql-" + ps.getName(), "restart");
  }

  public static void startPostgresql(Server ps) throws IOException, SQLException {
    ServerManager.controlProcess("postgresql-" + ps.getName(), "start");
  }

  public static void stopPostgresql(Server ps) throws IOException, SQLException {
    ServerManager.controlProcess("postgresql-" + ps.getName(), "stop");
  }

  /**
   * This task will be ran once per day at 1:30am.
   */
  private static final Schedule schedule = (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year)
      -> minute == 30 && hour == 1;

  @Override
  public Schedule getSchedule() {
    return schedule;
  }

  @Override
  public int getThreadPriority() {
    return Thread.NORM_PRIORITY - 2;
  }

  /**
   * Rotates PostgreSQL log files.  Those older than one month are removed.
   *
   * <p>TODO: Should use standard log file rotation, so configuration still works if aoserv-daemon disabled or removed.</p>
   */
  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  public void run(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
    try {
      AoservConnector conn = AoservDaemon.getConnector();
      for (Server postgresServer : AoservDaemon.getThisServer().getPostgresServers()) {
        String version = postgresServer.getVersion().getTechnologyVersion(conn).getVersion();
        if (
            !version.startsWith(Version.VERSION_7_1 + '.')
                && !version.startsWith(Version.VERSION_7_2 + '.')
                && !version.startsWith(Version.VERSION_7_3 + '.')
                && !version.startsWith(Version.VERSION_8_0 + '.')
        ) {
          // Is 8.1 or newer, need to compress and rotate logs
          File logDirectory = new File("/var/opt/postgresql-" + postgresServer.getName() + "/log");
          String[] list = logDirectory.list();
          if (list != null) {
            for (String filename : list) {
              if (
                  !"stderr".equals(filename)
                      && !"stdout".equals(filename)
              ) {
                // Must be in postgresql-2006-02-14_011332.log format
                // TODO: *.csv, too
                if (
                    filename.length() != 32
                        || !"postgresql-".equals(filename.substring(0, 11))
                        || filename.charAt(11) < '0' || filename.charAt(11) > '9'
                        || filename.charAt(12) < '0' || filename.charAt(12) > '9'
                        || filename.charAt(13) < '0' || filename.charAt(13) > '9'
                        || filename.charAt(14) < '0' || filename.charAt(14) > '9'
                        || filename.charAt(15) != '-'
                        || filename.charAt(16) < '0' || filename.charAt(16) > '9'
                        || filename.charAt(17) < '0' || filename.charAt(17) > '9'
                        || filename.charAt(18) != '-'
                        || filename.charAt(19) < '0' || filename.charAt(19) > '9'
                        || filename.charAt(20) < '0' || filename.charAt(20) > '9'
                        || filename.charAt(21) != '_'
                        || filename.charAt(22) < '0' || filename.charAt(22) > '9'
                        || filename.charAt(23) < '0' || filename.charAt(23) > '9'
                        || filename.charAt(24) < '0' || filename.charAt(24) > '9'
                        || filename.charAt(25) < '0' || filename.charAt(25) > '9'
                        || filename.charAt(26) < '0' || filename.charAt(26) > '9'
                        || filename.charAt(27) < '0' || filename.charAt(27) > '9'
                        || filename.charAt(28) != '.'
                        || (
                        !(
                            // *.log
                            filename.charAt(29) == 'l'
                                || filename.charAt(30) == 'o'
                                || filename.charAt(31) == 'g'
                        ) && !(
                            // *.csv
                            filename.charAt(29) == 'c'
                                || filename.charAt(30) == 's'
                                || filename.charAt(31) == 'v'
                        )
                      )
                ) {
                  logger.log(Level.WARNING, null, new IOException("Warning, unexpected filename, will not remove: " + logDirectory.getPath() + "/" + filename));
                } else {
                  // Determine the timestamp of the file
                  GregorianCalendar fileDate = new GregorianCalendar();
                  fileDate.set(Calendar.YEAR, Integer.parseInt(filename.substring(11, 15)));
                  fileDate.set(Calendar.MONTH, Integer.parseInt(filename.substring(16, 18)) - 1);
                  fileDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(filename.substring(19, 21)));
                  fileDate.set(Calendar.HOUR_OF_DAY, 0);
                  fileDate.set(Calendar.MINUTE, 0);
                  fileDate.set(Calendar.SECOND, 0);
                  fileDate.set(Calendar.MILLISECOND, 0);

                  GregorianCalendar monthAgo = new GregorianCalendar();
                  monthAgo.add(Calendar.MONTH, -1);

                  if (fileDate.compareTo(monthAgo) < 0) {
                    new PosixFile(logDirectory, filename).delete();
                  }
                }
              }
            }
          }
        }
      }
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
    }
  }
}
