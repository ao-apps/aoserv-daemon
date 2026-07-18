/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024, 2025, 2026  AO Industries, Inc.
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

import static com.aoindustries.aoserv.daemon.mysql.MySQLServerManager.executeUpdate;

import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.DatabaseUser;
import com.aoindustries.aoserv.client.mysql.Permission;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.mysql.User;
import com.aoindustries.aoserv.client.mysql.UserServer;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Controls the MySQL DB Users.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLDBUserManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(MySQLDBUserManager.class.getName());

  private MySQLDBUserManager() {
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
          // Get the list of all db entries that should exist
          List<DatabaseUser> dbUsers = mysqlServer.getMysqlDbUsers();
          if (dbUsers.isEmpty()) {
            logger.severe("No users; refusing to rebuild config: " + mysqlServer);
          } else {
            Server.Version version = Server.Version.parse(mysqlServer.getVersion().getVersion());
            // Different versions of MySQL have different sets of system db users
            Set<Tuple2<Database.Name, User.Name>> systemDbUsers = version.getSystemDatabaseUsers();

            // Verify has all system db users
            Set<Tuple2<Database.Name, User.Name>> requiredDbUsers = new LinkedHashSet<>(systemDbUsers);
            for (DatabaseUser mdu : dbUsers) {
              if (
                  requiredDbUsers.remove(new Tuple2<>(mdu.getMysqlDatabase().getName(), mdu.getMysqlServerUser().getMysqlUser().getKey()))
                      && requiredDbUsers.isEmpty()
              ) {
                break;
              }
            }
            if (!requiredDbUsers.isEmpty()) {
              logger.severe("Required db users not found; refusing to rebuild config: " + mysqlServer + " → " + requiredDbUsers);
            } else {
              boolean needsFlush = false;

              // Get the connection to work through
              try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
                try {
                  // TODO: This does not update existing records; add update phase.
                  // TODO: As written, updates to mysql_users table (which are very rare) will be represented here.

                  // Get the list of all existing db entries
                  Set<Tuple2<Database.Name, User.Name>> existing = new HashSet<>();
                  String currentSql = null;
                  try (
                      Statement stmt = conn.createStatement();
                      ResultSet results = stmt.executeQuery(currentSql = "SELECT db, user FROM db")
                      ) {
                    while (results.next()) {
                      try {
                        Tuple2<Database.Name, User.Name> tuple = new Tuple2<>(
                            Database.Name.valueOf(results.getString(1)),
                            User.Name.valueOf(results.getString(2))
                        );
                        if (!existing.add(tuple)) {
                          throw new SQLException("Duplicate (db, user): " + tuple);
                        }
                      } catch (ValidationException e) {
                        throw new SQLException(e);
                      }
                    }
                  } catch (Error | RuntimeException | SQLException e) {
                    ErrorPrinter.addSql(e, currentSql);
                    throw e;
                  }

                  // Add the db entries that do not exist and should
                  for (DatabaseUser mdu : dbUsers) {
                    Database md = mdu.getMysqlDatabase();
                    Database.Name db = md.getName();
                    UserServer msu = mdu.getMysqlServerUser();
                    User.Name user = msu.getMysqlUser().getKey();

                    // These must both be on the same server !!!
                    if (!md.getMysqlServer().equals(msu.getMysqlServer())) {
                      throw new SQLException(
                          "Host mismatch in mysql_db_users.pkey="
                              + mdu.getPkey()
                              + ": ((mysql_databases.pkey="
                              + md.getPkey()
                              + ").mysql_server="
                              + md.getMysqlServer().getPkey()
                              + ") != ((mysql_server_users.pkey="
                              + msu.getPkey()
                              + ").mysql_server="
                              + msu.getMysqlServer().getPkey()
                              + ')'
                      );
                    }
                    Tuple2<Database.Name, User.Name> key = new Tuple2<>(db, user);
                    if (!existing.remove(key)) {
                      Set<Permission> databaseUserPermissions = version.getDatabaseUserPermissions();
                      if (version.supportsDirectGrantTableUpdates()) {
                        StringBuilder sql = new StringBuilder();
                        sql.append("INSERT INTO db (Host, Db, User, ")
                            .append(databaseUserPermissions.stream().map(Permission::getMysqlColumn).collect(Collectors.joining(", ")))
                            .append(") VALUES (?, ?, ?, ")
                            .append(databaseUserPermissions.stream().map(permission -> "?").collect(Collectors.joining(", ")))
                            .append(")");
                        List<String> params = new ArrayList<>();
                        String host =
                            user.equals(User.MYSQL_SESSION)
                                || user.equals(User.MYSQL_SYS)
                                ? "localhost"
                                : UserServer.ANY_HOST;
                        params.add(host);
                        params.add(db.toString());
                        params.add(user.toString());
                        for (Permission permission : databaseUserPermissions) {
                          params.add(permission.isDatabaseUserGranted(mdu) ? "Y" : "N");
                        }
                        currentSql = null;
                        try (PreparedStatement pstmt = conn.prepareStatement(currentSql = sql.toString())) {
                          // Add the db entry
                          int pos = 1;
                          for (String param : params) {
                            pstmt.setString(pos++, param);
                          }
                          pstmt.executeUpdate();
                        } catch (Error | RuntimeException | SQLException e) {
                          ErrorPrinter.addSql(e, currentSql);
                          throw e;
                        }
                        needsFlush = true;
                      }
                    } else {
                      throw new NotImplementedException("TODO: Update via GRANT/REVOKE (and other things) for MySQL " + version);
                    }
                  }

                  // Remove the extra db entries
                  if (!existing.isEmpty()) {
                    currentSql = null;
                    for (Tuple2<Database.Name, User.Name> key : existing) {
                      if (systemDbUsers.contains(key)) {
                        logger.log(
                            Level.WARNING,
                            null,
                            new SQLException("Refusing to delete system MySQL db user: " + key + " on " + mysqlServer)
                        );
                      } else {
                        if (version.supportsDirectGrantTableUpdates()) {
                          // Remove the extra db entry
                          executeUpdate(
                              conn,
                              "DELETE FROM db WHERE db=? AND user=?",
                              key.getElement1().toString(),
                              key.getElement2().toString()
                          );
                          needsFlush = true;
                        } else {
                          throw new NotImplementedException("TODO: Update via GRANT/REVOKE (and other things) for MySQL " + version);
                        }
                      }
                    }
                  }
                } catch (SQLException e) {
                  conn.abort(AoservDaemon.executorService);
                  throw e;
                }
              }
              if (needsFlush) {
                MySQLServerManager.flushPrivileges(mysqlServer);
              }
            }
          }
        }
      }
      return true;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  private static MySQLDBUserManager mysqlDBUserManager;

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
              && AoservDaemonConfiguration.isManagerEnabled(MySQLDBUserManager.class)
              && mysqlDBUserManager == null
      ) {
        System.out.print("Starting MySQLDBUserManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          mysqlDBUserManager = new MySQLDBUserManager();
          conn.getMysql().getDatabaseUser().addTableListener(mysqlDBUserManager, 0);
          conn.getMysql().getDatabase().addTableListener(mysqlDBUserManager, 0);
          conn.getMysql().getUserServer().addTableListener(mysqlDBUserManager, 0);
          conn.getMysql().getUser().addTableListener(mysqlDBUserManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  public static void waitForRebuild() {
    if (mysqlDBUserManager != null) {
      mysqlDBUserManager.waitForBuild();
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild MySQL DB Users";
  }
}
