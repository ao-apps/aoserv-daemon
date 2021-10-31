/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.DatabaseUser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL DB Users.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLDBUserManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(MySQLDBUserManager.class.getName());

	private MySQLDBUserManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			AOServConnector connector = AOServDaemon.getConnector();
			synchronized(rebuildLock) {
				for(Server mysqlServer : connector.getMysql().getServer()) {
					// Get the list of all db entries that should exist
					List<DatabaseUser> dbUsers = mysqlServer.getMySQLDBUsers();
					if(dbUsers.isEmpty()) {
						logger.severe("No users; refusing to rebuild config: " + mysqlServer);
					} else {
						String version = mysqlServer.getVersion().getVersion();
						// Different versions of MySQL have different sets of system db users
						Set<Tuple2<Database.Name, User.Name>> systemDbUsers = new LinkedHashSet<>();
						if(
							version.startsWith(Server.VERSION_4_0_PREFIX)
							|| version.startsWith(Server.VERSION_4_1_PREFIX)
							|| version.startsWith(Server.VERSION_5_0_PREFIX)
							|| version.startsWith(Server.VERSION_5_1_PREFIX)
							|| version.startsWith(Server.VERSION_5_6_PREFIX)
						) {
							// None
						} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
							systemDbUsers.add(new Tuple2<>(Database.PERFORMANCE_SCHEMA, User.MYSQL_SESSION));
							systemDbUsers.add(new Tuple2<>(Database.SYS, User.MYSQL_SYS));
						} else {
							throw new SQLException("Unsupported version of MySQL: " + version);
						}

						// Verify has all system db users
						Set<Tuple2<Database.Name, User.Name>> requiredDbUsers = new LinkedHashSet<>(systemDbUsers);
						for(DatabaseUser mdu : dbUsers) {
							if(
								requiredDbUsers.remove(new Tuple2<>(mdu.getMySQLDatabase().getName(), mdu.getMySQLServerUser().getMySQLUser().getKey()))
								&& requiredDbUsers.isEmpty()
							) {
								break;
							}
						}
						if(!requiredDbUsers.isEmpty()) {
							logger.severe("Required db users not found; refusing to rebuild config: " + mysqlServer + " -> " + requiredDbUsers);
						} else {
							boolean modified = false;

							// Get the connection to work through
							try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
								try {
									// TODO: This does not update existing records; add update phase.
									// TODO: As written, updates to mysql_users table (which are very rare) will be represented here.

									// Get the list of all existing db entries
									Set<Tuple2<Database.Name, User.Name>> existing = new HashSet<>();
									String currentSQL = null;
									try (
										Statement stmt = conn.createStatement();
										ResultSet results = stmt.executeQuery(currentSQL = "SELECT db, user FROM db")
									) {
										while (results.next()) {
											try {
												Tuple2<Database.Name, User.Name> tuple = new Tuple2<>(
													Database.Name.valueOf(results.getString(1)),
													User.Name.valueOf(results.getString(2))
												);
												if(!existing.add(tuple)) throw new SQLException("Duplicate (db, user): " + tuple);
											} catch(ValidationException e) {
												throw new SQLException(e);
											}
										}
									} catch(Error | RuntimeException | SQLException e) {
										ErrorPrinter.addSQL(e, currentSQL);
										throw e;
									}

									// Add the db entries that do not exist and should
									String insertSQL;
									if(version.startsWith(Server.VERSION_4_0_PREFIX)) {
										insertSQL = "INSERT INTO db VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
									} else if(version.startsWith(Server.VERSION_4_1_PREFIX)) {
										insertSQL = "INSERT INTO db VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
									} else if(version.startsWith(Server.VERSION_5_0_PREFIX)) {
										insertSQL = "INSERT INTO db VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
									} else if(
										version.startsWith(Server.VERSION_5_1_PREFIX)
										|| version.startsWith(Server.VERSION_5_6_PREFIX)
										|| version.startsWith(Server.VERSION_5_7_PREFIX)
									) {
										insertSQL="INSERT INTO db VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
									} else throw new SQLException("Unsupported MySQL version: "+version);
									currentSQL = null;
									try (PreparedStatement pstmt = conn.prepareStatement(currentSQL = insertSQL)) {
										for(DatabaseUser mdu : dbUsers) {
											Database md = mdu.getMySQLDatabase();
											Database.Name db = md.getName();
											UserServer msu = mdu.getMySQLServerUser();
											User.Name user = msu.getMySQLUser().getKey();

											// These must both be on the same server !!!
											if(!md.getMySQLServer().equals(msu.getMySQLServer())) throw new SQLException(
												"Host mismatch in mysql_db_users.pkey="
												+ mdu.getPkey()
												+ ": ((mysql_databases.pkey="
												+ md.getPkey()
												+ ").mysql_server="
												+ md.getMySQLServer().getPkey()
												+ ")!=((mysql_server_users.pkey="
												+ msu.getPkey()
												+ ").mysql_server="
												+ msu.getMySQLServer().getPkey()
												+ ')'
											);
											Tuple2<Database.Name, User.Name> key = new Tuple2<>(db, user);
											if(!existing.remove(key)) {
												// Add the db entry
												String host = 
													user.equals(User.MYSQL_SESSION)
													|| user.equals(User.MYSQL_SYS)
													? "localhost"
													: UserServer.ANY_HOST;
												pstmt.setString(1, host);
												pstmt.setString(2, db.toString());
												pstmt.setString(3, user.toString());
												pstmt.setString(4, mdu.canSelect() ? "Y" : "N");
												pstmt.setString(5, mdu.canInsert() ? "Y" : "N");
												pstmt.setString(6, mdu.canUpdate() ? "Y" : "N");
												pstmt.setString(7, mdu.canDelete() ? "Y" : "N");
												pstmt.setString(8, mdu.canCreate() ? "Y" : "N");
												pstmt.setString(9, mdu.canDrop() ? "Y" : "N");
												pstmt.setString(10, mdu.canGrant() ? "Y" : "N");
												pstmt.setString(11, mdu.canReference() ? "Y" : "N");
												pstmt.setString(12, mdu.canIndex() ? "Y" : "N");
												pstmt.setString(13, mdu.canAlter() ? "Y" : "N");
												pstmt.setString(14, mdu.canCreateTempTable() ? "Y" : "N");
												pstmt.setString(15, mdu.canLockTables() ? "Y" : "N");
												if(
													version.startsWith(Server.VERSION_5_0_PREFIX)
													|| version.startsWith(Server.VERSION_5_1_PREFIX)
													|| version.startsWith(Server.VERSION_5_6_PREFIX)
													|| version.startsWith(Server.VERSION_5_7_PREFIX)
												) {
													pstmt.setString(16, mdu.canCreateView() ? "Y" : "N");
													pstmt.setString(17, mdu.canShowView() ? "Y" : "N");
													pstmt.setString(18, mdu.canCreateRoutine() ? "Y" : "N");
													pstmt.setString(19, mdu.canAlterRoutine() ? "Y" : "N");
													pstmt.setString(20, mdu.canExecute() ? "Y" : "N");
													if(
														version.startsWith(Server.VERSION_5_1_PREFIX)
														|| version.startsWith(Server.VERSION_5_6_PREFIX)
														|| version.startsWith(Server.VERSION_5_7_PREFIX)
													) {
														pstmt.setString(21, mdu.canEvent() ? "Y" : "N");
														pstmt.setString(22, mdu.canTrigger() ? "Y" : "N");
													}
												}
												pstmt.executeUpdate();
												modified = true;
											}
										}
									} catch(Error | RuntimeException | SQLException e) {
										ErrorPrinter.addSQL(e, currentSQL);
										throw e;
									}

									// Remove the extra db entries
									if (!existing.isEmpty()) {
										currentSQL = null;
										try (PreparedStatement pstmt = conn.prepareStatement(currentSQL = "DELETE FROM db WHERE db=? AND user=?")) {
											for (Tuple2<Database.Name, User.Name> key : existing) {
												if(systemDbUsers.contains(key)) {
													logger.log(
														Level.WARNING,
														null,
														new SQLException("Refusing to delete system MySQL db user: " + key + " on " + mysqlServer)
													);
												} else {
													// Remove the extra db entry
													pstmt.setString(1, key.getElement1().toString());
													pstmt.setString(2, key.getElement2().toString());
													pstmt.executeUpdate();
												}
											}
										} catch(Error | RuntimeException | SQLException e) {
											ErrorPrinter.addSQL(e, currentSQL);
											throw e;
										}
										modified = true;
									}
								} catch(SQLException e) {
									conn.abort(AOServDaemon.executorService);
									throw e;
								}
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

	private static MySQLDBUserManager mysqlDBUserManager;
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
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLDBUserManager.class)
				&& mysqlDBUserManager == null
			) {
				System.out.print("Starting MySQLDBUserManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
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
		if(mysqlDBUserManager!=null) mysqlDBUserManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild MySQL DB Users";
	}
}
