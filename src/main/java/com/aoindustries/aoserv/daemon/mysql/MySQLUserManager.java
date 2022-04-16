/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.mysql.User;
import com.aoindustries.aoserv.client.mysql.UserServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL Users.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLUserManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(MySQLUserManager.class.getName());

	private MySQLUserManager() {
		// Do nothing
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			//AOServConnector connector = AOServDaemon.getConnector();
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized (rebuildLock) {
				for(Server mysqlServer : thisServer.getMySQLServers()) {
					// Get the list of all users that should exist.  By getting the list and reusing it we have a snapshot of the configuration.
					List<UserServer> users = mysqlServer.getMySQLServerUsers();
					if(users.isEmpty()) {
						logger.severe("No users; refusing to rebuild config: " + mysqlServer);
					} else {
						// Must have root user
						boolean foundRoot = false;
						for(UserServer user : users) {
							if(user.getMySQLUser().getKey().equals(User.ROOT)) {
								foundRoot = true;
								break;
							}
						}
						if(!foundRoot) {
							logger.severe(User.ROOT + " user not found; refusing to rebuild config: " + mysqlServer);
						} else {
							final String version=mysqlServer.getVersion().getVersion();

							boolean modified = false;

							// Get the connection to work through
							try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
								try {
									// Get the list of all existing users
									Set<Tuple2<String, User.Name>> existing = new HashSet<>();
									String currentSQL = null;
									try (
										Statement stmt = conn.createStatement();
										ResultSet results = stmt.executeQuery(currentSQL = "SELECT host, user FROM user")
									) {
										try {
											while (results.next()) {
												Tuple2<String, User.Name> tuple = new Tuple2<>(
													results.getString(1),
													User.Name.valueOf(results.getString(2))
												);
												if(!existing.add(tuple)) throw new SQLException("Duplicate (host, user): " + tuple);
											}
										} catch(ValidationException e) {
											throw new SQLException(e);
										}
									} catch(Error | RuntimeException | SQLException e) {
										ErrorPrinter.addSQL(e, currentSQL);
										throw e;
									}

									// Update existing users to proper values
									String updateSQL;
									if(version.startsWith(Server.VERSION_4_0_PREFIX)) {
										updateSQL = "UPDATE user SET\n"
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
												+ "    Select_priv!=?\n"
												+ "    OR Insert_priv!=?\n"
												+ "    OR Update_priv!=?\n"
												+ "    OR Delete_priv!=?\n"
												+ "    OR Create_priv!=?\n"
												+ "    OR Drop_priv!=?\n"
												+ "    OR Reload_priv!=?\n"
												+ "    OR Shutdown_priv!=?\n"
												+ "    OR Process_priv!=?\n"
												+ "    OR File_priv!=?\n"
												+ "    OR Grant_priv!=?\n"
												+ "    OR References_priv!=?\n"
												+ "    OR Index_priv!=?\n"
												+ "    OR Alter_priv!=?\n"
												+ "    OR Show_db_priv!=?\n"
												+ "    OR Super_priv!=?\n"
												+ "    OR Create_tmp_table_priv!=?\n"
												+ "    OR Lock_tables_priv!=?\n"
												+ "    OR Execute_priv!=?\n"
												+ "    OR Repl_slave_priv!=?\n"
												+ "    OR Repl_client_priv!=?\n"
												+ "    OR max_questions!=?\n"
												+ "    OR max_updates!=?\n"
												+ "    OR max_connections!=?\n"
												+ "  )";
									} else if(version.startsWith(Server.VERSION_4_1_PREFIX)) {
										updateSQL = "UPDATE user SET\n"
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
												+ "    Select_priv!=?\n"
												+ "    OR Insert_priv!=?\n"
												+ "    OR Update_priv!=?\n"
												+ "    OR Delete_priv!=?\n"
												+ "    OR Create_priv!=?\n"
												+ "    OR Drop_priv!=?\n"
												+ "    OR Reload_priv!=?\n"
												+ "    OR Shutdown_priv!=?\n"
												+ "    OR Process_priv!=?\n"
												+ "    OR File_priv!=?\n"
												+ "    OR Grant_priv!=?\n"
												+ "    OR References_priv!=?\n"
												+ "    OR Index_priv!=?\n"
												+ "    OR Alter_priv!=?\n"
												+ "    OR Show_db_priv!=?\n"
												+ "    OR Super_priv!=?\n"
												+ "    OR Create_tmp_table_priv!=?\n"
												+ "    OR Lock_tables_priv!=?\n"
												+ "    OR Execute_priv!=?\n"
												+ "    OR Repl_slave_priv!=?\n"
												+ "    OR Repl_client_priv!=?\n"
												+ "    OR max_questions!=?\n"
												+ "    OR max_updates!=?\n"
												+ "    OR max_connections!=?\n"
												+ "  )";
									} else if(version.startsWith(Server.VERSION_5_0_PREFIX)) {
										updateSQL = "UPDATE user SET\n"
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
												+ "    Select_priv!=?\n"
												+ "    OR Insert_priv!=?\n"
												+ "    OR Update_priv!=?\n"
												+ "    OR Delete_priv!=?\n"
												+ "    OR Create_priv!=?\n"
												+ "    OR Drop_priv!=?\n"
												+ "    OR Reload_priv!=?\n"
												+ "    OR Shutdown_priv!=?\n"
												+ "    OR Process_priv!=?\n"
												+ "    OR File_priv!=?\n"
												+ "    OR Grant_priv!=?\n"
												+ "    OR References_priv!=?\n"
												+ "    OR Index_priv!=?\n"
												+ "    OR Alter_priv!=?\n"
												+ "    OR Show_db_priv!=?\n"
												+ "    OR Super_priv!=?\n"
												+ "    OR Create_tmp_table_priv!=?\n"
												+ "    OR Lock_tables_priv!=?\n"
												+ "    OR Execute_priv!=?\n"
												+ "    OR Repl_slave_priv!=?\n"
												+ "    OR Repl_client_priv!=?\n"
												+ "    OR Create_view_priv!=?\n"
												+ "    OR Show_view_priv!=?\n"
												+ "    OR Create_routine_priv!=?\n"
												+ "    OR Alter_routine_priv!=?\n"
												+ "    OR Create_user_priv!=?\n"
												+ "    OR max_questions!=?\n"
												+ "    OR max_updates!=?\n"
												+ "    OR max_connections!=?\n"
												+ "    OR max_user_connections!=?\n"
												+ "  )";
									} else if(
										version.startsWith(Server.VERSION_5_1_PREFIX)
										|| version.startsWith(Server.VERSION_5_6_PREFIX)
									) {
										updateSQL = "UPDATE user SET\n"
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
												+ "    Select_priv!=?\n"
												+ "    OR Insert_priv!=?\n"
												+ "    OR Update_priv!=?\n"
												+ "    OR Delete_priv!=?\n"
												+ "    OR Create_priv!=?\n"
												+ "    OR Drop_priv!=?\n"
												+ "    OR Reload_priv!=?\n"
												+ "    OR Shutdown_priv!=?\n"
												+ "    OR Process_priv!=?\n"
												+ "    OR File_priv!=?\n"
												+ "    OR Grant_priv!=?\n"
												+ "    OR References_priv!=?\n"
												+ "    OR Index_priv!=?\n"
												+ "    OR Alter_priv!=?\n"
												+ "    OR Show_db_priv!=?\n"
												+ "    OR Super_priv!=?\n"
												+ "    OR Create_tmp_table_priv!=?\n"
												+ "    OR Lock_tables_priv!=?\n"
												+ "    OR Execute_priv!=?\n"
												+ "    OR Repl_slave_priv!=?\n"
												+ "    OR Repl_client_priv!=?\n"
												+ "    OR Create_view_priv!=?\n"
												+ "    OR Show_view_priv!=?\n"
												+ "    OR Create_routine_priv!=?\n"
												+ "    OR Alter_routine_priv!=?\n"
												+ "    OR Create_user_priv!=?\n"
												+ "    OR Event_priv!=?\n"
												+ "    OR Trigger_priv!=?\n"
												+ "    OR max_questions!=?\n"
												+ "    OR max_updates!=?\n"
												+ "    OR max_connections!=?\n"
												+ "    OR max_user_connections!=?\n"
												+ "  )";
									} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
										updateSQL = "UPDATE user SET\n"
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
												+ "    Select_priv!=?\n"
												+ "    OR Insert_priv!=?\n"
												+ "    OR Update_priv!=?\n"
												+ "    OR Delete_priv!=?\n"
												+ "    OR Create_priv!=?\n"
												+ "    OR Drop_priv!=?\n"
												+ "    OR Reload_priv!=?\n"
												+ "    OR Shutdown_priv!=?\n"
												+ "    OR Process_priv!=?\n"
												+ "    OR File_priv!=?\n"
												+ "    OR Grant_priv!=?\n"
												+ "    OR References_priv!=?\n"
												+ "    OR Index_priv!=?\n"
												+ "    OR Alter_priv!=?\n"
												+ "    OR Show_db_priv!=?\n"
												+ "    OR Super_priv!=?\n"
												+ "    OR Create_tmp_table_priv!=?\n"
												+ "    OR Lock_tables_priv!=?\n"
												+ "    OR Execute_priv!=?\n"
												+ "    OR Repl_slave_priv!=?\n"
												+ "    OR Repl_client_priv!=?\n"
												+ "    OR Create_view_priv!=?\n"
												+ "    OR Show_view_priv!=?\n"
												+ "    OR Create_routine_priv!=?\n"
												+ "    OR Alter_routine_priv!=?\n"
												+ "    OR Create_user_priv!=?\n"
												+ "    OR Event_priv!=?\n"
												+ "    OR Trigger_priv!=?\n"
												+ "    OR max_questions!=?\n"
												+ "    OR max_updates!=?\n"
												+ "    OR max_connections!=?\n"
												+ "    OR max_user_connections!=?\n"
												+ "    OR account_locked!=?\n"
												+ "  )";
									} else {
										throw new SQLException("Unsupported MySQL version: " + version);
									}

									currentSQL = null;
									try (PreparedStatement pstmt = conn.prepareStatement(currentSQL = updateSQL)) {
										for(UserServer msu : users) {
											User mu = msu.getMySQLUser();
											String host = msu.getHost();
											if(host == null) host = "";
											User.Name username = mu.getKey();
											Tuple2<String, User.Name> key = new Tuple2<>(host, username);
											if(existing.contains(key)) {
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
												pstmt.setString(pos++, mu.canShowDB() ? "Y" : "N");
												pstmt.setString(pos++, mu.isSuper() ? "Y" : "N");
												pstmt.setString(pos++, mu.canCreateTempTable() ? "Y" : "N");
												pstmt.setString(pos++, mu.canLockTables() ? "Y" : "N");
												pstmt.setString(pos++, mu.canExecute() ? "Y" : "N");
												pstmt.setString(pos++, mu.isReplicationSlave() ? "Y" : "N");
												pstmt.setString(pos++, mu.isReplicationClient() ? "Y" : "N");
												if(
													version.startsWith(Server.VERSION_5_0_PREFIX)
													|| version.startsWith(Server.VERSION_5_1_PREFIX)
													|| version.startsWith(Server.VERSION_5_6_PREFIX)
													|| version.startsWith(Server.VERSION_5_7_PREFIX)
												) {
													pstmt.setString(pos++, mu.canCreateView() ? "Y" : "N");
													pstmt.setString(pos++, mu.canShowView() ? "Y" : "N");
													pstmt.setString(pos++, mu.canCreateRoutine() ? "Y" : "N");
													pstmt.setString(pos++, mu.canAlterRoutine() ? "Y" : "N");
													pstmt.setString(pos++, mu.canCreateUser() ? "Y" : "N");
													if(
														version.startsWith(Server.VERSION_5_1_PREFIX)
														|| version.startsWith(Server.VERSION_5_6_PREFIX)
														|| version.startsWith(Server.VERSION_5_7_PREFIX)
													) {
														pstmt.setString(pos++, mu.canEvent() ? "Y" : "N");
														pstmt.setString(pos++, mu.canTrigger() ? "Y" : "N");
													}
												}
												pstmt.setInt(pos++, msu.getMaxQuestions());
												pstmt.setInt(pos++, msu.getMaxUpdates());
												pstmt.setInt(pos++, msu.getMaxConnections());
												if(
													version.startsWith(Server.VERSION_5_0_PREFIX)
													|| version.startsWith(Server.VERSION_5_1_PREFIX)
													|| version.startsWith(Server.VERSION_5_6_PREFIX)
													|| version.startsWith(Server.VERSION_5_7_PREFIX)
												) pstmt.setInt(pos++, msu.getMaxUserConnections());
												boolean locked =
													username.equals(User.MYSQL_SESSION)
													|| username.equals(User.MYSQL_SYS)
													|| msu.isDisabled()
												;
												if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
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
												pstmt.setString(pos++, mu.canShowDB() ? "Y" : "N");
												pstmt.setString(pos++, mu.isSuper() ? "Y" : "N");
												pstmt.setString(pos++, mu.canCreateTempTable() ? "Y" : "N");
												pstmt.setString(pos++, mu.canLockTables() ? "Y" : "N");
												pstmt.setString(pos++, mu.canExecute() ? "Y" : "N");
												pstmt.setString(pos++, mu.isReplicationSlave() ? "Y" : "N");
												pstmt.setString(pos++, mu.isReplicationClient() ? "Y" : "N");
												if(
													version.startsWith(Server.VERSION_5_0_PREFIX)
													|| version.startsWith(Server.VERSION_5_1_PREFIX)
													|| version.startsWith(Server.VERSION_5_6_PREFIX)
													|| version.startsWith(Server.VERSION_5_7_PREFIX)
												) {
													pstmt.setString(pos++, mu.canCreateView() ? "Y" : "N");
													pstmt.setString(pos++, mu.canShowView() ? "Y" : "N");
													pstmt.setString(pos++, mu.canCreateRoutine() ? "Y" : "N");
													pstmt.setString(pos++, mu.canAlterRoutine() ? "Y" : "N");
													pstmt.setString(pos++, mu.canCreateUser() ? "Y" : "N");
													if(
														version.startsWith(Server.VERSION_5_1_PREFIX)
														|| version.startsWith(Server.VERSION_5_6_PREFIX)
														|| version.startsWith(Server.VERSION_5_7_PREFIX)
													) {
														pstmt.setString(pos++, mu.canEvent() ? "Y" : "N");
														pstmt.setString(pos++, mu.canTrigger() ? "Y" : "N");
													}
												}
												pstmt.setInt(pos++, msu.getMaxQuestions());
												pstmt.setInt(pos++, msu.getMaxUpdates());
												pstmt.setInt(pos++, msu.getMaxConnections());
												if(
													version.startsWith(Server.VERSION_5_0_PREFIX)
													|| version.startsWith(Server.VERSION_5_1_PREFIX)
													|| version.startsWith(Server.VERSION_5_6_PREFIX)
													|| version.startsWith(Server.VERSION_5_7_PREFIX)
												) pstmt.setInt(pos++, msu.getMaxUserConnections());
												if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
													pstmt.setString(pos++, locked ? "Y" : "N");
												}
												int updateCount = pstmt.executeUpdate();
												if(updateCount > 0) modified = true;
											}
										}
									} catch(Error | RuntimeException | SQLException e) {
										ErrorPrinter.addSQL(e, currentSQL);
										throw e;
									}

									// Add the users that do not exist and should
									String insertSQL;
									if(version.startsWith(Server.VERSION_4_0_PREFIX)) insertSQL = "INSERT INTO user VALUES (?,?,'"+User.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
									else if(version.startsWith(Server.VERSION_4_1_PREFIX)) insertSQL = "INSERT INTO user VALUES (?,?,'"+User.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
									else if(version.startsWith(Server.VERSION_5_0_PREFIX)) insertSQL = "INSERT INTO user VALUES (?,?,'"+User.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
									else if(version.startsWith(Server.VERSION_5_1_PREFIX)) insertSQL = "INSERT INTO user VALUES (?,?,'"+User.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
									else if(version.startsWith(Server.VERSION_5_6_PREFIX)) insertSQL = "INSERT INTO user VALUES (?,?,'"+User.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'N','','','','',?,?,?,?,'',NULL,'N')";
									else if(version.startsWith(Server.VERSION_5_7_PREFIX)) insertSQL = "INSERT INTO user VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'N','','','','',?,?,?,?,'mysql_native_password','" + User.NO_PASSWORD_DB_VALUE + "','N',NOW(),NULL,?)";
									else throw new SQLException("Unsupported MySQL version: " + version);

									currentSQL = null;
									try (PreparedStatement pstmt = conn.prepareStatement(currentSQL = insertSQL)) {
										for(UserServer msu : users) {
											User mu = msu.getMySQLUser();
											String host = msu.getHost();
											if(host == null) host = "";
											User.Name username = mu.getKey();
											Tuple2<String, User.Name> key = new Tuple2<>(host, username);
											if(!existing.remove(key)) {
												// Add the user
												if(mu.isSpecial()) {
													logger.log(
														Level.WARNING,
														null,
														new SQLException("Refusing to create special user: " + username + " on " + mysqlServer.getName())
													);
												} else {
													int pos=1;
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
													pstmt.setString(pos++, mu.canShowDB() ? "Y" : "N");
													pstmt.setString(pos++, mu.isSuper() ? "Y" : "N");
													pstmt.setString(pos++, mu.canCreateTempTable() ? "Y" : "N");
													pstmt.setString(pos++, mu.canLockTables() ? "Y" : "N");
													pstmt.setString(pos++, mu.canExecute() ? "Y" : "N");
													pstmt.setString(pos++, mu.isReplicationSlave() ? "Y" : "N");
													pstmt.setString(pos++, mu.isReplicationClient() ? "Y" : "N");
													if(
														version.startsWith(Server.VERSION_5_0_PREFIX)
														|| version.startsWith(Server.VERSION_5_1_PREFIX)
														|| version.startsWith(Server.VERSION_5_6_PREFIX)
														|| version.startsWith(Server.VERSION_5_7_PREFIX)
													) {
														pstmt.setString(pos++, mu.canCreateView() ? "Y" : "N");
														pstmt.setString(pos++, mu.canShowView() ? "Y" : "N");
														pstmt.setString(pos++, mu.canCreateRoutine() ? "Y" : "N");
														pstmt.setString(pos++, mu.canAlterRoutine() ? "Y" : "N");
														pstmt.setString(pos++, mu.canCreateUser() ? "Y" : "N");
														if(
															version.startsWith(Server.VERSION_5_1_PREFIX)
															|| version.startsWith(Server.VERSION_5_6_PREFIX)
															|| version.startsWith(Server.VERSION_5_7_PREFIX)
														) {
															pstmt.setString(pos++, mu.canEvent() ? "Y" : "N");
															pstmt.setString(pos++, mu.canTrigger() ? "Y" : "N");
														}
													}
													pstmt.setInt(pos++, msu.getMaxQuestions());
													pstmt.setInt(pos++, msu.getMaxUpdates());
													pstmt.setInt(pos++, msu.getMaxConnections());
													if(
														version.startsWith(Server.VERSION_5_0_PREFIX)
														|| version.startsWith(Server.VERSION_5_1_PREFIX)
														|| version.startsWith(Server.VERSION_5_6_PREFIX)
														|| version.startsWith(Server.VERSION_5_7_PREFIX)
													) pstmt.setInt(pos++, msu.getMaxUserConnections());
													if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
														boolean locked = msu.isDisabled();
														pstmt.setString(pos++, locked ? "Y" : "N");
													}
													pstmt.executeUpdate();

													modified = true;
												}
											}
										}
									} catch(Error | RuntimeException | SQLException e) {
										ErrorPrinter.addSQL(e, currentSQL);
										throw e;
									}

									// Remove the extra users
									if(!existing.isEmpty()) {
										currentSQL = null;
										try (PreparedStatement pstmt = conn.prepareStatement(currentSQL = "DELETE FROM user WHERE host=? AND user=?")) {
											for (Tuple2<String, User.Name> key : existing) {
												// Remove the extra host entry
												String host = key.getElement1();
												User.Name user = key.getElement2();
												if(User.isSpecial(user)) {
													logger.log(
														Level.WARNING,
														null,
														new SQLException("Refusing to drop special user: " + user + " user for host " + host + " on " + mysqlServer.getName())
													);
												} else {
													pstmt.setString(1, host);
													pstmt.setString(2, user.toString());
													pstmt.executeUpdate();
													modified = true;
												}
											}
										} catch(Error | RuntimeException | SQLException e) {
											ErrorPrinter.addSQL(e, currentSQL);
											throw e;
										}
									}
								} catch(SQLException e) {
									conn.abort(AOServDaemon.executorService);
									throw e;
								}
							}

							// Disable and enable accounts
							if(
								version.startsWith(Server.VERSION_4_0_PREFIX)
								|| version.startsWith(Server.VERSION_4_1_PREFIX)
								|| version.startsWith(Server.VERSION_5_0_PREFIX)
								|| version.startsWith(Server.VERSION_5_1_PREFIX)
								|| version.startsWith(Server.VERSION_5_6_PREFIX)
							) {
								// Older versions of MySQL are disabled by stashing the encrypted password and replacing it with an invalid hash
								for(UserServer msu : users) {
									if(!msu.isSpecial()) {
										String prePassword = msu.getPredisablePassword();
										if(!msu.isDisabled()) {
											if(prePassword != null) {
												setEncryptedPassword(mysqlServer, msu.getMySQLUser().getKey(), prePassword);
												modified = true;
												msu.setPredisablePassword(null);
											}
										} else {
											if(prePassword == null) {
												User.Name username = msu.getMySQLUser().getKey();
												msu.setPredisablePassword(getEncryptedPassword(mysqlServer, username));
												setEncryptedPassword(mysqlServer, username, User.NO_PASSWORD);
												modified = true;
											}
										}
									}
								}
							} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
								// MySQL 5.7+ support "account_locked" column, set above.
								// Nothing to do here.
							} else {
								throw new SQLException("Unsupported version of MySQL: " + version);
							}
							if(modified) MySQLServerManager.flushPrivileges(mysqlServer);
						}
					}
				}
			}
			return true;
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	public static String getEncryptedPassword(Server mysqlServer, User.Name username) throws IOException, SQLException {
		if(User.isSpecial(username)) {
			throw new SQLException("Refusing to get the encrypted password for a special user: " + username + " on " + mysqlServer.getName());
		}
		final String version = mysqlServer.getVersion().getVersion();
		String sql;
		if(
			version.startsWith(Server.VERSION_4_0_PREFIX)
			|| version.startsWith(Server.VERSION_4_1_PREFIX)
			|| version.startsWith(Server.VERSION_5_0_PREFIX)
			|| version.startsWith(Server.VERSION_5_1_PREFIX)
			|| version.startsWith(Server.VERSION_5_6_PREFIX)
		) {
			// Older MySQL: password stored in "password" column
			sql = "SELECT password FROM user WHERE user=?";
		} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
			// MySQL 5.7+: password stored in "authentication_string" column
			sql = "SELECT authentication_string FROM user WHERE user=?";
		} else {
			throw new SQLException("Unsupported version of MySQL: " + version);
		}
		try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection(true)) {
			try {
				try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
					try {
						pstmt.setString(1, username.toString());
						try (ResultSet result = pstmt.executeQuery()) {
							if(result.next()) {
								return result.getString(1);
							} else {
								throw new SQLException("No rows returned.");
							}
						}
					} catch(Error | RuntimeException | SQLException e) {
						ErrorPrinter.addSQL(e, sql);
						throw e;
					}
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
	}

	/**
	 * Sets the password, updating password_last_changed.
	 */
	public static void setPassword(Server mysqlServer, User.Name username, String password) throws IOException, SQLException {
		if(User.isSpecial(username)) {
			throw new SQLException("Refusing to set the password for a special user: " + username + " on " + mysqlServer.getName());
		}
		final String version = mysqlServer.getVersion().getVersion();
		// Get the connection to work through
		try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
			try {
				if(Objects.equals(password, User.NO_PASSWORD)) {
					// Disable the account
					String sql;
					if(
						version.startsWith(Server.VERSION_4_0_PREFIX)
						|| version.startsWith(Server.VERSION_4_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_0_PREFIX)
						|| version.startsWith(Server.VERSION_5_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_6_PREFIX)
					) {
						sql = "UPDATE user SET password='" + User.NO_PASSWORD_DB_VALUE + "' WHERE user=?";
					} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
						sql = "UPDATE user SET authentication_string='" + User.NO_PASSWORD_DB_VALUE + "', password_last_changed=NOW() WHERE user=?";
					} else {
						throw new SQLException("Unsupported version of MySQL: " + version);
					}
					try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
						pstmt.setString(1, username.toString());
						pstmt.executeUpdate();
					} catch(Error | RuntimeException | SQLException e) {
						ErrorPrinter.addSQL(e, sql);
						throw e;
					}
				} else {
					// Reset the password
					String sql;
					if(
						version.startsWith(Server.VERSION_4_0_PREFIX)
						|| version.startsWith(Server.VERSION_4_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_0_PREFIX)
						|| version.startsWith(Server.VERSION_5_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_6_PREFIX)
					) {
						sql = "UPDATE user SET password=PASSWORD(?) WHERE user=?";
					} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
						sql = "UPDATE user SET authentication_string=PASSWORD(?), password_last_changed=NOW() WHERE user=?";
					} else {
						throw new SQLException("Unsupported version of MySQL: " + version);
					}
					try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
						pstmt.setString(1, password);
						pstmt.setString(2, username.toString());
						pstmt.executeUpdate();
					} catch(Error | RuntimeException | SQLException e) {
						ErrorPrinter.addSQL(e, sql);
						throw e;
					}
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
		MySQLServerManager.flushPrivileges(mysqlServer);
	}

	/**
	 * Sets the encrypted password, password_last_changed unchanged.
	 */
	private static void setEncryptedPassword(Server mysqlServer, User.Name username, String password) throws IOException, SQLException {
		if(User.isSpecial(username)) {
			throw new SQLException("Refusing to set the encrypted password for a special user: " + username + " on " + mysqlServer.getName());
		}
		final String version = mysqlServer.getVersion().getVersion();
		// Get the connection to work through
		try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
			try {
				if(Objects.equals(password, User.NO_PASSWORD)) {
					// Disable the account
					String sql;
					if(
						version.startsWith(Server.VERSION_4_0_PREFIX)
						|| version.startsWith(Server.VERSION_4_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_0_PREFIX)
						|| version.startsWith(Server.VERSION_5_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_6_PREFIX)
					) {
						sql = "UPDATE user SET password='" + User.NO_PASSWORD_DB_VALUE + "' WHERE user=?";
					} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
						sql = "UPDATE user SET authentication_string='" + User.NO_PASSWORD_DB_VALUE + "' WHERE user=?";
					} else {
						throw new SQLException("Unsupported version of MySQL: " + version);
					}
					try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
						pstmt.setString(1, username.toString());
						pstmt.executeUpdate();
					} catch(Error | RuntimeException | SQLException e) {
						ErrorPrinter.addSQL(e, sql);
						throw e;
					}
				} else {
					// Reset the password
					String sql;
					if(
						version.startsWith(Server.VERSION_4_0_PREFIX)
						|| version.startsWith(Server.VERSION_4_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_0_PREFIX)
						|| version.startsWith(Server.VERSION_5_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_6_PREFIX)
					) {
						sql = "UPDATE user SET password=? WHERE user=?";
					} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
						sql = "UPDATE user SET authentication_string=? WHERE user=?";
					} else {
						throw new SQLException("Unsupported version of MySQL: " + version);
					}
					try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
						pstmt.setString(1, password);
						pstmt.setString(2, username.toString());
						pstmt.executeUpdate();
					} catch(Error | RuntimeException | SQLException e) {
						ErrorPrinter.addSQL(e, sql);
						throw e;
					}
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
		MySQLServerManager.flushPrivileges(mysqlServer);
	}

	private static MySQLUserManager mysqlUserManager;
	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void start() throws IOException, SQLException {
		com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLUserManager.class)
				&& mysqlUserManager == null
			) {
				System.out.print("Starting MySQLUserManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
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
		if(mysqlUserManager!=null) mysqlUserManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild MySQL Users";
	}
}
