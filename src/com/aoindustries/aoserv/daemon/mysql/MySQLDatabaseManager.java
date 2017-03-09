/*
 * Copyright 2002-2013, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.PropertiesUtils;
import com.aoindustries.util.concurrent.ConcurrencyLimiter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Controls the MySQL databases.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLDatabaseManager extends BuilderThread {

	private MySQLDatabaseManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			//AOServConnector connector=AOServDaemon.getConnector();
			AOServer thisAOServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				for(MySQLServer mysqlServer : thisAOServer.getMySQLServers()) {
					String version = mysqlServer.getVersion().getVersion();
					boolean modified = false;
					// Get the connection to work through
					AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
					Connection conn = pool.getConnection(false);
					try {
						// Get the list of all existing databases
						Set<String> existing = new HashSet<>();
						try (Statement stmt = conn.createStatement()) {
							try (ResultSet results = stmt.executeQuery("show databases")) {
								while(results.next()) existing.add(results.getString(1));
							}

							// Create the databases that do not exist and should
							for(MySQLDatabase database : mysqlServer.getMySQLDatabases()) {
								String name = database.getName();
								if(existing.contains(name)) existing.remove(name);
								else {
									// Create the database
									stmt.executeUpdate("create database " + name);
									modified=true;
								}
							}

							// Remove the extra databases
							for(String dbName : existing) {
								if(
									dbName.equals(MySQLDatabase.MYSQL)
									|| (
										version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
										&& dbName.equals(MySQLDatabase.INFORMATION_SCHEMA)
									) || (
										version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
										&& dbName.equals(MySQLDatabase.INFORMATION_SCHEMA)
									) || (
										version.startsWith(MySQLServer.VERSION_5_6_PREFIX)
										&& (
											dbName.equals(MySQLDatabase.INFORMATION_SCHEMA)
											|| dbName.equals(MySQLDatabase.PERFORMANCE_SCHEMA)
										)
									)
								) {
									LogFactory.getLogger(MySQLDatabaseManager.class).log(
										Level.WARNING,
										null,
										new SQLException("Refusing to drop critical MySQL Database: " + dbName + " on " + mysqlServer)
									);
								} else {
									// Remove the extra database
									stmt.executeUpdate("drop database " + dbName);
									modified=true;
								}
							}
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
			LogFactory.getLogger(MySQLDatabaseManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static MySQLDatabaseManager mysqlDatabaseManager;

	public static void dumpDatabase(MySQLDatabase md, CompressedDataOutputStream masterOut) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		UnixFile tempFile = UnixFile.mktemp("/tmp/dump_mysql_database.sql.", true);
		try {
			String dbName = md.getName();
			MySQLServer ms = md.getMySQLServer();
			String path;
			if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
				path = "/usr/aoserv/daemon/bin/dump_mysql_database";
			} else if(
				osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				path = "/opt/aoserv-daemon/bin/dump_mysql_database";
			} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			// Make sure perl is installed as required by dump_mysql_database
			PackageManager.installPackage(PackageManager.PackageName.PERL);
			AOServDaemon.exec(
				path,
				dbName,
				ms.getMinorVersion(),
				Integer.toString(ms.getNetBind().getPort().getPort()),
				tempFile.getPath()
			);
			try (InputStream dumpin = new FileInputStream(tempFile.getFile())) {
				byte[] buff = BufferManager.getBytes();
				try {
					int ret;
					while((ret = dumpin.read(buff, 0, BufferManager.BUFFER_SIZE)) != -1) {
						masterOut.writeByte(AOServDaemonProtocol.NEXT);
						masterOut.writeShort(ret);
						masterOut.write(buff, 0, ret);
					}
				} finally {
					BufferManager.release(buff, false);
				}
			}
		} finally {
			if(tempFile.getStat().exists()) tempFile.delete();
		}
	}

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
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLDatabaseManager.class)
				&& mysqlDatabaseManager == null
			) {
				System.out.print("Starting MySQLDatabaseManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					mysqlDatabaseManager = new MySQLDatabaseManager();
					conn.getMysqlDatabases().addTableListener(mysqlDatabaseManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void waitForRebuild() {
		if(mysqlDatabaseManager != null) mysqlDatabaseManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild MySQL Databases";
	}

	public static void getMasterStatus(int mysqlServer, CompressedDataOutputStream out) throws IOException, SQLException {
		// Use the existing pools
		MySQLServer ms = AOServDaemon.getConnector().getMysqlServers().get(mysqlServer);
		if(ms == null) throw new SQLException("Unable to find MySQLServer: " + mysqlServer);

		AOConnectionPool pool = MySQLServerManager.getPool(ms);
		Connection conn = pool.getConnection(true);
		try {
			try (
				Statement stmt = conn.createStatement();
				ResultSet results = stmt.executeQuery("SHOW MASTER STATUS")
			) {
				if(results.next()) {
					out.write(AOServDaemonProtocol.NEXT);
					out.writeNullUTF(results.getString("File"));
					out.writeNullUTF(results.getString("Position"));
				} else {
					out.write(AOServDaemonProtocol.DONE);
				}
			}
		} finally {
			pool.releaseConnection(conn);
		}
	}

	/**
	 * Gets a connection to the MySQL server, this handles both master and slave scenarios.
	 */
	public static Connection getMySQLConnection(String failoverRoot, int nestedOperatingSystemVersion, int port) throws IOException, SQLException {
		// Load the properties from the failover image
		File file;
		if(nestedOperatingSystemVersion == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
			file = new File(failoverRoot + "/etc/aoserv/daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
		} else if(
			nestedOperatingSystemVersion == OperatingSystemVersion.REDHAT_ES_4_X86_64
			|| nestedOperatingSystemVersion == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			|| nestedOperatingSystemVersion == OperatingSystemVersion.CENTOS_7_X86_64
		) {
			file = new File(failoverRoot + "/etc/opt/aoserv-daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
		} else {
			throw new AssertionError("Unsupported nested OperatingSystemVersion: #" + nestedOperatingSystemVersion);
		}
		if(!file.exists()) throw new SQLException("Properties file doesn't exist: " + file.getPath());
		Properties nestedProps = PropertiesUtils.loadFromFile(file);
		String user = nestedProps.getProperty("aoserv.daemon.mysql.user");
		String password = nestedProps.getProperty("aoserv.daemon.mysql.password");

		// For simplicity, doesn't use connection pools
		try {
			Class.forName(AOServDaemonConfiguration.getMySqlDriver()).newInstance();
		} catch(ClassNotFoundException|InstantiationException|IllegalAccessException err) {
			throw new SQLException(err);
		}
		final String jdbcUrl = "jdbc:mysql://127.0.0.1:" + port + "/mysql";
		try {
			return DriverManager.getConnection(
				jdbcUrl,
				user,
				password
			);
		} catch(SQLException err) {
			//LogFactory.getLogger(MySQLDatabaseManager.class).log(Level.SEVERE, null, err);
			throw new SQLException("Unable to connect to MySQL database: jdbcUrl=" + jdbcUrl, err);
		}
	}

	public static void getSlaveStatus(String failoverRoot, int nestedOperatingSystemVersion, int port, CompressedDataOutputStream out) throws IOException, SQLException {
		try (
			Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, port);
			Statement stmt = conn.createStatement();
			ResultSet results = stmt.executeQuery("SHOW SLAVE STATUS")
		) {
			if(results.next()) {
				out.write(AOServDaemonProtocol.NEXT);
				out.writeNullUTF(results.getString("Slave_IO_State"));
				out.writeNullUTF(results.getString("Master_Log_File"));
				out.writeNullUTF(results.getString("Read_Master_Log_Pos"));
				out.writeNullUTF(results.getString("Relay_Log_File"));
				out.writeNullUTF(results.getString("Relay_Log_Pos"));
				out.writeNullUTF(results.getString("Relay_Master_Log_File"));
				out.writeNullUTF(results.getString("Slave_IO_Running"));
				out.writeNullUTF(results.getString("Slave_SQL_Running"));
				out.writeNullUTF(results.getString("Last_Errno"));
				out.writeNullUTF(results.getString("Last_Error"));
				out.writeNullUTF(results.getString("Skip_Counter"));
				out.writeNullUTF(results.getString("Exec_Master_Log_Pos"));
				out.writeNullUTF(results.getString("Relay_Log_Space"));
				out.writeNullUTF(results.getString("Seconds_Behind_Master"));
			} else {
				out.write(AOServDaemonProtocol.DONE);
			}
		}
	}

	public static void getTableStatus(String failoverRoot, int nestedOperatingSystemVersion, int port, String databaseName, CompressedDataOutputStream out) throws IOException, SQLException {
		List<MySQLDatabase.TableStatus> tableStatuses = new ArrayList<>();
		try (
			Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, port);
			Statement stmt = conn.createStatement()
		) {
			boolean isMySQL40;
			try (ResultSet results = stmt.executeQuery("SELECT version()")) {
				if(!results.next()) throw new SQLException("No row returned");
				isMySQL40 = results.getString(1).startsWith(MySQLServer.VERSION_4_0_PREFIX);
			}
			try (ResultSet results = stmt.executeQuery("SHOW TABLE STATUS FROM "+databaseName)) {
				while(results.next()) {
					String engine = results.getString(isMySQL40 ? "Type" : "Engine");
					Integer version;
					if(isMySQL40) {
						version = null;
					} else {
						version = results.getInt("Version");
						if(results.wasNull()) version = null;
					}
					String rowFormat = results.getString("Row_format");
					Long rows = results.getLong("Rows");
					if(results.wasNull()) rows = null;
					Long avgRowLength = results.getLong("Avg_row_length");
					if(results.wasNull()) avgRowLength = null;
					Long dataLength = results.getLong("Data_length");
					if(results.wasNull()) dataLength = null;
					Long maxDataLength = results.getLong("Max_data_length");
					if(results.wasNull()) maxDataLength = null;
					Long indexLength = results.getLong("Index_length");
					if(results.wasNull()) indexLength = null;
					Long dataFree = results.getLong("Data_free");
					if(results.wasNull()) dataFree = null;
					Long autoIncrement = results.getLong("Auto_increment");
					if(results.wasNull()) autoIncrement = null;
					String collation;
					if(isMySQL40) {
						collation = null;
					} else {
						collation = results.getString("Collation");
					}
					try {
						tableStatuses.add(
							new MySQLDatabase.TableStatus(
								results.getString("Name"),
								engine==null ? null : MySQLDatabase.Engine.valueOf(engine),
								version,
								rowFormat==null ? null : MySQLDatabase.TableStatus.RowFormat.valueOf(rowFormat),
								rows,
								avgRowLength,
								dataLength,
								maxDataLength,
								indexLength,
								dataFree,
								autoIncrement,
								results.getString("Create_time"),
								isMySQL40 ? null : results.getString("Update_time"),
								results.getString("Check_time"),
								collation==null ? null : MySQLDatabase.TableStatus.Collation.valueOf(collation),
								isMySQL40 ? null : results.getString("Checksum"),
								results.getString("Create_options"),
								results.getString("Comment")
							)
						);
					} catch(IllegalArgumentException err) {
						throw new IOException(err);
					}
				}
			}
		}
		out.write(AOServDaemonProtocol.NEXT);
		int size = tableStatuses.size();
		out.writeCompressedInt(size);
		for(int c = 0; c < size; c++) {
			MySQLDatabase.TableStatus tableStatus = tableStatuses.get(c);
			out.writeUTF(tableStatus.getName());
			out.writeNullEnum(tableStatus.getEngine());
			out.writeNullInteger(tableStatus.getVersion());
			out.writeNullEnum(tableStatus.getRowFormat());
			out.writeNullLong(tableStatus.getRows());
			out.writeNullLong(tableStatus.getAvgRowLength());
			out.writeNullLong(tableStatus.getDataLength());
			out.writeNullLong(tableStatus.getMaxDataLength());
			out.writeNullLong(tableStatus.getIndexLength());
			out.writeNullLong(tableStatus.getDataFree());
			out.writeNullLong(tableStatus.getAutoIncrement());
			out.writeNullUTF(tableStatus.getCreateTime());
			out.writeNullUTF(tableStatus.getUpdateTime());
			out.writeNullUTF(tableStatus.getCheckTime());
			out.writeNullEnum(tableStatus.getCollation());
			out.writeNullUTF(tableStatus.getChecksum());
			out.writeNullUTF(tableStatus.getCreateOptions());
			out.writeNullUTF(tableStatus.getComment());
		}
	}

	private static class CheckTableConcurrencyKey {

		private final String failoverRoot;
		private final int port;
		private final String databaseName;
		private final String tableName;
		private final int hash;

		private CheckTableConcurrencyKey(
			String failoverRoot,
			int port,
			String databaseName,
			String tableName
		) {
			this.failoverRoot = failoverRoot;
			this.port = port;
			this.databaseName = databaseName;
			this.tableName = tableName;
			int newHash = failoverRoot.hashCode();
			newHash = newHash * 31 + port;
			newHash = newHash * 31 + databaseName.hashCode();
			newHash = newHash * 31 + tableName.hashCode();
			this.hash = newHash;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof CheckTableConcurrencyKey)) return false;
			CheckTableConcurrencyKey other = (CheckTableConcurrencyKey)obj;
			return
				// hash check shortcut
				hash == other.hash
				// == fields
				&& port==other.port
				// .equals fields
				&& failoverRoot.equals(other.failoverRoot)
				&& databaseName.equals(other.databaseName)
				&& tableName.equals(other.tableName)
			;
		}
	}

	private static final ConcurrencyLimiter<CheckTableConcurrencyKey,List<MySQLDatabase.CheckTableResult>> checkTableLimiter = new ConcurrencyLimiter<>();

	/**
	 * Checks all tables, times-out in one minute.
	 */
	public static void checkTables(
		final String failoverRoot,
		final int nestedOperatingSystemVersion,
		final int port,
		final String databaseName,
		final List<String> tableNames,
		CompressedDataOutputStream out
	) throws IOException, SQLException {
		Future<List<MySQLDatabase.CheckTableResult>> future = AOServDaemon.executorService.submit(() -> {
			List<MySQLDatabase.CheckTableResult> allTableResults = new ArrayList<>();
			for(final String tableName : tableNames) {
				if(!MySQLDatabase.isSafeName(tableName)) {
					allTableResults.add(
						new MySQLDatabase.CheckTableResult(
							tableName,
							0,
							MySQLDatabase.CheckTableResult.MsgType.error,
							"Unsafe table name, refusing to check table"
						)
					);
				} else {
					try {
						allTableResults.addAll(
							checkTableLimiter.executeSerialized(new CheckTableConcurrencyKey(
									failoverRoot,
									port,
									databaseName,
									tableName
								),
								() -> {
									final String dbNamePrefix = databaseName+'.';
									final long startTime = System.currentTimeMillis();
									try (
										Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, port);
										Statement stmt = conn.createStatement();
										ResultSet results = stmt.executeQuery("CHECK TABLE `"+databaseName+"`.`"+tableName+"` FAST QUICK")
										) {
										long duration = System.currentTimeMillis() - startTime;
										if(duration<0) duration = 0; // System time possibly reset
										final List<MySQLDatabase.CheckTableResult> tableResults = new ArrayList<>();
										while(results.next()) {
											try {
												String table = results.getString("Table");
												if(table.startsWith(dbNamePrefix)) table = table.substring(dbNamePrefix.length());
												final String msgType = results.getString("Msg_type");
												tableResults.add(
													new MySQLDatabase.CheckTableResult(
														table,
														duration,
														msgType==null ? null : MySQLDatabase.CheckTableResult.MsgType.valueOf(msgType),
														results.getString("Msg_text")
													)
												);
											} catch(IllegalArgumentException err) {
												throw new IOException(err);
											}
										}
										return tableResults;
									}
								}
							)
						);
					} catch(InterruptedException e) {
						// Restore the interrupted status
						Thread.currentThread().interrupt();
						throw new SQLException(e);
					} catch(ExecutionException e) {
						throw new SQLException(e);
					}
				}
			}
			return allTableResults;
		});
		try {
			List<MySQLDatabase.CheckTableResult> allTableResults = future.get(60, TimeUnit.SECONDS);
			out.write(AOServDaemonProtocol.NEXT);
			int size = allTableResults.size();
			out.writeCompressedInt(size);
			for(int c=0;c<size;c++) {
				MySQLDatabase.CheckTableResult checkTableResult = allTableResults.get(c);
				out.writeUTF(checkTableResult.getTable());
				out.writeLong(checkTableResult.getDuration());
				out.writeNullEnum(checkTableResult.getMsgType());
				out.writeNullUTF(checkTableResult.getMsgText());
			}
		} catch(InterruptedException exc) {
			// Restore the interrupted status
			Thread.currentThread().interrupt();
			IOException newExc = new InterruptedIOException();
			newExc.initCause(exc);
			throw newExc;
		} catch(ExecutionException|TimeoutException exc) {
			throw new SQLException(exc);
		} finally {
			future.cancel(false);
		}
	}
}
