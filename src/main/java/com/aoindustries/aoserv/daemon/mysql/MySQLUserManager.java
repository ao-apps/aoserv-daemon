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

import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

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
        needsFlush |= addMissingUsers(mysqlServer, version, conn, users, existing);
        needsFlush |= updateExistingUsers(version, conn, users, existing);
        needsFlush |= removeExtraUsers(mysqlServer, version, conn, existing);
      } catch (SQLException e) {
        conn.abort(AoservDaemon.executorService);
        throw e;
      }
    }
    // Note: This is done without holding Connection because it can callback to master, which could potentially deadlock.
    needsFlush |= disableAndEnableUsers(mysqlServer, version, users);
    if (needsFlush) {
      MySQLServerManager.flushPrivileges(mysqlServer);
    }
  }

  private static void setParams(PreparedStatement pstmt, Iterable<String> params) throws SQLException {
    int pos = 1;
    for (String param : params) {
      pstmt.setString(pos++, param);
    }
  }

  /**
   * Adds any missing users with default (minimal) permissions.
   *
   * @param existing  Any users added are added to this set
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean addMissingUsers(Server mysqlServer, Server.Version version, Connection conn, List<UserServer> users,
      Set<Tuple2<String, User.Name>> existing) throws IOException, SQLException {
    boolean needsFlush = false;
    for (UserServer msu : users) {
      User mu = msu.getMysqlUser();
      String host = msu.getHost();
      if (host == null) {
        host = "";
      }
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
          final String sql;
          final List<String> params = new ArrayList<>();
          switch (version) {
            case VERSION_4_1:
            case VERSION_5_0:
              sql = "INSERT INTO user (Host, User, Password, ssl_type, ssl_cipher, x509_issuer, x509_subject) VALUES (?,?,?,'','','','')";
              params.add(host);
              params.add(username.toString());
              params.add(User.NO_PASSWORD_DB_VALUE);
              needsFlush = true;
              break;
            case VERSION_5_6:
              sql = "INSERT INTO user (Host, User, Password, ssl_type, ssl_cipher, x509_issuer, x509_subject, plugin) VALUES (?,?,?,'','','','','')";
              params.add(host);
              params.add(username.toString());
              params.add(User.NO_PASSWORD_DB_VALUE);
              needsFlush = true;
              break;
            case VERSION_5_7:
              sql = "INSERT INTO user (Host, User, ssl_type, ssl_cipher, x509_issuer, x509_subject, authentication_string, password_last_changed, account_locked)"
                  + " VALUES (?,?,'','','','',?,NOW(),?)";
              params.add(host);
              params.add(username.toString());
              params.add(User.NO_PASSWORD_DB_VALUE);
              params.add(msu.isDisabled() ? "Y" : "N");
              needsFlush = true;
              break;
            case VERSION_8_4:
              sql = "CREATE USER ?@? IDENTIFIED WITH caching_sha2_password AS ? ACCOUNT " + (msu.isDisabled() ? "LOCK" : "UNLOCK");
              params.add(username.toString());
              params.add(host);
              params.add(NO_PASSWORD_DB_VALUE_CACHING_SHA2);
              break;
            default:
              throw new SQLException("Unsupported MySQL version: " + version);
          }
          String currentSql = null;
          try (PreparedStatement pstmt = conn.prepareStatement(currentSql = sql)) {
            setParams(pstmt, params);
            pstmt.executeUpdate();
          } catch (Error | RuntimeException | SQLException e) {
            ErrorPrinter.addSql(e, currentSql);
            throw e;
          }
        }
      }
    }
    return needsFlush;
  }

  /**
   * Updates user permissions and other settings.
   *
   * @param existing  Each user that is processed is removed from this set.
   *                  When this method returns, any remaining elements in {@code existing} are candidates for removal.
   *
   * @return  Returns {@code true} when {@link MySQLServerManager#flushPrivileges(com.aoindustries.aoserv.client.mysql.Server)} is required.
   */
  private static boolean updateExistingUsers(Server.Version version, Connection conn, List<UserServer> users,
      Set<Tuple2<String, User.Name>> existing) throws IOException, SQLException {
    boolean needsFlush = false;
    // Update existing users to proper values
    String updateSql;
    if (version == Server.Version.VERSION_4_1) {
      updateSql = "UPDATE user SET\n"
          + "  Select_priv=?,\n"
          + "  Insert_priv=?,\n"
          + "  Update_priv=?,\n"
          + "  Delete_priv=?,\n"
          + "  Create_priv=?,\n"
          + "  Drop_priv=?,\n"
          + "  Reload_priv=?,\n"
          + "  Shutdown_priv=?,\n"
          + "  Process_priv=?,\n"
          + "  File_priv=?,\n"
          + "  Grant_priv=?,\n"
          + "  References_priv=?,\n"
          + "  Index_priv=?,\n"
          + "  Alter_priv=?,\n"
          + "  Show_db_priv=?,\n"
          + "  Super_priv=?,\n"
          + "  Create_tmp_table_priv=?,\n"
          + "  Lock_tables_priv=?,\n"
          + "  Execute_priv=?,\n"
          + "  Repl_slave_priv=?,\n"
          + "  Repl_client_priv=?,\n"
          + "  max_questions=?,\n"
          + "  max_updates=?,\n"
          + "  max_connections=?\n"
          + "WHERE\n"
          + "  Host=?\n"
          + "  AND User=?\n"
          + "  AND (\n"
          + "    Select_priv != ?\n"
          + "    OR Insert_priv != ?\n"
          + "    OR Update_priv != ?\n"
          + "    OR Delete_priv != ?\n"
          + "    OR Create_priv != ?\n"
          + "    OR Drop_priv != ?\n"
          + "    OR Reload_priv != ?\n"
          + "    OR Shutdown_priv != ?\n"
          + "    OR Process_priv != ?\n"
          + "    OR File_priv != ?\n"
          + "    OR Grant_priv != ?\n"
          + "    OR References_priv != ?\n"
          + "    OR Index_priv != ?\n"
          + "    OR Alter_priv != ?\n"
          + "    OR Show_db_priv != ?\n"
          + "    OR Super_priv != ?\n"
          + "    OR Create_tmp_table_priv != ?\n"
          + "    OR Lock_tables_priv != ?\n"
          + "    OR Execute_priv != ?\n"
          + "    OR Repl_slave_priv != ?\n"
          + "    OR Repl_client_priv != ?\n"
          + "    OR max_questions != ?\n"
          + "    OR max_updates != ?\n"
          + "    OR max_connections != ?\n"
          + "  )";
    } else if (version == Server.Version.VERSION_5_0) {
      updateSql = "UPDATE user SET\n"
          + "  Select_priv=?,\n"
          + "  Insert_priv=?,\n"
          + "  Update_priv=?,\n"
          + "  Delete_priv=?,\n"
          + "  Create_priv=?,\n"
          + "  Drop_priv=?,\n"
          + "  Reload_priv=?,\n"
          + "  Shutdown_priv=?,\n"
          + "  Process_priv=?,\n"
          + "  File_priv=?,\n"
          + "  Grant_priv=?,\n"
          + "  References_priv=?,\n"
          + "  Index_priv=?,\n"
          + "  Alter_priv=?,\n"
          + "  Show_db_priv=?,\n"
          + "  Super_priv=?,\n"
          + "  Create_tmp_table_priv=?,\n"
          + "  Lock_tables_priv=?,\n"
          + "  Execute_priv=?,\n"
          + "  Repl_slave_priv=?,\n"
          + "  Repl_client_priv=?,\n"
          + "  Create_view_priv=?,\n"
          + "  Show_view_priv=?,\n"
          + "  Create_routine_priv=?,\n"
          + "  Alter_routine_priv=?,\n"
          + "  Create_user_priv=?,\n"
          + "  max_questions=?,\n"
          + "  max_updates=?,\n"
          + "  max_connections=?,\n"
          + "  max_user_connections=?\n"
          + "WHERE\n"
          + "  Host=?\n"
          + "  AND User=?\n"
          + "  AND (\n"
          + "    Select_priv != ?\n"
          + "    OR Insert_priv != ?\n"
          + "    OR Update_priv != ?\n"
          + "    OR Delete_priv != ?\n"
          + "    OR Create_priv != ?\n"
          + "    OR Drop_priv != ?\n"
          + "    OR Reload_priv != ?\n"
          + "    OR Shutdown_priv != ?\n"
          + "    OR Process_priv != ?\n"
          + "    OR File_priv != ?\n"
          + "    OR Grant_priv != ?\n"
          + "    OR References_priv != ?\n"
          + "    OR Index_priv != ?\n"
          + "    OR Alter_priv != ?\n"
          + "    OR Show_db_priv != ?\n"
          + "    OR Super_priv != ?\n"
          + "    OR Create_tmp_table_priv != ?\n"
          + "    OR Lock_tables_priv != ?\n"
          + "    OR Execute_priv != ?\n"
          + "    OR Repl_slave_priv != ?\n"
          + "    OR Repl_client_priv != ?\n"
          + "    OR Create_view_priv != ?\n"
          + "    OR Show_view_priv != ?\n"
          + "    OR Create_routine_priv != ?\n"
          + "    OR Alter_routine_priv != ?\n"
          + "    OR Create_user_priv != ?\n"
          + "    OR max_questions != ?\n"
          + "    OR max_updates != ?\n"
          + "    OR max_connections != ?\n"
          + "    OR max_user_connections != ?\n"
          + "  )";
    } else if (version == Server.Version.VERSION_5_6) {
      updateSql = "UPDATE user SET\n"
          + "  Select_priv=?,\n"
          + "  Insert_priv=?,\n"
          + "  Update_priv=?,\n"
          + "  Delete_priv=?,\n"
          + "  Create_priv=?,\n"
          + "  Drop_priv=?,\n"
          + "  Reload_priv=?,\n"
          + "  Shutdown_priv=?,\n"
          + "  Process_priv=?,\n"
          + "  File_priv=?,\n"
          + "  Grant_priv=?,\n"
          + "  References_priv=?,\n"
          + "  Index_priv=?,\n"
          + "  Alter_priv=?,\n"
          + "  Show_db_priv=?,\n"
          + "  Super_priv=?,\n"
          + "  Create_tmp_table_priv=?,\n"
          + "  Lock_tables_priv=?,\n"
          + "  Execute_priv=?,\n"
          + "  Repl_slave_priv=?,\n"
          + "  Repl_client_priv=?,\n"
          + "  Create_view_priv=?,\n"
          + "  Show_view_priv=?,\n"
          + "  Create_routine_priv=?,\n"
          + "  Alter_routine_priv=?,\n"
          + "  Create_user_priv=?,\n"
          + "  Event_priv=?,\n"
          + "  Trigger_priv=?,\n"
          + "  max_questions=?,\n"
          + "  max_updates=?,\n"
          + "  max_connections=?,\n"
          + "  max_user_connections=?\n"
          + "WHERE\n"
          + "  Host=?\n"
          + "  AND User=?\n"
          + "  AND (\n"
          + "    Select_priv != ?\n"
          + "    OR Insert_priv != ?\n"
          + "    OR Update_priv != ?\n"
          + "    OR Delete_priv != ?\n"
          + "    OR Create_priv != ?\n"
          + "    OR Drop_priv != ?\n"
          + "    OR Reload_priv != ?\n"
          + "    OR Shutdown_priv != ?\n"
          + "    OR Process_priv != ?\n"
          + "    OR File_priv != ?\n"
          + "    OR Grant_priv != ?\n"
          + "    OR References_priv != ?\n"
          + "    OR Index_priv != ?\n"
          + "    OR Alter_priv != ?\n"
          + "    OR Show_db_priv != ?\n"
          + "    OR Super_priv != ?\n"
          + "    OR Create_tmp_table_priv != ?\n"
          + "    OR Lock_tables_priv != ?\n"
          + "    OR Execute_priv != ?\n"
          + "    OR Repl_slave_priv != ?\n"
          + "    OR Repl_client_priv != ?\n"
          + "    OR Create_view_priv != ?\n"
          + "    OR Show_view_priv != ?\n"
          + "    OR Create_routine_priv != ?\n"
          + "    OR Alter_routine_priv != ?\n"
          + "    OR Create_user_priv != ?\n"
          + "    OR Event_priv != ?\n"
          + "    OR Trigger_priv != ?\n"
          + "    OR max_questions != ?\n"
          + "    OR max_updates != ?\n"
          + "    OR max_connections != ?\n"
          + "    OR max_user_connections != ?\n"
          + "  )";
    } else if (version == Server.Version.VERSION_5_7) {
      updateSql = "UPDATE user SET\n"
          + "  Select_priv=?,\n"
          + "  Insert_priv=?,\n"
          + "  Update_priv=?,\n"
          + "  Delete_priv=?,\n"
          + "  Create_priv=?,\n"
          + "  Drop_priv=?,\n"
          + "  Reload_priv=?,\n"
          + "  Shutdown_priv=?,\n"
          + "  Process_priv=?,\n"
          + "  File_priv=?,\n"
          + "  Grant_priv=?,\n"
          + "  References_priv=?,\n"
          + "  Index_priv=?,\n"
          + "  Alter_priv=?,\n"
          + "  Show_db_priv=?,\n"
          + "  Super_priv=?,\n"
          + "  Create_tmp_table_priv=?,\n"
          + "  Lock_tables_priv=?,\n"
          + "  Execute_priv=?,\n"
          + "  Repl_slave_priv=?,\n"
          + "  Repl_client_priv=?,\n"
          + "  Create_view_priv=?,\n"
          + "  Show_view_priv=?,\n"
          + "  Create_routine_priv=?,\n"
          + "  Alter_routine_priv=?,\n"
          + "  Create_user_priv=?,\n"
          + "  Event_priv=?,\n"
          + "  Trigger_priv=?,\n"
          + "  max_questions=?,\n"
          + "  max_updates=?,\n"
          + "  max_connections=?,\n"
          + "  max_user_connections=?,\n"
          + "  account_locked=?\n"
          + "WHERE\n"
          + "  Host=?\n"
          + "  AND User=?\n"
          + "  AND (\n"
          + "    Select_priv != ?\n"
          + "    OR Insert_priv != ?\n"
          + "    OR Update_priv != ?\n"
          + "    OR Delete_priv != ?\n"
          + "    OR Create_priv != ?\n"
          + "    OR Drop_priv != ?\n"
          + "    OR Reload_priv != ?\n"
          + "    OR Shutdown_priv != ?\n"
          + "    OR Process_priv != ?\n"
          + "    OR File_priv != ?\n"
          + "    OR Grant_priv != ?\n"
          + "    OR References_priv != ?\n"
          + "    OR Index_priv != ?\n"
          + "    OR Alter_priv != ?\n"
          + "    OR Show_db_priv != ?\n"
          + "    OR Super_priv != ?\n"
          + "    OR Create_tmp_table_priv != ?\n"
          + "    OR Lock_tables_priv != ?\n"
          + "    OR Execute_priv != ?\n"
          + "    OR Repl_slave_priv != ?\n"
          + "    OR Repl_client_priv != ?\n"
          + "    OR Create_view_priv != ?\n"
          + "    OR Show_view_priv != ?\n"
          + "    OR Create_routine_priv != ?\n"
          + "    OR Alter_routine_priv != ?\n"
          + "    OR Create_user_priv != ?\n"
          + "    OR Event_priv != ?\n"
          + "    OR Trigger_priv != ?\n"
          + "    OR max_questions != ?\n"
          + "    OR max_updates != ?\n"
          + "    OR max_connections != ?\n"
          + "    OR max_user_connections != ?\n"
          + "    OR account_locked != ?\n"
          + "  )";
    } else {
      throw new SQLException("Unsupported MySQL version: " + version);
    }

    String currentSql = null;
    try (PreparedStatement pstmt = conn.prepareStatement(currentSql = updateSql)) {
      for (UserServer msu : users) {
        User mu = msu.getMysqlUser();
        String host = msu.getHost();
        if (host == null) {
          host = "";
        }
        User.Name username = mu.getKey();
        Tuple2<String, User.Name> key = new Tuple2<>(host, username);
        if (existing.remove(key)) {
          int pos = 1;
          // Update the user
          pstmt.setString(pos++, mu.canSelect() ? "Y" : "N");
          pstmt.setString(pos++, mu.canInsert() ? "Y" : "N");
          pstmt.setString(pos++, mu.canUpdate() ? "Y" : "N");
          pstmt.setString(pos++, mu.canDelete() ? "Y" : "N");
          pstmt.setString(pos++, mu.canCreate() ? "Y" : "N");
          pstmt.setString(pos++, mu.canDrop() ? "Y" : "N");
          pstmt.setString(pos++, mu.canReload() ? "Y" : "N");
          pstmt.setString(pos++, mu.canShutdown() ? "Y" : "N");
          pstmt.setString(pos++, mu.canProcess() ? "Y" : "N");
          pstmt.setString(pos++, mu.canFile() ? "Y" : "N");
          pstmt.setString(pos++, mu.canGrant() ? "Y" : "N");
          pstmt.setString(pos++, mu.canReference() ? "Y" : "N");
          pstmt.setString(pos++, mu.canIndex() ? "Y" : "N");
          pstmt.setString(pos++, mu.canAlter() ? "Y" : "N");
          pstmt.setString(pos++, mu.canShowDb() ? "Y" : "N");
          pstmt.setString(pos++, mu.isSuper() ? "Y" : "N");
          pstmt.setString(pos++, mu.canCreateTempTable() ? "Y" : "N");
          pstmt.setString(pos++, mu.canLockTables() ? "Y" : "N");
          pstmt.setString(pos++, mu.canExecute() ? "Y" : "N");
          pstmt.setString(pos++, mu.isReplicationSlave() ? "Y" : "N");
          pstmt.setString(pos++, mu.isReplicationClient() ? "Y" : "N");
          if (
              version == Server.Version.VERSION_5_0
                  || version == Server.Version.VERSION_5_6
                  || version == Server.Version.VERSION_5_7
          ) {
            pstmt.setString(pos++, mu.canCreateView() ? "Y" : "N");
            pstmt.setString(pos++, mu.canShowView() ? "Y" : "N");
            pstmt.setString(pos++, mu.canCreateRoutine() ? "Y" : "N");
            pstmt.setString(pos++, mu.canAlterRoutine() ? "Y" : "N");
            pstmt.setString(pos++, mu.canCreateUser() ? "Y" : "N");
            if (
                version == Server.Version.VERSION_5_6
                    || version == Server.Version.VERSION_5_7
            ) {
              pstmt.setString(pos++, mu.canEvent() ? "Y" : "N");
              pstmt.setString(pos++, mu.canTrigger() ? "Y" : "N");
            }
          }
          pstmt.setInt(pos++, msu.getMaxQuestions());
          pstmt.setInt(pos++, msu.getMaxUpdates());
          pstmt.setInt(pos++, msu.getMaxConnections());
          if (
              version == Server.Version.VERSION_5_0
                  || version == Server.Version.VERSION_5_6
                  || version == Server.Version.VERSION_5_7
          ) {
            pstmt.setInt(pos++, msu.getMaxUserConnections());
          }
          boolean locked =
              username.equals(User.MYSQL_SESSION)
                  || username.equals(User.MYSQL_SYS)
                  || msu.isDisabled();
          if (version == Server.Version.VERSION_5_7) {
            pstmt.setString(pos++, locked ? "Y" : "N");
          }
          // where
          pstmt.setString(pos++, host);
          pstmt.setString(pos++, username.toString());
          pstmt.setString(pos++, mu.canSelect() ? "Y" : "N");
          pstmt.setString(pos++, mu.canInsert() ? "Y" : "N");
          pstmt.setString(pos++, mu.canUpdate() ? "Y" : "N");
          pstmt.setString(pos++, mu.canDelete() ? "Y" : "N");
          pstmt.setString(pos++, mu.canCreate() ? "Y" : "N");
          pstmt.setString(pos++, mu.canDrop() ? "Y" : "N");
          pstmt.setString(pos++, mu.canReload() ? "Y" : "N");
          pstmt.setString(pos++, mu.canShutdown() ? "Y" : "N");
          pstmt.setString(pos++, mu.canProcess() ? "Y" : "N");
          pstmt.setString(pos++, mu.canFile() ? "Y" : "N");
          pstmt.setString(pos++, mu.canGrant() ? "Y" : "N");
          pstmt.setString(pos++, mu.canReference() ? "Y" : "N");
          pstmt.setString(pos++, mu.canIndex() ? "Y" : "N");
          pstmt.setString(pos++, mu.canAlter() ? "Y" : "N");
          pstmt.setString(pos++, mu.canShowDb() ? "Y" : "N");
          pstmt.setString(pos++, mu.isSuper() ? "Y" : "N");
          pstmt.setString(pos++, mu.canCreateTempTable() ? "Y" : "N");
          pstmt.setString(pos++, mu.canLockTables() ? "Y" : "N");
          pstmt.setString(pos++, mu.canExecute() ? "Y" : "N");
          pstmt.setString(pos++, mu.isReplicationSlave() ? "Y" : "N");
          pstmt.setString(pos++, mu.isReplicationClient() ? "Y" : "N");
          if (
              version == Server.Version.VERSION_5_0
                  || version == Server.Version.VERSION_5_6
                  || version == Server.Version.VERSION_5_7
          ) {
            pstmt.setString(pos++, mu.canCreateView() ? "Y" : "N");
            pstmt.setString(pos++, mu.canShowView() ? "Y" : "N");
            pstmt.setString(pos++, mu.canCreateRoutine() ? "Y" : "N");
            pstmt.setString(pos++, mu.canAlterRoutine() ? "Y" : "N");
            pstmt.setString(pos++, mu.canCreateUser() ? "Y" : "N");
            if (
                version == Server.Version.VERSION_5_6
                    || version == Server.Version.VERSION_5_7
            ) {
              pstmt.setString(pos++, mu.canEvent() ? "Y" : "N");
              pstmt.setString(pos++, mu.canTrigger() ? "Y" : "N");
            }
          }
          pstmt.setInt(pos++, msu.getMaxQuestions());
          pstmt.setInt(pos++, msu.getMaxUpdates());
          pstmt.setInt(pos++, msu.getMaxConnections());
          if (
              version == Server.Version.VERSION_5_0
                  || version == Server.Version.VERSION_5_6
                  || version == Server.Version.VERSION_5_7
          ) {
            pstmt.setInt(pos++, msu.getMaxUserConnections());
          }
          if (version == Server.Version.VERSION_5_7) {
            pstmt.setString(pos++, locked ? "Y" : "N");
          }
          int updateCount = pstmt.executeUpdate();
          if (updateCount > 0) {
            needsFlush = true;
          }
        }
      }
    } catch (Error | RuntimeException | SQLException e) {
      ErrorPrinter.addSql(e, currentSql);
      throw e;
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
  private static boolean removeExtraUsers(Server mysqlServer, Server.Version version, Connection conn,
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
            new SQLException("Refusing to drop special user: " + user + " user for host " + host + " on " + mysqlServer.getName())
        );
      } else {
        final String sql;
        final String param1;
        final String param2;
        switch (version) {
          case VERSION_4_1:
          case VERSION_5_0:
          case VERSION_5_6:
          case VERSION_5_7:
            sql = "DELETE FROM user WHERE host=? AND user=?";
            param1 = host;
            param2 = user.toString();
            needsFlush = true;
            break;
          case VERSION_8_4:
            sql = "DROP USER ?@?";
            param1 = user.toString();
            param2 = host;
            break;
          default:
            throw new SQLException("Unsupported version of MySQL: " + version);
        }
        String currentSql = null;
        try (PreparedStatement pstmt = conn.prepareStatement(currentSql = sql)) {
          pstmt.setString(1, param1);
          pstmt.setString(2, param2);
          pstmt.executeUpdate();
        } catch (Error | RuntimeException | SQLException e) {
          ErrorPrinter.addSql(e, currentSql);
          throw e;
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
  private static boolean disableAndEnableUsers(Server mysqlServer, Server.Version version, List<UserServer> users) throws IOException, SQLException {
    boolean needsFlush = false;
    // Disable and enable accounts
    switch (version) {
      case VERSION_4_1:
      case VERSION_5_0:
      case VERSION_5_6:
        // Older versions of MySQL are disabled by stashing the encrypted password and replacing it with an invalid hash
        for (UserServer msu : users) {
          if (!msu.isSpecial()) {
            String prePassword = msu.getPredisablePassword();
            if (!msu.isDisabled()) {
              if (prePassword != null) {
                needsFlush |= setAuthenticationString(mysqlServer, version, msu.getMysqlUser().getKey(), prePassword);
                msu.setPredisablePassword(null);
              }
            } else {
              if (prePassword == null) {
                User.Name username = msu.getMysqlUser().getKey();
                msu.setPredisablePassword(getAuthenticationString(mysqlServer, username));
                needsFlush |= setAuthenticationString(mysqlServer, version, username, User.NO_PASSWORD);
              }
            }
          }
        }
        break;
      case VERSION_5_7:
      case VERSION_8_4:
        // MySQL 5.7+ support "account_locked" column, set above.
        // Nothing to do here.
        break;
      default:
        throw new SQLException("Unsupported version of MySQL: " + version);
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
      case VERSION_8_4:
        // MySQL 8.4+: password stored in "authentication_string" column but NO_PASSWORD_DB_VALUE_CACHING_SHA2 used instead of User.NO_PASSWORD_DB_VALUE
        sql = "SELECT authentication_string FROM user WHERE user=?";
        compatibilityConverter = authenticationString -> NO_PASSWORD_DB_VALUE_CACHING_SHA2.equals(authenticationString) ? User.NO_PASSWORD_DB_VALUE : authenticationString;
        break;
      default:
        throw new SQLException("Unsupported version of MySQL: " + version);
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
   * Sets the password, updating password_last_changed.
   */
  public static void setPassword(Server mysqlServer, User.Name username, String password) throws IOException, SQLException {
    if (User.isSpecial(username)) {
      throw new SQLException("Refusing to set the password for a special user: " + username + " on " + mysqlServer.getName());
    }
    final Server.Version version = Server.Version.parse(mysqlServer.getVersion().getVersion());
    boolean needsFlush = false;
    // Get the connection to work through
    try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
      try {
        // Support "no password" as a method to disable the account
        final boolean isNoPassword = Objects.equals(password, User.NO_PASSWORD);
        final String sql;
        final String param1;
        final String param2;
        switch (version) {
          case VERSION_4_1:
          case VERSION_5_0:
          case VERSION_5_6:
            if (isNoPassword) {
              sql = "UPDATE user SET password=? WHERE user=?";
              param1 = User.NO_PASSWORD_DB_VALUE;
              param2 = username.toString();
            } else {
              sql = "UPDATE user SET password=PASSWORD(?) WHERE user=?";
              param1 = password;
              param2 = username.toString();
            }
            needsFlush = true;
            break;
          case VERSION_5_7:
            if (isNoPassword) {
              sql = "UPDATE user SET authentication_string=?, password_last_changed=NOW() WHERE user=?";
              param1 = User.NO_PASSWORD_DB_VALUE;
              param2 = username.toString();
            } else {
              sql = "UPDATE user SET authentication_string=PASSWORD(?), password_last_changed=NOW() WHERE user=?";
              param1 = password;
              param2 = username.toString();
            }
            needsFlush = true;
            break;
          case VERSION_8_4:
            if (isNoPassword) {
              sql = "ALTER USER ?@'' IDENTIFIED WITH caching_sha2_password AS ?";
              param1 = username.toString();
              param2 = NO_PASSWORD_DB_VALUE_CACHING_SHA2;
            } else {
              sql = "ALTER USER ?@'' IDENTIFIED WITH caching_sha2_password BY ?";
              param1 = username.toString();
              param2 = password;
            }
            break;
          default:
            throw new SQLException("Unsupported version of MySQL: " + version);
        }
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
          pstmt.setString(1, param1);
          pstmt.setString(2, param2);
          pstmt.executeUpdate();
        } catch (Error | RuntimeException | SQLException e) {
          ErrorPrinter.addSql(e, sql);
          throw e;
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
  private static boolean setAuthenticationString(Server mysqlServer, Server.Version version, User.Name username, String authenticationString) throws IOException, SQLException {
    if (User.isSpecial(username)) {
      throw new SQLException("Refusing to set the authentication string for a special user: " + username + " on " + mysqlServer.getName());
    }
    boolean needsFlush = false;
    // Get the connection to work through
    try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
      try {
        // Support "no password" as a method to disable the account
        final boolean isNoPassword = Objects.equals(authenticationString, User.NO_PASSWORD);
        final String sql;
        final String param1;
        final String param2;
        switch (version) {
          case VERSION_4_1:
          case VERSION_5_0:
          case VERSION_5_6:
            sql = "UPDATE user SET password=? WHERE user=?";
            param1 = isNoPassword ? User.NO_PASSWORD_DB_VALUE : authenticationString;
            param2 = username.toString();
            needsFlush = true;
            break;
          case VERSION_5_7:
            sql = "UPDATE user SET authentication_string=? WHERE user=?";
            param1 = isNoPassword ? User.NO_PASSWORD_DB_VALUE : authenticationString;
            param2 = username.toString();
            needsFlush = true;
            break;
          case VERSION_8_4:
            sql = "ALTER USER ?@'' IDENTIFIED WITH caching_sha2_password AS ?";
            param1 = username.toString();
            param2 = isNoPassword ? NO_PASSWORD_DB_VALUE_CACHING_SHA2 : authenticationString;
            break;
          default:
            throw new SQLException("Unsupported version of MySQL: " + version);
        }
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
          pstmt.setString(1, param1);
          pstmt.setString(2, param2);
          pstmt.executeUpdate();
        } catch (Error | RuntimeException | SQLException e) {
          ErrorPrinter.addSql(e, sql);
          throw e;
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
