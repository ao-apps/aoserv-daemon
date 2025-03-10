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

package com.aoindustries.aoserv.daemon.mysql;

import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL Hosts.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLHostManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(MySQLHostManager.class.getName());

  private MySQLHostManager() {
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

      AoservConnector connector = AoservDaemon.getConnector();
      synchronized (rebuildLock) {
        for (Server mysqlServer : connector.getMysql().getServer()) {
          String version = mysqlServer.getVersion().getVersion();
          // hosts no longer exists in MySQL 5.6.7+
          if (
              version.startsWith(Server.VERSION_4_0_PREFIX)
                  || version.startsWith(Server.VERSION_4_1_PREFIX)
                  || version.startsWith(Server.VERSION_5_0_PREFIX)
                  || version.startsWith(Server.VERSION_5_1_PREFIX)
          ) {
            boolean modified = false;
            // Get the connection to work through
            try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
              try {
                // Get the list of all existing hosts
                Set<String> existing = new HashSet<>();
                String currentSql = null;
                try (
                    Statement stmt = conn.createStatement();
                    ResultSet results = stmt.executeQuery(currentSql = "SELECT host FROM host")
                    ) {
                  while (results.next()) {
                    String host = results.getString(1);
                    if (!existing.add(host)) {
                      throw new SQLException("Duplicate host: " + host);
                    }
                  }
                } catch (Error | RuntimeException | SQLException e) {
                  ErrorPrinter.addSql(e, currentSql);
                  throw e;
                }

                // Get the list of all hosts that should exist
                Set<String> hosts = new HashSet<>();
                // Always include loopback, just in case of data errors
                hosts.add(IpAddress.LOOPBACK_IP);
                hosts.add("localhost");
                hosts.add("localhost.localdomain");
                // Include all of the local IP addresses
                for (Device nd : thisServer.getHost().getNetDevices()) {
                  for (IpAddress ia : nd.getIpAddresses()) {
                    InetAddress ip = ia.getInetAddress();
                    if (!ip.isUnspecified()) {
                      String ipString = ip.toString();
                      if (!hosts.contains(ipString)) {
                        hosts.add(ipString);
                      }
                    }
                  }
                }

                // Add the hosts that do not exist and should
                String insertSql;
                if (version.startsWith(Server.VERSION_4_0_PREFIX)) {
                  insertSql = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
                } else if (version.startsWith(Server.VERSION_4_1_PREFIX)) {
                  insertSql = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
                } else if (version.startsWith(Server.VERSION_5_0_PREFIX)) {
                  insertSql = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
                } else if (version.startsWith(Server.VERSION_5_1_PREFIX)) {
                  insertSql = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
                } else {
                  throw new SQLException("Unsupported MySQL version: " + version);
                }

                currentSql = null;
                try (PreparedStatement pstmt = conn.prepareStatement(currentSql = insertSql)) {
                  for (String hostname : hosts) {
                    if (!existing.remove(hostname)) {
                      // Add the host
                      pstmt.setString(1, hostname);
                      pstmt.executeUpdate();
                      modified = true;
                    }
                  }
                } catch (Error | RuntimeException | SQLException e) {
                  ErrorPrinter.addSql(e, currentSql);
                  throw e;
                }

                // Remove the extra hosts
                if (!existing.isEmpty()) {
                  currentSql = null;
                  try (PreparedStatement pstmt = conn.prepareStatement(currentSql = "DELETE FROM host WHERE host=?")) {
                    for (String dbName : existing) {
                      // Remove the extra host entry
                      pstmt.setString(1, dbName);
                      pstmt.executeUpdate();
                    }
                  } catch (Error | RuntimeException | SQLException e) {
                    ErrorPrinter.addSql(e, currentSql);
                    throw e;
                  }
                  modified = true;
                }
              } catch (SQLException e) {
                conn.abort(AoservDaemon.executorService);
                throw e;
              }
            }
            if (modified) {
              MySQLServerManager.flushPrivileges(mysqlServer);
            }
          }
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

  private static MySQLHostManager mysqlHostManager;

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
              && AoservDaemonConfiguration.isManagerEnabled(MySQLHostManager.class)
              && mysqlHostManager == null
      ) {
        System.out.print("Starting MySQLHostManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          mysqlHostManager = new MySQLHostManager();
          conn.getNet().getIpAddress().addTableListener(mysqlHostManager, 0);
          conn.getMysql().getServer().addTableListener(mysqlHostManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild MySQL Hosts";
  }
}
