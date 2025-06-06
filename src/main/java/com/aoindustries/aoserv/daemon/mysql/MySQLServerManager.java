/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2006-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.mysql;

import com.aoapps.net.Port;
import com.aoapps.sql.pool.AOConnectionPool;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.selinux.SEManagePort;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL servers.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLServerManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(MySQLServerManager.class.getName());

  /**
   * The SELinux type for MySQL.
   */
  private static final String SELINUX_TYPE = "mysqld_port_t";

  public static final File mysqlDirectory = new File(Server.DATA_BASE_DIR.toString());

  private MySQLServerManager() {
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
        Set<Port> mysqlPorts = new HashSet<>();
        for (Server mysqlServer : thisServer.getMysqlServers()) {
          mysqlPorts.add(mysqlServer.getBind().getPort());
          // TODO: Add and initialize any missing /var/lib/mysql/name
          // TODO: Add/update any /etc/rc.d/init.d/mysql-name
        }
        // Add any other local MySQL port (such as tunneled)
        for (Bind nb : thisServer.getHost().getNetBinds()) {
          String protocol = nb.getAppProtocol().getProtocol();
          if (AppProtocol.MYSQL.equals(protocol)) {
            mysqlPorts.add(nb.getPort());
          }
        }

        // Set mysql_port_t SELinux ports.
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
              if (SEManagePort.configure(mysqlPorts, SELINUX_TYPE)) {
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

  static AOConnectionPool getPool(Server ms) throws IOException, SQLException {
    synchronized (pools) {
      Integer i = ms.getPkey();
      AOConnectionPool pool = pools.get(i);
      if (pool == null) {
        Database md = ms.getMysqlDatabase(Database.MYSQL);
        if (md == null) {
          throw new SQLException("Unable to find Database: " + Database.MYSQL + " on " + ms.toString());
        }
        Port port = md.getMysqlServer().getBind().getPort();
        Server.Name serverName = ms.getName();
        String jdbcUrl;
        if (port == Server.DEFAULT_PORT) {
          jdbcUrl = "jdbc:mysql://127.0.0.1/" + URLEncoder.encode(md.getName().toString(), StandardCharsets.UTF_8) + "?useSSL=false";
        } else {
          jdbcUrl = "jdbc:mysql://127.0.0.1:" + port.getPort() + "/" + URLEncoder.encode(md.getName().toString(), StandardCharsets.UTF_8) + "?useSSL=false";
        }
        pool = new AOConnectionPool(
            AoservDaemonConfiguration.getMySqlDriver(),
            jdbcUrl,
            AoservDaemonConfiguration.getMySqlUser(serverName),
            AoservDaemonConfiguration.getMySqlPassword(serverName),
            AoservDaemonConfiguration.getMySqlConnections(serverName),
            AoservDaemonConfiguration.getMySqlMaxConnectionAge(serverName),
            logger
        );
        pools.put(i, pool);
      }
      return pool;
    }
  }

  private static MySQLServerManager mysqlServerManager;

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
              && AoservDaemonConfiguration.isManagerEnabled(MySQLServerManager.class)
              && mysqlServerManager == null
      ) {
        System.out.print("Starting MySQLServerManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          mysqlServerManager = new MySQLServerManager();
          conn.getMysql().getServer().addTableListener(mysqlServerManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  public static void waitForRebuild() {
    if (mysqlServerManager != null) {
      mysqlServerManager.waitForBuild();
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild MySQL Servers";
  }

  public static void restartMysql(Server ms) throws IOException, SQLException {
    ServerManager.controlProcess("mysql-" + ms.getName(), "restart");
  }

  public static void startMysql(Server ms) throws IOException, SQLException {
    ServerManager.controlProcess("mysql-" + ms.getName(), "start");
  }

  public static void stopMysql(Server ms) throws IOException, SQLException {
    ServerManager.controlProcess("mysql-" + ms.getName(), "stop");
  }

  private static final Object flushLock = new Object();

  static void flushPrivileges(Server mysqlServer) throws IOException, SQLException {
    OperatingSystemVersion osv = AoservDaemon.getThisServer().getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (flushLock) {
      /*
      This did not work properly, so we now invoke a native process instead.

      synchronized (flushLock) {
        try (Connection conn = getPool().getConnection()) {
          try {
            String currentSql = null;
            try (Statement stmt = conn.createStatement()) {
              stmt.executeUpdate(currentSql = "flush privileges");
            } catch (Error | RuntimeException | SQLException e) {
              ErrorPrinter.addSql(e, currentSql);
              throw e;
            }
          } catch (SQLException e) {
            conn.abort(AoservDaemon.executorService);
            throw e;
          }
        }
      }
      */
      String path;
      if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
        path = "/opt/mysql-" + mysqlServer.getMinorVersion() + "-i686/bin/mysqladmin";
      } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64
          || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
        path = "/opt/mysql-" + mysqlServer.getMinorVersion() + "/bin/mysqladmin";
      } else {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      Server.Name serverName = mysqlServer.getName();
      AoservDaemon.exec(
          path,
          "-h",
          "127.0.0.1",
          "-P",
          Integer.toString(mysqlServer.getBind().getPort().getPort()),
          "-u",
          AoservDaemonConfiguration.getMySqlUser(serverName),
          "--password=" + AoservDaemonConfiguration.getMySqlPassword(serverName), // TODO: use --login-path
          "reload"
      );
    }
  }
}
