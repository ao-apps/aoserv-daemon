/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025, 2026  AO Industries, Inc.
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Controls the MySQL Users.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLUserManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(MySQLUserManager.class.getName());

  /**
   * Validly formatted but invalid authentication string used for caching_sha2_password.
   * This is only used internally, externally still represented as {@link User#NO_PASSWORD_DB_VALUE}.
   */
  private static final String NO_PASSWORD_DB_VALUE_CACHING_SHA2 =
      "$A$005$THISISACOMBINATIONOFINVALIDSALTANDPASSWORDTHATMUSTNEVERBRBEUSED";

  private MySQLUserManager() {
    // Do nothing
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      // AoservConnector connector = AoservDaemon.getConnector();
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
        for (Server mysqlServer : thisServer.getMysqlServers()) {
          rebuildMysqlServer(mysqlServer);
        }
      }
      return true;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  private static void rebuildMysqlServer(Server mysqlServer) throws IOException, SQLException {
    assert Thread.holdsLock(rebuildLock);
    // Get the list of all users that should exist.  By getting the list and reusing it we have a snapshot of the configuration.
    List<UserServer> users = mysqlServer.getMysqlServerUsers();
    if (users.isEmpty()) {
      logger.severe("No users; refusing to rebuild config: " + mysqlServer);
      return;
    }
    // Must have root user
    boolean foundRoot = users.stream().anyMatch(user -> User.ROOT.equals(user.getMysqlUser_username()));
    if (!foundRoot) {
      logger.severe(User.ROOT + " user not found; refusing to rebuild config: " + mysqlServer);
      return;
    }
    final Server.Version version = Server.Version.parse(mysqlServer.getVersion().getVersion());

    MySQLDatabaseManager.BackupOnce backupOnce = new MySQLDatabaseManager.BackupOnce(mysqlServer);
    boolean needsFlush = false;

    // Get the connection to work through
    try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
      try {
        // Get the list of all existing users
        Set<Tuple2<String, User.Name>> existing = new HashSet<>();
        String currentSql = null;
        try (
            Statement stmt = conn.createStatement();
            ResultSet results = stmt.executeQuery(currentSql = "SELECT host, user FROM user")
            ) {
          try {
            while (results.next()) {
              Tuple2<String, User.Name> tuple = new Tuple2<>(
                  results.getString(1),
                  User.Name.valueOf(results.getString(2))
              );
              if (!existing.add(tuple)) {
                throw new SQLException("Duplicate (host, user): " + tuple);
              }
            }
          } catch (ValidationException e) {
            throw new SQLException(e);
          }
        } catch (Error | RuntimeException | SQLException e) {
          ErrorPrinter.addSql(e, currentSql);
          throw e;
        }
        // Warning: The order of add, update, then remove may not be changed without careful review of how existing is handled
        needsFlush |= addMissingUsers(mysqlServer, version, backupOnce, conn, users, existing);
        needsFlush |= updateExistingUsers(mysqlServer, version, backupOnce, conn, users, existing);
        needsFlush |= removeExtraUsers(mysqlServer, version, backupOnce, conn, existing);
      } catch (SQLException e) {
        conn.abort(AoservDaemon.executorService);
        throw e;
      }
    }
    // Note: This is done without holding Connection because it can callback to master, which could potentially deadlock.
    needsFlush |= disableAndEnableUsers(mysqlServer, version, backupOnce, users);
    if (needsFlush) {
      MySQLServerManager.flushPrivileges(mysqlServer);
    }
  }

  /**
   * Locked when either always-locked or disabled.
   */
  private static boolean isLocked(Server.Version version, UserServer msu) {
    return version.isAlwaysLocked(msu.getMysqlUser_username()) || msu.isDisabled();
  }

  /**
   * Adds any missing users with default (minimal) permissions.
   *
   * @param existing  Any users added are added to this set
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean addMissingUsers(Server mysqlServer, Server.Version version,
      MySQLDatabaseManager.BackupOnce backupOnce, Connection conn, List<UserServer> users,
      Set<Tuple2<String, User.Name>> existing) throws IOException, SQLException {
    boolean needsFlush = false;
    for (UserServer msu : users) {
      User mu = msu.getMysqlUser();
      String host = Objects.toString(msu.getHost(), "");
      User.Name username = mu.getKey();
      Tuple2<String, User.Name> key = new Tuple2<>(host, username);
      if (existing.add(key)) {
        // Add the user
        if (mu.isSpecial()) {
          logger.log(
              Level.WARNING,
              null,
              new SQLException("Refusing to create special user: " + username + " on " + mysqlServer.getName())
          );
        } else {
          // Add the users that do not exist and should
          backupOnce.backup();
          if (version.supportsDirectGrantTableUpdates()) {
            logger.info(() -> "Inserting '" + username + "'@'" + host + "' to mysql.user on " + mysqlServer);
            switch (version) {
              case VERSION_4_1:
              case VERSION_5_0:
                executeUpdate(
                    conn,
                    "INSERT INTO user (Host, User, Password, ssl_type, ssl_cipher, x509_issuer, x509_subject) VALUES (?,?,?,'','','','')",
                    host,
                    username.toString(),
                    User.NO_PASSWORD_DB_VALUE
                );
                break;
              case VERSION_5_6:
                executeUpdate(
                    conn,
                    "INSERT INTO user (Host, User, Password, ssl_type, ssl_cipher, x509_issuer, x509_subject, plugin) VALUES (?,?,?,'','','','','')",
                    host,
                    username.toString(),
                    User.NO_PASSWORD_DB_VALUE
                );
                break;
              case VERSION_5_7:
                executeUpdate(
                    conn,
                    "INSERT INTO user (Host, User, ssl_type, ssl_cipher, x509_issuer, x509_subject, authentication_string, password_last_changed, account_locked)"
                    + " VALUES (?,?,'','','','',?,NOW(),?)",
                    host,
                    username.toString(),
                    User.NO_PASSWORD_DB_VALUE,
                    isLocked(version, msu) ? "Y" : "N"
                );
                break;
              default:
                throw new SQLException("Unsupported MySQL version: " + version);
            }
            needsFlush = true;
          } else {
            logger.info(() -> "Creating user '" + username + "'@'" + host + "' on " + mysqlServer);
            executeUpdate(
                conn,
                "CREATE USER ?@? IDENTIFIED WITH caching_sha2_password AS ? ACCOUNT " + (isLocked(version, msu) ? "LOCK" : "UNLOCK"),
                username.toString(),
                host,
                NO_PASSWORD_DB_VALUE_CACHING_SHA2
            );
          }
        }
      }
    }
    return needsFlush;
  }

  private static void addUpdateWhere(Server.Version version, UserServer msu, User mu, String host, User.Name username,
      Set<Permission> userPermissions, boolean expectedLocked, StringBuilder sql, List<Object> params) {
    sql.append("\n"
        + "WHERE\n"
        + "  Host=?\n"
        + "  AND User=?\n");
    params.add(host);
    params.add(username.toString());
    sql.append("  AND (\n"
        + "    ");
    for (Permission permission : userPermissions) {
      sql.append(permission.getMysqlColumn()).append(" != ?\n"
          + "    OR ");
      params.add(permission.isUserGranted(mu) ? "Y" : "N");
    }
    sql.append("max_questions != ?\n"
          + "    OR max_updates != ?\n"
          + "    OR max_connections != ?");
    params.add(msu.getMaxQuestions());
    params.add(msu.getMaxUpdates());
    params.add(msu.getMaxConnections());
    if (version.hasMaxUserConnections()) {
      sql.append("\n"
          + "    OR max_user_connections != ?");
      params.add(msu.getMaxUserConnections());
    }
    if (version.hasAccountLocked()) {
      sql.append("\n"
          + "    OR account_locked != ?");
      params.add(expectedLocked ? "Y" : "N");
    }
    sql.append("\n"
          + "  )");
  }

  private static void setParams(PreparedStatement pstmt, Iterable<?> params) throws SQLException {
    int pos = 1;
    for (Object param : params) {
      if (param instanceof String) {
        pstmt.setString(pos++, (String) param);
      } else if (param instanceof Integer) {
        pstmt.setInt(pos++, (Integer) param);
      } else {
        throw new AssertionError("Unexpected parameter type: " + (param == null ? "null" : param.getClass().getName()));
      }
    }
  }

  /**
   * Updates user permissions and other settings.
   *
   * @param existing  Each user that is processed is removed from this set.
   *                  When this method returns, any remaining elements in {@code existing} are candidates for removal.
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean updateExistingUsers(Server mysqlServer, Server.Version version,
      MySQLDatabaseManager.BackupOnce backupOnce, Connection conn, List<UserServer> users,
      Set<Tuple2<String, User.Name>> existing) throws IOException, SQLException {
    boolean needsFlush = false;
    // Update existing users to proper values
    for (UserServer msu : users) {
      User mu = msu.getMysqlUser();
      String host = Objects.toString(msu.getHost(), "");
      User.Name username = mu.getKey();
      Tuple2<String, User.Name> key = new Tuple2<>(host, username);
      if (existing.remove(key)) {
        final Set<Permission> userPermissions = version.getUserPermissions();
        final boolean expectedLocked = isLocked(version, msu);
        if (version.supportsDirectGrantTableUpdates()) {
          // Never update special users
          if (!mu.isSpecial()) {
            StringBuilder sql = new StringBuilder();
            List<Object> params = new ArrayList<>();
            // Check if update required
            sql.append("SELECT COUNT(*) FROM user");
            addUpdateWhere(version, msu, mu, host, username, userPermissions, expectedLocked, sql, params);
            boolean updateRequired;
            String selectSql = sql.toString();
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
              // Update the user
              setParams(pstmt, params);
              int toUpdateCount;
              try (ResultSet result = pstmt.executeQuery()) {
                if (!result.next()) {
                  throw new SQLException("No row");
                }
                toUpdateCount = result.getInt(1);
              }
              if (toUpdateCount > 1) {
                throw new SQLException("Duplicate (host, user): " + key);
              }
              updateRequired = toUpdateCount != 0;
            } catch (Error | RuntimeException | SQLException e) {
              ErrorPrinter.addSql(e, selectSql);
              throw e;
            }
            if (updateRequired) {
              backupOnce.backup();
              logger.info(() -> "Updating '" + username + "'@'" + host + "' in mysql.user on " + mysqlServer);
              // Do the update
              sql.setLength(0);
              params.clear();
              sql.append("UPDATE user SET");
              for (Permission permission : userPermissions) {
                sql.append("\n  ").append(permission.getMysqlColumn()).append("=?,");
                params.add(permission.isUserGranted(mu) ? "Y" : "N");
              }
              sql.append("\n"
                    + "  max_questions=?,\n"
                    + "  max_updates=?,\n"
                    + "  max_connections=?");
              params.add(msu.getMaxQuestions());
              params.add(msu.getMaxUpdates());
              params.add(msu.getMaxConnections());
              if (version.hasMaxUserConnections()) {
                sql.append(",\n"
                    + "  max_user_connections=?");
                params.add(msu.getMaxUserConnections());
              }
              if (version.hasAccountLocked()) {
                sql.append(",\n"
                    + "  account_locked=?");
                params.add(expectedLocked ? "Y" : "N");
              }
              addUpdateWhere(version, msu, mu, host, username, userPermissions, expectedLocked, sql, params);
              String updateSql = sql.toString();
              try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                // Update the user
                setParams(pstmt, params);
                int updateCount = pstmt.executeUpdate();
                needsFlush = true;
                if (updateCount != 1) {
                  throw new SQLException("Unexpected number of rows updated: expected 1, got " + updateCount + " for (host, user): " + key);
                }
              } catch (Error | RuntimeException | SQLException e) {
                ErrorPrinter.addSql(e, updateSql);
                throw e;
              }
            }
          }
        } else {
          // Query current record, must exist
          StringBuilder sql = new StringBuilder();
          sql.append("SELECT ");
          // permissions
          sql.append(userPermissions.stream().map(Permission::getMysqlColumn).collect(Collectors.joining(", ")));
          final EnumSet<Permission> currentPermissions = EnumSet.noneOf(Permission.class);
          // max_*
          sql.append("max_questions, max_updates, max_connections");
          final int currentMaxQuestions;
          final int currentMaxUpdates;
          final int currentMaxConnections;
          // max_user_connections
          if (version.hasMaxUserConnections()) {
            sql.append(", max_user_connections");
          }
          final int currentMaxUserConnections;
          // account_locked
          if (version.hasAccountLocked()) {
            sql.append(", account_locked");
          }
          final boolean currentAccountLocked;
          sql.append(" FROM user WHERE Host = ? AND User = ?");
          String currentSql = null;
          try (PreparedStatement pstmt = conn.prepareStatement(currentSql = sql.toString())) {
            pstmt.setString(1, host);
            pstmt.setString(2, username.toString());
            try (ResultSet result = pstmt.executeQuery()) {
              if (!result.next()) {
                throw new SQLException("User not found (host, user): " + key);
              }
              // permissions
              for (Permission permission : userPermissions) {
                if ("Y".equals(result.getString(permission.getMysqlColumn()))) {
                  currentPermissions.add(permission);
                }
              }
              // max_*
              currentMaxQuestions = result.getInt("max_questions");
              currentMaxUpdates = result.getInt("max_updates");
              currentMaxConnections = result.getInt("max_connections");
              // max_user_connections
              if (version.hasMaxUserConnections()) {
                currentMaxUserConnections = result.getInt("max_user_connections");
              } else {
                currentMaxUserConnections = -1;
              }
              // account_locked
              if (version.hasAccountLocked()) {
                currentAccountLocked = "Y".equals(result.getString("account_locked"));
              } else {
                currentAccountLocked = false;
              }
              if (result.next()) {
                throw new SQLException("Duplicate (host, user): " + key);
              }
            }
          } catch (Error | RuntimeException | SQLException e) {
            ErrorPrinter.addSql(e, currentSql);
            throw e;
          }
          // Find all permission differences
          EnumSet<Permission> toRevoke = EnumSet.noneOf(Permission.class);
          EnumSet<Permission> toGrant = EnumSet.noneOf(Permission.class);
          for (Permission permission : userPermissions) {
            boolean currentGrant = currentPermissions.contains(permission);
            boolean expectedGrant = permission.isUserGranted(mu);
            if (currentGrant != expectedGrant) {
              (expectedGrant ? toGrant : toRevoke).add(permission);
            }
          }
          if (version.hasAccountLocked() && expectedLocked && !currentAccountLocked) {
            // Do lock if needed
            if (mu.isSpecial()) {
              logger.log(
                  Level.WARNING,
                  null,
                  new SQLException("Refusing to lock special user: " + username + " on " + mysqlServer.getName())
              );
            } else {
              backupOnce.backup();
              logger.info(() -> "Locking '" + username + "'@'" + host + "' on " + mysqlServer);
              executeUpdate(
                  conn,
                  "ALTER USER ?@? ACCOUNT LOCK",
                  username.toString(),
                  host
              );
            }
          }
          if (!toRevoke.isEmpty()) {
            // Do any revokes
            if (mu.isSpecial()) {
              logger.log(
                  Level.WARNING,
                  null,
                  new SQLException("Refusing to revoke from special user: " + username + " on " + mysqlServer.getName() + ": " + toRevoke)
              );
            } else {
              backupOnce.backup();
              String permissions = toRevoke.stream().map(Permission::getMysqlPrivilegeType).collect(Collectors.joining(", "));
              logger.info(() -> "Revoking " + permissions + " on *.* from '" + username + "'@'" + host + "' on " + mysqlServer);
              executeUpdate(
                  conn,
                  "REVOKE " + permissions + " ON *.* FROM ?@?",
                  username.toString(),
                  host
              );
            }
          }
          if (!toGrant.isEmpty()) {
            // Do any grants
            if (mu.isSpecial()) {
              logger.log(
                  Level.WARNING,
                  null,
                  new SQLException("Refusing to grant to special user: " + username + " on " + mysqlServer.getName() + ": " + toGrant)
              );
            } else {
              backupOnce.backup();
              String permissions = toGrant.stream().map(Permission::getMysqlPrivilegeType).collect(Collectors.joining(", "));
              logger.info(() -> "Granting " + permissions + " on *.* to '" + username + "'@'" + host + "' on " + mysqlServer);
              executeUpdate(
                  conn,
                  "GRANT " + permissions + " ON *.* TO ?@?",
                  username.toString(),
                  host
              );
            }
          }
          // Build ALTER USER
          sql.setLength(0);
          sql.append("ALTER USER ?@?");
          boolean didWith = false;
          int expectedMaxQuestions = msu.getMaxQuestions();
          if (currentMaxQuestions != expectedMaxQuestions) {
            backupOnce.backup();
            logger.info(() -> "Altering MAX_QUERIES_PER_HOUR to " + expectedMaxQuestions + " on '" + username + "'@'" + host + "' on " + mysqlServer);
            sql.append(" WITH MAX_QUERIES_PER_HOUR ").append(expectedMaxQuestions);
            didWith = true;
          }
          int expectedMaxUpdates = msu.getMaxUpdates();
          if (currentMaxUpdates != expectedMaxUpdates) {
            backupOnce.backup();
            logger.info(() -> "Altering MAX_UPDATES_PER_HOUR to " + expectedMaxUpdates + " on '" + username + "'@'" + host + "' on " + mysqlServer);
            sql.append(didWith ? " " : " WITH ").append("MAX_UPDATES_PER_HOUR ").append(expectedMaxUpdates);
            didWith = true;
          }
          int expectedMaxConnections = msu.getMaxConnections();
          if (currentMaxConnections != expectedMaxConnections) {
            backupOnce.backup();
            logger.info(() -> "Altering MAX_CONNECTIONS_PER_HOUR to " + expectedMaxConnections + " on '" + username + "'@'" + host + "' on " + mysqlServer);
            sql.append(didWith ? " " : " WITH ").append("MAX_CONNECTIONS_PER_HOUR ").append(expectedMaxConnections);
            didWith = true;
          }
          if (version.hasMaxUserConnections()) {
            int expectedMaxUserConnections = msu.getMaxUserConnections();
            if (currentMaxUserConnections != expectedMaxUserConnections) {
              backupOnce.backup();
              logger.info(() -> "Altering MAX_USER_CONNECTIONS to " + expectedMaxUserConnections + " on '" + username + "'@'" + host + "' on " + mysqlServer);
              sql.append(didWith ? " " : " WITH ").append("MAX_USER_CONNECTIONS ").append(expectedMaxUserConnections);
              didWith = true;
            }
          }
          boolean hasAlter = didWith;
          if (version.hasAccountLocked() && !expectedLocked && currentAccountLocked) {
            backupOnce.backup();
            logger.info(() -> "Unlocking '" + username + "'@'" + host + "' on " + mysqlServer);
            sql.append(" ACCOUNT UNLOCK");
            hasAlter = true;
          }
          if (hasAlter) {
            if (mu.isSpecial()) {
              logger.log(
                  Level.WARNING,
                  null,
                  new SQLException("Refusing to alter special user: " + username + " on " + mysqlServer.getName() + ": " + sql)
              );
            } else {
              executeUpdate(
                  conn,
                  sql.toString(),
                  username.toString(),
                  host
              );
            }
          }
        }
      }
    }
    return needsFlush;
  }

  /**
   * Removes all extra users.  {@linkplain User#isSpecial(com.aoindustries.aoserv.client.mysql.User.Name) special users}
   * will not be removed and a warning will be logged instead.
   *
   * @param extra  All users in this set will be removed.
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean removeExtraUsers(Server mysqlServer, Server.Version version,
      MySQLDatabaseManager.BackupOnce backupOnce, Connection conn,
      Set<Tuple2<String, User.Name>> extra) throws IOException, SQLException {
    boolean needsFlush = false;
    // Remove the extra users
    for (Tuple2<String, User.Name> key : extra) {
      // Remove the extra user@host entry
      String host = key.getElement1();
      User.Name user = key.getElement2();
      if (User.isSpecial(user)) {
        logger.log(
            Level.WARNING,
            null,
            new SQLException("Refusing to drop special user: " + user + " for host " + host + " on " + mysqlServer.getName())
        );
      } else {
        backupOnce.backup();
        if (version.supportsDirectGrantTableUpdates()) {
          logger.info(() -> "Deleting '" + user + "'@'" + host + "' from mysql.user on " + mysqlServer);
          executeUpdate(
              conn,
              "DELETE FROM user WHERE host=? AND user=?",
              host,
              user.toString()
          );
          needsFlush = true;
        } else {
          logger.info(() -> "Dropping user '" + user + "'@'" + host + "' on " + mysqlServer);
          executeUpdate(
              conn,
              "DROP USER ?@?",
              user.toString(),
              host
          );
        }
      }
    }
    return needsFlush;
  }

  /**
   * Disable and enable users.  This makes calls to master so no {@link Connection} passed-in.
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean disableAndEnableUsers(Server mysqlServer, Server.Version version,
      MySQLDatabaseManager.BackupOnce backupOnce, List<UserServer> users) throws IOException, SQLException {
    boolean needsFlush = false;
    // Disable and enable accounts
    if (!version.hasAccountLocked()) {
      // Older versions of MySQL are disabled by stashing the encrypted password and replacing it with an invalid hash
      for (UserServer msu : users) {
        if (!msu.isSpecial()) {
          String prePassword = msu.getPredisablePassword();
          if (!isLocked(version, msu)) {
            if (prePassword != null) {
              needsFlush |= setAuthenticationString(mysqlServer, version, backupOnce, msu.getMysqlUser().getKey(), prePassword);
              msu.setPredisablePassword(null);
            }
          } else {
            if (prePassword == null) {
              User.Name username = msu.getMysqlUser().getKey();
              msu.setPredisablePassword(getAuthenticationString(mysqlServer, username));
              needsFlush |= setAuthenticationString(mysqlServer, version, backupOnce, username, User.NO_PASSWORD);
            }
          }
        }
      }
    }
    return needsFlush;
  }

  public static String getAuthenticationString(Server mysqlServer, User.Name username) throws IOException, SQLException {
    if (User.isSpecial(username)) {
      throw new SQLException("Refusing to get the encrypted password for a special user: " + username + " on " + mysqlServer.getName());
    }
    final Server.Version version = Server.Version.parse(mysqlServer.getVersion().getVersion());
    final String sql;
    Function<String, String> compatibilityConverter = Function.identity();
    if (version.supportsDirectGrantTableUpdates()) {
      switch (version) {
        case VERSION_4_1:
        case VERSION_5_0:
        case VERSION_5_6:
          // Older MySQL: password stored in "password" column
          sql = "SELECT password FROM user WHERE user=?";
          break;
        case VERSION_5_7:
          // MySQL 5.7+: password stored in "authentication_string" column
          sql = "SELECT authentication_string FROM user WHERE user=?";
          break;
        default:
          throw new SQLException("Unsupported version of MySQL: " + version);
      }
    } else {
      // MySQL 8.0+: password stored in "authentication_string" column but NO_PASSWORD_DB_VALUE_CACHING_SHA2 used instead of User.NO_PASSWORD_DB_VALUE
      sql = "SELECT authentication_string FROM user WHERE user=?";
      compatibilityConverter = authenticationString -> NO_PASSWORD_DB_VALUE_CACHING_SHA2.equals(authenticationString) ? User.NO_PASSWORD_DB_VALUE : authenticationString;
    }
    try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection(true)) {
      try {
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
          try {
            pstmt.setString(1, username.toString());
            try (ResultSet result = pstmt.executeQuery()) {
              if (result.next()) {
                return compatibilityConverter.apply(result.getString(1));
              } else {
                throw new SQLException("No rows returned.");
              }
            }
          } catch (Error | RuntimeException | SQLException e) {
            ErrorPrinter.addSql(e, sql);
            throw e;
          }
        }
      } catch (SQLException e) {
        conn.abort(AoservDaemon.executorService);
        throw e;
      }
    }
  }

  /**
   * Gets the host value found in user table for the given user.
   *
   * @throws SQLException when not found or more than one row in user matches.
   */
  private static String getHostForUser(Connection conn, User.Name username) throws SQLException {
    String sql = "SELECT host FROM user WHERE user = ?";
    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, username.toString());
      try (ResultSet results = pstmt.executeQuery()) {
        Set<String> hosts = new HashSet<>();
        while (results.next()) {
          String host = results.getString(1);
          if (!hosts.add(host)) {
            throw new SQLException("Duplicate (host, user): (" + host + ", " + username + ")");
          }
        }
        if (hosts.isEmpty()) {
          throw new SQLException("User not found: " + username);
        }
        if (hosts.size() > 1) {
          throw new SQLException("More than one host found: " + username + ": " + hosts);
        }
        return hosts.iterator().next();
      }
    } catch (Error | RuntimeException | SQLException e) {
      ErrorPrinter.addSql(e, sql);
      throw e;
    }
  }

  /**
   * Sets the password, updating password_last_changed.
   */
  public static void setPassword(Server mysqlServer, User.Name username, String password) throws IOException, SQLException {
    if (User.isSpecial(username)) {
      throw new SQLException("Refusing to set the password for a special user: " + username + " on " + mysqlServer.getName());
    }
    MySQLDatabaseManager.backupDatabase(mysqlServer, Database.MYSQL);
    final Server.Version version = Server.Version.parse(mysqlServer.getVersion().getVersion());
    boolean needsFlush = false;
    // Get the connection to work through
    try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
      try {
        // Support "no password" as a method to disable the account
        final boolean isNoPassword = Objects.equals(password, User.NO_PASSWORD);
        if (version.supportsDirectGrantTableUpdates()) {
          switch (version) {
            case VERSION_4_1:
            case VERSION_5_0:
            case VERSION_5_6:
              if (isNoPassword) {
                logger.info(() -> "Removing password for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET password=? WHERE user=?",
                    User.NO_PASSWORD_DB_VALUE,
                    username.toString()
                );
              } else {
                logger.info(() -> "Setting password for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET password=PASSWORD(?) WHERE user=?",
                    password,
                    username.toString()
                );
              }
              break;
            case VERSION_5_7:
              if (isNoPassword) {
                logger.info(() -> "Removing authentication_string for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET authentication_string=?, password_last_changed=NOW() WHERE user=?",
                    User.NO_PASSWORD_DB_VALUE,
                    username.toString()
                );
              } else {
                logger.info(() -> "Setting authentication_string for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET authentication_string=PASSWORD(?), password_last_changed=NOW() WHERE user=?",
                    password,
                    username.toString()
                );
              }
              break;
            default:
              throw new SQLException("Unsupported version of MySQL: " + version);
          }
          needsFlush = true;
        } else {
          String host = getHostForUser(conn, username);
          if (isNoPassword) {
            logger.info(() -> "Removing authentication_string for '" + username + "'@'" + host + "' on " + mysqlServer);
            executeUpdate(
                conn,
                "ALTER USER ?@? IDENTIFIED WITH caching_sha2_password AS ?",
                username.toString(),
                host,
                NO_PASSWORD_DB_VALUE_CACHING_SHA2
            );
          } else {
            logger.info(() -> "Setting authentication_string for '" + username + "'@'" + host + "' on " + mysqlServer);
            executeUpdate(
                conn,
                "ALTER USER ?@? IDENTIFIED WITH caching_sha2_password BY ?",
                username.toString(),
                host,
                password
            );
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

  /**
   * Directly set authentication_string, password_last_changed unchanged.
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean setAuthenticationString(Server mysqlServer, Server.Version version,
      MySQLDatabaseManager.BackupOnce backupOnce, User.Name username, String authenticationString
  ) throws IOException, SQLException {
    if (User.isSpecial(username)) {
      throw new SQLException("Refusing to set the authentication string for a special user: " + username + " on " + mysqlServer.getName());
    }
    backupOnce.backup();
    boolean needsFlush = false;
    // Get the connection to work through
    try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
      try {
        // Support "no password" as a method to disable the account
        final boolean isNoPassword = Objects.equals(authenticationString, User.NO_PASSWORD);
        if (version.supportsDirectGrantTableUpdates()) {
          switch (version) {
            case VERSION_4_1:
            case VERSION_5_0:
            case VERSION_5_6:
              if (isNoPassword) {
                logger.info(() -> "Removing password for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET password=? WHERE user=?",
                    User.NO_PASSWORD_DB_VALUE,
                    username.toString()
                );
              } else {
                logger.info(() -> "Updating password for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET password=? WHERE user=?",
                    authenticationString,
                    username.toString()
                );
              }
              break;
            case VERSION_5_7:
              if (isNoPassword) {
                logger.info(() -> "Removing authentication_string for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET authentication_string=? WHERE user=?",
                    User.NO_PASSWORD_DB_VALUE,
                    username.toString()
                );
              } else {
                logger.info(() -> "Updating authentication_string for '" + username + "' in mysql.user on " + mysqlServer);
                executeUpdate(
                    conn,
                    "UPDATE user SET authentication_string=? WHERE user=?",
                    authenticationString,
                    username.toString()
                );
              }
              break;
            default:
              throw new SQLException("Unsupported version of MySQL: " + version);
          }
          needsFlush = true;
        } else {
          String host = getHostForUser(conn, username);
          if (isNoPassword) {
            logger.info(() -> "Removing authentication_string for '" + username + "'@'" + host + "' on " + mysqlServer);
            executeUpdate(
                conn,
                "ALTER USER ?@? IDENTIFIED WITH caching_sha2_password AS ?",
                username.toString(),
                host,
                isNoPassword ? NO_PASSWORD_DB_VALUE_CACHING_SHA2 : authenticationString
            );
          } else {
            logger.info(() -> "Updating authentication_string for '" + username + "'@'" + host + "' on " + mysqlServer);
            executeUpdate(
                conn,
                "ALTER USER ?@? IDENTIFIED WITH caching_sha2_password AS ?",
                username.toString(),
                host,
                isNoPassword ? NO_PASSWORD_DB_VALUE_CACHING_SHA2 : authenticationString
            );
          }
        }
      } catch (SQLException e) {
        conn.abort(AoservDaemon.executorService);
        throw e;
      }
    }
    return needsFlush;
  }

  private static MySQLUserManager mysqlUserManager;

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
              && AoservDaemonConfiguration.isManagerEnabled(MySQLUserManager.class)
              && mysqlUserManager == null
      ) {
        System.out.print("Starting MySQLUserManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          mysqlUserManager = new MySQLUserManager();
          conn.getMysql().getUserServer().addTableListener(mysqlUserManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  public static void waitForRebuild() {
    if (mysqlUserManager != null) {
      mysqlUserManager.waitForBuild();
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild MySQL Users";
  }
}
