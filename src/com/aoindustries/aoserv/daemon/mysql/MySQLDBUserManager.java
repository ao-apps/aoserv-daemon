/*
 * Copyright 2002-2013, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MySQLDBUser;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.MySQLServerUser;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.MySQLDatabaseName;
import com.aoindustries.aoserv.client.validator.MySQLUserId;
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
			AOServer thisAOServer = AOServDaemon.getThisAOServer();
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
				for(MySQLServer mysqlServer : connector.getMysqlServers()) {
					String version=mysqlServer.getVersion().getVersion();
					boolean modified=false;

					// Get the connection to work through
					AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
					Connection conn = pool.getConnection();
					try {
						// Get the list of all existing db entries
						Set<Tuple2<MySQLDatabaseName,MySQLUserId>> existing = new HashSet<>();
						try (
							Statement stmt = conn.createStatement();
							ResultSet results = stmt.executeQuery("select db, user from db")
						) {
							while (results.next()) {
								try {
									Tuple2<MySQLDatabaseName,MySQLUserId> tuple = new Tuple2<>(
										MySQLDatabaseName.valueOf(results.getString(1)),
										MySQLUserId.valueOf(results.getString(2))
									);
									if(!existing.add(tuple)) throw new SQLException("Duplicate (db, user): " + tuple);
								} catch(ValidationException e) {
									throw new SQLException(e);
								}
							}
						}

						// Get the list of all db entries that should exist
						List<MySQLDBUser> dbUsers = mysqlServer.getMySQLDBUsers();

						// Add the db entries that do not exist and should
						String insertSQL;
						if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX)) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
						else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
						else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
						else if(
							version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
							|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
						) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
						else throw new SQLException("Unsupported MySQL version: "+version);
						try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
							for(MySQLDBUser mdu : dbUsers) {
								MySQLDatabase md = mdu.getMySQLDatabase();
								MySQLDatabaseName db=md.getName();
								MySQLServerUser msu=mdu.getMySQLServerUser();
								MySQLUserId user=msu.getMySQLUser().getKey();

								// These must both be on the same server !!!
								if(!md.getMySQLServer().equals(msu.getMySQLServer())) throw new SQLException(
									"Server mismatch in mysql_db_users.pkey="
									+mdu.getPkey()
									+": ((mysql_databases.pkey="
									+md.getPkey()
									+").mysql_server="
									+md.getMySQLServer().getPkey()
									+")!=((mysql_server_users.pkey="
									+msu.getPkey()
									+").mysql_server="
									+msu.getMySQLServer().getPkey()
									+')'
								);
								Tuple2<MySQLDatabaseName,MySQLUserId> key = new Tuple2<>(db, user);
								if(!existing.remove(key)) {
									// Add the db entry
									String host=MySQLServerUser.ANY_HOST;
									pstmt.setString(1, host);
									pstmt.setString(2, db.toString());
									pstmt.setString(3, user.toString());
									pstmt.setString(4, mdu.canSelect()?"Y":"N");
									pstmt.setString(5, mdu.canInsert()?"Y":"N");
									pstmt.setString(6, mdu.canUpdate()?"Y":"N");
									pstmt.setString(7, mdu.canDelete()?"Y":"N");
									pstmt.setString(8, mdu.canCreate()?"Y":"N");
									pstmt.setString(9, mdu.canDrop()?"Y":"N");
									pstmt.setString(10, mdu.canGrant()?"Y":"N");
									pstmt.setString(11, mdu.canReference()?"Y":"N");
									pstmt.setString(12, mdu.canIndex()?"Y":"N");
									pstmt.setString(13, mdu.canAlter()?"Y":"N");
									pstmt.setString(14, mdu.canCreateTempTable()?"Y":"N");
									pstmt.setString(15, mdu.canLockTables()?"Y":"N");
									if(
										version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
										|| version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
										|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
									) {
										pstmt.setString(16, mdu.canCreateView()?"Y":"N");
										pstmt.setString(17, mdu.canShowView()?"Y":"N");
										pstmt.setString(18, mdu.canCreateRoutine()?"Y":"N");
										pstmt.setString(19, mdu.canAlterRoutine()?"Y":"N");
										pstmt.setString(20, mdu.canExecute()?"Y":"N");
										if(
											version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
											|| version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
										) {
											pstmt.setString(21, mdu.canEvent()?"Y":"N");
											pstmt.setString(22, mdu.canTrigger()?"Y":"N");
										}
									}
									pstmt.executeUpdate();

									modified = true;
								}
							}
						}

						// Remove the extra db entries
						if (!existing.isEmpty()) {
							try (PreparedStatement pstmt = conn.prepareStatement("delete from db where db=? and user=?")) {
								for (Tuple2<MySQLDatabaseName,MySQLUserId> key : existing) {
									// Remove the extra db entry
									pstmt.setString(1, key.getElement1().toString());
									pstmt.setString(2, key.getElement2().toString());
									pstmt.executeUpdate();
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
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
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
					conn.getMysqlDBUsers().addTableListener(mysqlDBUserManager, 0);
					conn.getMysqlDatabases().addTableListener(mysqlDBUserManager, 0);
					conn.getMysqlServerUsers().addTableListener(mysqlDBUserManager, 0);
					conn.getMysqlUsers().addTableListener(mysqlDBUserManager, 0);
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
