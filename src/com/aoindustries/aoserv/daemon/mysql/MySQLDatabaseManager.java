/*
 * Copyright 2002-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.AOServer;
import com.aoindustries.aoserv.client.mysql.MySQLDatabase;
import com.aoindustries.aoserv.client.mysql.MySQLServer;
import com.aoindustries.aoserv.client.validator.MySQLDatabaseName;
import com.aoindustries.aoserv.client.validator.MySQLTableName;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.ObjectUtils;
import com.aoindustries.net.Port;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.PropertiesUtils;
import com.aoindustries.util.concurrent.ConcurrencyLimiter;
import com.aoindustries.validation.ValidationException;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
					List<MySQLDatabase> databases = mysqlServer.getMySQLDatabases();
					if(databases.isEmpty()) {
						LogFactory.getLogger(MySQLDatabaseManager.class).severe("No databases; refusing to rebuild config: " + mysqlServer);
					} else {
						String version = mysqlServer.getVersion().getVersion();
						// Different versions of MySQL have different sets of system databases
						Set<MySQLDatabaseName> systemDatabases = new LinkedHashSet<>();
						if(
							version.startsWith(MySQLServer.VERSION_4_0_PREFIX)
							|| version.startsWith(MySQLServer.VERSION_4_1_PREFIX)
						) {
							systemDatabases.add(MySQLDatabase.MYSQL);
						} else if(
							version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
							|| version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
						) {
							systemDatabases.add(MySQLDatabase.MYSQL);
							systemDatabases.add(MySQLDatabase.INFORMATION_SCHEMA);
						} else if(version.startsWith(MySQLServer.VERSION_5_6_PREFIX)) {
							systemDatabases.add(MySQLDatabase.MYSQL);
							systemDatabases.add(MySQLDatabase.INFORMATION_SCHEMA);
							systemDatabases.add(MySQLDatabase.PERFORMANCE_SCHEMA);
						} else if(version.startsWith(MySQLServer.VERSION_5_7_PREFIX)) {
							systemDatabases.add(MySQLDatabase.MYSQL);
							systemDatabases.add(MySQLDatabase.INFORMATION_SCHEMA);
							systemDatabases.add(MySQLDatabase.PERFORMANCE_SCHEMA);
							systemDatabases.add(MySQLDatabase.SYS);
						} else {
							throw new SQLException("Unsupported version of MySQL: " + version);
						}
						// Verify has all system databases
						Set<MySQLDatabaseName> requiredDatabases = new LinkedHashSet<>(systemDatabases);
						for(MySQLDatabase database : databases) {
							if(
								requiredDatabases.remove(database.getName())
								&& requiredDatabases.isEmpty()
							) {
								break;
							}
						}
						if(!requiredDatabases.isEmpty()) {
							LogFactory.getLogger(MySQLUserManager.class).severe("Required databases not found; refusing to rebuild config: " + mysqlServer + " -> " + requiredDatabases);
						} else {
							boolean modified = false;
							// Get the connection to work through
							AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
							Connection conn = pool.getConnection(false);
							try {
								// Get the list of all existing databases
								Set<MySQLDatabaseName> existing = new HashSet<>();
								try (Statement stmt = conn.createStatement()) {
									try (ResultSet results = stmt.executeQuery("show databases")) {
										while(results.next()) {
											try {
												MySQLDatabaseName name = MySQLDatabaseName.valueOf(results.getString(1));
												if(!existing.add(name)) throw new SQLException("Duplicate database name: " + name);
											} catch(ValidationException e) {
												throw new SQLException(e);
											}
										}
									}

									// Create the databases that do not exist and should
									for(MySQLDatabase database : databases) {
										MySQLDatabaseName name = database.getName();
										if(!existing.remove(name)) {
											// Create the database
											stmt.executeUpdate("create database " + name);
											modified=true;
										}
									}

									// Remove the extra databases
									for(MySQLDatabaseName dbName : existing) {
										if(systemDatabases.contains(dbName)) {
											LogFactory.getLogger(MySQLDatabaseManager.class).log(
												Level.WARNING,
												null,
												new SQLException("Refusing to drop system MySQL Database: " + dbName + " on " + mysqlServer)
											);
										} else {
											// Dump database before dropping
											dumpDatabase(
												mysqlServer,
												dbName,
												BackupManager.getNextBackupFile("-mysql-" + mysqlServer.getName()+"-"+dbName+".sql.gz"),
												true
											);
											// Now drop
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

	public static void dumpDatabase(
		MySQLDatabase md,
		AOServDaemonProtocol.Version protocolVersion,
		CompressedDataOutputStream masterOut,
		boolean gzip
	) throws IOException, SQLException {
		UnixFile tempFile=UnixFile.mktemp(
			gzip
				? "/tmp/dump_mysql_database.sql.gz."
				: "/tmp/dump_mysql_database.sql.",
			true
		);
		try {
			dumpDatabase(
				md.getMySQLServer(),
				md.getName(),
				tempFile.getFile(),
				gzip
			);
			long dumpSize = tempFile.getStat().getSize();
			if(protocolVersion.compareTo(AOServDaemonProtocol.Version.VERSION_1_80_0) >= 0) {
				masterOut.writeLong(dumpSize);
			}
			long bytesRead = 0;
			try (InputStream dumpin = new FileInputStream(tempFile.getFile())) {
				byte[] buff = BufferManager.getBytes();
				try {
					int ret;
					while((ret=dumpin.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
						bytesRead += ret;
						if(bytesRead > dumpSize) throw new IOException("Too many bytes read: " + bytesRead + " > " + dumpSize);
						masterOut.writeByte(AOServDaemonProtocol.NEXT);
						masterOut.writeShort(ret);
						masterOut.write(buff, 0, ret);
					}
				} finally {
					BufferManager.release(buff, false);
				}
			}
			if(bytesRead < dumpSize) throw new IOException("Too few bytes read: " + bytesRead + " < " + dumpSize);
		} finally {
			if(tempFile.getStat().exists()) tempFile.delete();
		}
	}

	private static void dumpDatabase(
		MySQLServer ms,
		MySQLDatabaseName dbName,
		File output,
		boolean gzip
	) throws IOException, SQLException {
		String commandPath;
		{
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
				commandPath = "/usr/aoserv/daemon/bin/dump_mysql_database";
			} else if(
				osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				commandPath = "/opt/aoserv-daemon/bin/dump_mysql_database";
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
		// Make sure perl is installed as required by dump_mysql_database
		PackageManager.installPackage(PackageManager.PackageName.PERL);
		if(gzip) PackageManager.installPackage(PackageManager.PackageName.GZIP);
		AOServDaemon.exec(
			commandPath,
			dbName.toString(),
			ms.getMinorVersion(),
			Integer.toString(ms.getBind().getPort().getPort()),
			output.getPath(),
			Boolean.toString(gzip)
		);
		if(output.length() == 0) throw new SQLException("Empty dump file: " + output);
	}

	private static MySQLDatabaseManager mysqlDatabaseManager;

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
	public static Connection getMySQLConnection(UnixPath failoverRoot, int nestedOperatingSystemVersion, Port port) throws IOException, SQLException {
		if(port.getProtocol() != com.aoindustries.net.Protocol.TCP) throw new IllegalArgumentException("Only TCP supported: " + port);
		// Load the properties from the failover image
		File file;
		if(nestedOperatingSystemVersion == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
			file = new File((failoverRoot==null ? "" : failoverRoot.toString()) + "/etc/aoserv/daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
		} else if(
			nestedOperatingSystemVersion == OperatingSystemVersion.REDHAT_ES_4_X86_64
			|| nestedOperatingSystemVersion == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			|| nestedOperatingSystemVersion == OperatingSystemVersion.CENTOS_7_X86_64
		) {
			file = new File((failoverRoot==null ? "" : failoverRoot.toString()) + "/etc/opt/aoserv-daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
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
		final String jdbcUrl = "jdbc:mysql://127.0.0.1:" + port.getPort() + "/mysql";
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

	public static void getSlaveStatus(UnixPath failoverRoot, int nestedOperatingSystemVersion, Port port, CompressedDataOutputStream out) throws IOException, SQLException {
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

	private static class TableStatusConcurrencyKey {

		private final UnixPath failoverRoot;
		private final Port port;
		private final MySQLDatabaseName databaseName;
		private final int hash;

		private TableStatusConcurrencyKey(
			UnixPath failoverRoot,
			Port port,
			MySQLDatabaseName databaseName
		) {
			this.failoverRoot = failoverRoot;
			this.port = port;
			this.databaseName = databaseName;
			int newHash = ObjectUtils.hashCode(failoverRoot);
			newHash = newHash * 31 + port.hashCode();
			newHash = newHash * 31 + databaseName.hashCode();
			this.hash = newHash;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof TableStatusConcurrencyKey)) return false;
			TableStatusConcurrencyKey other = (TableStatusConcurrencyKey)obj;
			return
				// hash check shortcut
				hash == other.hash
				// == fields
				&& port==other.port
				// .equals fields
				&& ObjectUtils.equals(failoverRoot, other.failoverRoot)
				&& databaseName.equals(other.databaseName)
			;
		}
	}

	private static final ConcurrencyLimiter<TableStatusConcurrencyKey,List<MySQLDatabase.TableStatus>> tableStatusLimiter = new ConcurrencyLimiter<>();

	public static void getTableStatus(UnixPath failoverRoot, int nestedOperatingSystemVersion, Port port, MySQLDatabaseName databaseName, CompressedDataOutputStream out) throws IOException, SQLException {
		List<MySQLDatabase.TableStatus> tableStatuses;
		try {
			tableStatuses = tableStatusLimiter.executeSerialized(
				new TableStatusConcurrencyKey(
					failoverRoot,
					port,
					databaseName
				),
				() -> {
					List<MySQLDatabase.TableStatus> statuses = new ArrayList<>();
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
									statuses.add(
										new MySQLDatabase.TableStatus(
											MySQLTableName.valueOf(results.getString("Name")),
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
								} catch(ValidationException e) {
									throw new SQLException(e);
								} catch(IllegalArgumentException err) {
									throw new IOException(err);
								}
							}
						}
					}
					return Collections.unmodifiableList(statuses);
				}
			);
		} catch(InterruptedException e) {
			throw new SQLException(e);
		} catch(ExecutionException e) {
			throw new SQLException(e);
		}
		out.write(AOServDaemonProtocol.NEXT);
		int size = tableStatuses.size();
		out.writeCompressedInt(size);
		for(int c = 0; c < size; c++) {
			MySQLDatabase.TableStatus tableStatus = tableStatuses.get(c);
			out.writeUTF(tableStatus.getName().toString());
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

		private final UnixPath failoverRoot;
		private final Port port;
		private final MySQLDatabaseName databaseName;
		private final MySQLTableName tableName;
		private final int hash;

		private CheckTableConcurrencyKey(
			UnixPath failoverRoot,
			Port port,
			MySQLDatabaseName databaseName,
			MySQLTableName tableName
		) {
			this.failoverRoot = failoverRoot;
			this.port = port;
			this.databaseName = databaseName;
			this.tableName = tableName;
			int newHash = ObjectUtils.hashCode(failoverRoot);
			newHash = newHash * 31 + port.hashCode();
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
				&& ObjectUtils.equals(failoverRoot, other.failoverRoot)
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
		final UnixPath failoverRoot,
		final int nestedOperatingSystemVersion,
		final Port port,
		final MySQLDatabaseName databaseName,
		final List<MySQLTableName> tableNames,
		CompressedDataOutputStream out
	) throws IOException, SQLException {
		Future<List<MySQLDatabase.CheckTableResult>> future = AOServDaemon.executorService.submit(() -> {
			List<MySQLDatabase.CheckTableResult> allTableResults = new ArrayList<>();
			for(final MySQLTableName tableName : tableNames) {
				if(!MySQLDatabase.isSafeName(tableName.toString())) {
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
									final String dbNamePrefix = databaseName.toString()+'.';
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
														MySQLTableName.valueOf(table),
														duration,
														msgType==null ? null : MySQLDatabase.CheckTableResult.MsgType.valueOf(msgType),
														results.getString("Msg_text")
													)
												);
											} catch(ValidationException e) {
												throw new SQLException(e);
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
				out.writeUTF(checkTableResult.getTable().toString());
				out.writeLong(checkTableResult.getDuration());
				out.writeNullEnum(checkTableResult.getMsgType());
				out.writeNullUTF(checkTableResult.getMsgText());
			}
		} catch(InterruptedException exc) {
			IOException ioErr = new InterruptedIOException();
			ioErr.initCause(exc);
			throw ioErr;
		} catch(ExecutionException|TimeoutException exc) {
			throw new SQLException(exc);
		} finally {
			future.cancel(false);
		}
	}
}
