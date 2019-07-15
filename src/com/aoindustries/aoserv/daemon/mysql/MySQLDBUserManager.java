/*
 * Copyright 2002-2013, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.DatabaseUser;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.mysql.User;
import com.aoindustries.aoserv.client.mysql.UserServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.Tuple2;
import com.aoindustries.validation.ValidationException;
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

/**
 * Controls the MySQL DB Users.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLDBUserManager extends BuilderThread {

	private MySQLDBUserManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisAOServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			AOServConnector connector = AOServDaemon.getConnector();
			synchronized(rebuildLock) {
				for(Server mysqlServer : connector.getMysql().getServer()) {
					// Get the list of all db entries that should exist
					List<DatabaseUser> dbUsers = mysqlServer.getMySQLDBUsers();
					if(dbUsers.isEmpty()) {
						LogFactory.getLogger(MySQLDBUserManager.class).severe("No users; refusing to rebuild config: " + mysqlServer);
					} else {
						String version = mysqlServer.getVersion().getVersion();
						// Different versions of MySQL have different sets of system db users
						Set<Tuple2<Database.Name,User.Name>> systemDbUsers = new LinkedHashSet<>();
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
						Set<Tuple2<Database.Name,User.Name>> requiredDbUsers = new LinkedHashSet<>(systemDbUsers);
						for(DatabaseUser mdu : dbUsers) {
							if(
								requiredDbUsers.remove(new Tuple2<>(mdu.getMySQLDatabase().getName(), mdu.getMySQLServerUser().getMySQLUser().getKey()))
								&& requiredDbUsers.isEmpty()
							) {
								break;
							}
						}
						if(!requiredDbUsers.isEmpty()) {
							LogFactory.getLogger(MySQLUserManager.class).severe("Required db users not found; refusing to rebuild config: " + mysqlServer + " -> " + requiredDbUsers);
						} else {
							boolean modified = false;

							// Get the connection to work through
							AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
							Connection conn = pool.getConnection();
							try {
								// TODO: This does not update existing records; add update phase.
								// TODO: As written, updates to mysql_users table (which are very rare) will be represented here.

								// Get the list of all existing db entries
								Set<Tuple2<Database.Name,User.Name>> existing = new HashSet<>();
								try (
									Statement stmt = conn.createStatement();
									ResultSet results = stmt.executeQuery("SELECT db, user FROM db")
								) {
									while (results.next()) {
										try {
											Tuple2<Database.Name,User.Name> tuple = new Tuple2<>(
												Database.Name.valueOf(results.getString(1)),
												User.Name.valueOf(results.getString(2))
											);
											if(!existing.add(tuple)) throw new SQLException("Duplicate (db, user): " + tuple);
										} catch(ValidationException e) {
											throw new SQLException(e);
										}
									}
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
								try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
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
										Tuple2<Database.Name,User.Name> key = new Tuple2<>(db, user);
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
								}

								// Remove the extra db entries
								if (!existing.isEmpty()) {
									try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM db WHERE db=? AND user=?")) {
										for (Tuple2<Database.Name,User.Name> key : existing) {
											if(systemDbUsers.contains(key)) {
												LogFactory.getLogger(MySQLDatabaseManager.class).log(
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
									}
									modified = true;
								}
							} finally {
								pool.releaseConnection(conn);
							}
							if(modified) MySQLServerManager.flushPrivileges(mysqlServer);
						}
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(MySQLDBUserManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static MySQLDBUserManager mysqlDBUserManager;
	public static void start() throws IOException, SQLException {
		com.aoindustries.aoserv.client.linux.Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
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
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
