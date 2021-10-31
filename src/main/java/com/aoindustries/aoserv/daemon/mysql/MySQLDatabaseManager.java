/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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

import com.aoapps.concurrent.KeyedConcurrencyReducer;
import com.aoapps.hodgepodge.io.stream.StreamableOutput;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.concurrent.ExecutionExceptions;
import com.aoapps.lang.util.BufferManager;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.util.PropertiesUtils;
import com.aoapps.lang.validation.ValidationException;
import com.aoapps.net.Port;
import com.aoapps.tempfiles.TempFile;
import com.aoapps.tempfiles.TempFileContext;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.mysql.Table_Name;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL databases.
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLDatabaseManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(MySQLDatabaseManager.class.getName());

	private static final File WORKING_DIRECTORY = new File("/var/lib/mysql");

	private MySQLDatabaseManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			//AOServConnector connector=AOServDaemon.getConnector();
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				for(Server mysqlServer : thisServer.getMySQLServers()) {
					List<Database> databases = mysqlServer.getMySQLDatabases();
					if(databases.isEmpty()) {
						logger.severe("No databases; refusing to rebuild config: " + mysqlServer);
					} else {
						String version = mysqlServer.getVersion().getVersion();
						// Different versions of MySQL have different sets of system databases
						Set<Database.Name> systemDatabases = new LinkedHashSet<>();
						if(
							version.startsWith(Server.VERSION_4_0_PREFIX)
							|| version.startsWith(Server.VERSION_4_1_PREFIX)
						) {
							systemDatabases.add(Database.MYSQL);
						} else if(
							version.startsWith(Server.VERSION_5_0_PREFIX)
							|| version.startsWith(Server.VERSION_5_1_PREFIX)
						) {
							systemDatabases.add(Database.MYSQL);
							systemDatabases.add(Database.INFORMATION_SCHEMA);
						} else if(version.startsWith(Server.VERSION_5_6_PREFIX)) {
							systemDatabases.add(Database.MYSQL);
							systemDatabases.add(Database.INFORMATION_SCHEMA);
							systemDatabases.add(Database.PERFORMANCE_SCHEMA);
						} else if(version.startsWith(Server.VERSION_5_7_PREFIX)) {
							systemDatabases.add(Database.MYSQL);
							systemDatabases.add(Database.INFORMATION_SCHEMA);
							systemDatabases.add(Database.PERFORMANCE_SCHEMA);
							systemDatabases.add(Database.SYS);
						} else {
							throw new SQLException("Unsupported version of MySQL: " + version);
						}
						// Verify has all system databases
						Set<Database.Name> requiredDatabases = new LinkedHashSet<>(systemDatabases);
						for(Database database : databases) {
							if(
								requiredDatabases.remove(database.getName())
								&& requiredDatabases.isEmpty()
							) {
								break;
							}
						}
						if(!requiredDatabases.isEmpty()) {
							logger.severe("Required databases not found; refusing to rebuild config: " + mysqlServer + " -> " + requiredDatabases);
						} else {
							boolean modified = false;
							// Get the connection to work through
							try (Connection conn = MySQLServerManager.getPool(mysqlServer).getConnection()) {
								try {
									// Get the list of all existing databases
									Set<Database.Name> existing = new HashSet<>();
									String currentSQL = null;
									try (Statement stmt = conn.createStatement()) {
										try (ResultSet results = stmt.executeQuery(currentSQL = "SHOW DATABASES")) {
											while(results.next()) {
												try {
													Database.Name name = Database.Name.valueOf(results.getString(1));
													if(!existing.add(name)) throw new SQLException("Duplicate database name: " + name);
												} catch(ValidationException e) {
													throw new SQLException(e);
												}
											}
										}

										// Create the databases that do not exist and should
										for(Database database : databases) {
											Database.Name name = database.getName();
											if(!existing.remove(name)) {
												if(database.isSpecial()) {
													logger.log(
														Level.WARNING,
														null,
														new SQLException("Refusing to create special database: " + name + " on " + mysqlServer.getName())
													);
												} else {
													// Create the database
													stmt.executeUpdate(currentSQL = "CREATE DATABASE `" + name + '`');
													modified = true;
												}
											}
										}

										// Remove the extra databases
										for(Database.Name dbName : existing) {
											if(systemDatabases.contains(dbName)) {
												logger.log(
													Level.WARNING,
													null,
													new SQLException("Refusing to drop system database: " + dbName + " on " + mysqlServer.getName())
												);
											} else if(Database.isSpecial(dbName)) {
												logger.log(
													Level.WARNING,
													null,
													new SQLException("Refusing to drop special database: " + dbName + " on " + mysqlServer.getName())
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
												stmt.executeUpdate(currentSQL = "DROP DATABASE `" + dbName + '`');
												modified = true;
											}
										}
									} catch(Error | RuntimeException | SQLException e) {
										ErrorPrinter.addSQL(e, currentSQL);
										throw e;
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

	public static void dumpDatabase(
		Database md,
		AOServDaemonProtocol.Version protocolVersion,
		StreamableOutput masterOut,
		boolean gzip
	) throws IOException, SQLException {
		try (
			TempFileContext tempFileContext = new TempFileContext();
			TempFile tempFile = tempFileContext.createTempFile("dump_mysql_database_", gzip ? ".sql.gz" : ".sql")
		) {
			dumpDatabase(
				md.getMySQLServer(),
				md.getName(),
				tempFile.getFile(),
				gzip
			);
			long dumpSize = new PosixFile(tempFile.getFile()).getStat().getSize();
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
		}
	}

	private static void dumpDatabase(
		Server ms,
		Database.Name dbName,
		File output,
		boolean gzip
	) throws IOException, SQLException {
		String commandPath;
		{
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
		String[] command = {
			commandPath,
			dbName.toString(),
			ms.getMinorVersion(),
			ms.getName().toString(),
			Integer.toString(ms.getBind().getPort().getPort()),
			output.getPath(),
			Boolean.toString(gzip)
		};
		AOServDaemon.exec(WORKING_DIRECTORY, command);
		if(output.length() == 0) {
			throw new SQLException("Empty dump file: " + output + "\nCommand: " + AOServDaemon.getCommandString(command));
		}
	}

	private static MySQLDatabaseManager mysqlDatabaseManager;

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
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLDatabaseManager.class)
				&& mysqlDatabaseManager == null
			) {
				System.out.print("Starting MySQLDatabaseManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					mysqlDatabaseManager = new MySQLDatabaseManager();
					conn.getMysql().getDatabase().addTableListener(mysqlDatabaseManager, 0);
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

	public static void getMasterStatus(int mysqlServer, StreamableOutput out) throws IOException, SQLException {
		// Use the existing pools
		Server ms = AOServDaemon.getConnector().getMysql().getServer().get(mysqlServer);
		if(ms == null) throw new SQLException("Unable to find Server: " + mysqlServer);

		try (Connection conn = MySQLServerManager.getPool(ms).getConnection(true)) {
			try {
				String currentSQL = null;
				try (
					Statement stmt = conn.createStatement();
					ResultSet results = stmt.executeQuery(currentSQL = "SHOW MASTER STATUS")
				) {
					if(results.next()) {
						out.write(AOServDaemonProtocol.NEXT);
						out.writeNullUTF(results.getString("File"));
						out.writeNullUTF(results.getString("Position"));
					} else {
						out.write(AOServDaemonProtocol.DONE);
					}
				} catch(Error | RuntimeException | SQLException e) {
					ErrorPrinter.addSQL(e, currentSQL);
					throw e;
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
	}

	public static String getJdbcUrl(Port port, Database.Name database) {
		try {
			if(port == Server.DEFAULT_PORT) {
				return "jdbc:mysql://127.0.0.1/" + URLEncoder.encode(database.toString(), StandardCharsets.UTF_8.name()) + "?useSSL=false"; // Java 10: No .name()
			} else {
				return "jdbc:mysql://127.0.0.1:" + port.getPort() + "/" + URLEncoder.encode(database.toString(), StandardCharsets.UTF_8.name()) + "?useSSL=false"; // Java 10: No .name()
			}
		} catch(UnsupportedEncodingException e) {
			throw new AssertionError("Standard encoding (" + StandardCharsets.UTF_8 + ") should always exist", e);
		}
	}

	/**
	 * Gets a connection to the MySQL server, this handles both master and slave scenarios.
	 */
	public static Connection getMySQLConnection(PosixPath failoverRoot, int nestedOperatingSystemVersion, Server.Name serverName, Port port) throws IOException, SQLException {
		if(port.getProtocol() != com.aoapps.net.Protocol.TCP) throw new IllegalArgumentException("Only TCP supported: " + port);
		String user, password;
		if(failoverRoot == null) {
			user = AOServDaemonConfiguration.getMySqlUser(serverName);
			password = AOServDaemonConfiguration.getMySqlPassword(serverName);
		} else {
			// Load the properties from the failover image
			File file = new File(failoverRoot + "/etc/opt/aoserv-daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
			if(!file.exists()) throw new IOException("Properties file doesn't exist: " + file.getPath());

			// TODO: Might be worth making AOServDaemonConfiguration more reusable, than duplicating so much here:
			Properties nestedProps = PropertiesUtils.loadFromFile(file);
			if(serverName == null) {
				// Assertion here, only to hint to update code when support of protocol 1.83.0 is removed
				assert true : "serverName is only null for protocol <= " + AOServDaemonProtocol.Version.VERSION_1_83_0;
				user = password = null;
			} else {
				user = nestedProps.getProperty("aoserv.daemon.mysql." + serverName + ".user");
				password = nestedProps.getProperty("aoserv.daemon.mysql." + serverName + ".password");
				if("[MYSQL_PASSWORD]".equals(password)) password = null;
			}
			if(user == null || user.isEmpty()) user = nestedProps.getProperty("aoserv.daemon.mysql.user");
			if(password == null || password.isEmpty()) {
				password = nestedProps.getProperty("aoserv.daemon.mysql.password");
				if("[MYSQL_PASSWORD]".equals(password)) password = null;
			}
		}

		// For simplicity, doesn't use connection pools
		try {
			Class.forName(AOServDaemonConfiguration.getMySqlDriver());
		} catch(ClassNotFoundException err) {
			throw new SQLException(err);
		}
		final String jdbcUrl = getJdbcUrl(port, Database.MYSQL);
		try {
			return DriverManager.getConnection(
				jdbcUrl,
				user,
				password
			);
		} catch(SQLException err) {
			//logger.log(Level.SEVERE, null, err);
			throw new SQLException("Unable to connect to MySQL database: jdbcUrl=" + jdbcUrl, err);
		}
	}

	public static void getSlaveStatus(PosixPath failoverRoot, int nestedOperatingSystemVersion, Server.Name serverName, Port port, StreamableOutput out) throws IOException, SQLException {
		try (Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, serverName, port)) {
			try {
				String currentSQL = null;
				try (
					Statement stmt = conn.createStatement();
					ResultSet results = stmt.executeQuery(currentSQL = "SHOW SLAVE STATUS")
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
				} catch(Error | RuntimeException | SQLException e) {
					ErrorPrinter.addSQL(e, currentSQL);
					throw e;
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
	}

	private static class TableStatusConcurrencyKey {

		private final PosixPath failoverRoot;
		private final Port port;
		private final Database.Name databaseName;
		private final int hash;

		private TableStatusConcurrencyKey(
			PosixPath failoverRoot,
			Port port,
			Database.Name databaseName
		) {
			this.failoverRoot = failoverRoot;
			this.port = port;
			this.databaseName = databaseName;
			int newHash = Objects.hashCode(failoverRoot);
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
				&& Objects.equals(failoverRoot, other.failoverRoot)
				&& databaseName.equals(other.databaseName)
			;
		}
	}

	private static final KeyedConcurrencyReducer<TableStatusConcurrencyKey, List<Database.TableStatus>> tableStatusLimiter = new KeyedConcurrencyReducer<>();

	public static void getTableStatus(PosixPath failoverRoot, int nestedOperatingSystemVersion, Server.Name serverName, Port port, Database.Name databaseName, StreamableOutput out) throws IOException, SQLException {
		List<Database.TableStatus> tableStatuses;
		try {
			tableStatuses = tableStatusLimiter.executeSerialized(
				new TableStatusConcurrencyKey(
					failoverRoot,
					port,
					databaseName
				),
				() -> {
					List<Database.TableStatus> statuses = new ArrayList<>();
					try (Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, serverName, port)) {
						try {
							String currentSQL = null;
							try (Statement stmt = conn.createStatement()) {
								boolean isMySQL40;
								try (ResultSet results = stmt.executeQuery(currentSQL = "SELECT VERSION()")) {
									if(!results.next()) throw new SQLException("No row returned");
									isMySQL40 = results.getString(1).startsWith(Server.VERSION_4_0_PREFIX);
								}
								try (ResultSet results = stmt.executeQuery(currentSQL = "SHOW TABLE STATUS FROM `" + databaseName + '`')) {
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
												new Database.TableStatus(
													Table_Name.valueOf(results.getString("Name")),
													engine==null ? null : Database.Engine.valueOf(engine),
													version,
													rowFormat==null ? null : Database.TableStatus.RowFormat.valueOf(rowFormat),
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
													collation==null ? null : Database.TableStatus.Collation.valueOf(collation),
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
							} catch(Error | RuntimeException | SQLException e) {
								ErrorPrinter.addSQL(e, currentSQL);
								throw e;
							}
						} catch(SQLException e) {
							conn.abort(AOServDaemon.executorService);
							throw e;
						}
					}
					return Collections.unmodifiableList(statuses);
				}
			);
		} catch(InterruptedException e) {
			// Restore the interrupted status
			Thread.currentThread().interrupt();
			throw new SQLException(e);
		} catch(ExecutionException e) {
			// Maintain expected exception types while not losing stack trace
			ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
			ExecutionExceptions.wrapAndThrow(e, SQLException.class, SQLException::new);
			throw new SQLException(e);
		}
		out.write(AOServDaemonProtocol.NEXT);
		int size = tableStatuses.size();
		out.writeCompressedInt(size);
		for(int c = 0; c < size; c++) {
			Database.TableStatus tableStatus = tableStatuses.get(c);
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

		private final PosixPath failoverRoot;
		private final Port port;
		private final Database.Name databaseName;
		private final Table_Name tableName;
		private final int hash;

		private CheckTableConcurrencyKey(
			PosixPath failoverRoot,
			Port port,
			Database.Name databaseName,
			Table_Name tableName
		) {
			this.failoverRoot = failoverRoot;
			this.port = port;
			this.databaseName = databaseName;
			this.tableName = tableName;
			int newHash = Objects.hashCode(failoverRoot);
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
				&& Objects.equals(failoverRoot, other.failoverRoot)
				&& databaseName.equals(other.databaseName)
				&& tableName.equals(other.tableName)
			;
		}
	}

	private static final KeyedConcurrencyReducer<CheckTableConcurrencyKey, List<Database.CheckTableResult>> checkTableLimiter = new KeyedConcurrencyReducer<>();

	/**
	 * Checks all tables, times-out in one minute.
	 */
	public static void checkTables(
		PosixPath failoverRoot,
		int nestedOperatingSystemVersion,
		Server.Name serverName,
		Port port,
		Database.Name databaseName,
		List<Table_Name> tableNames,
		StreamableOutput out
	) throws IOException, SQLException {
		Future<List<Database.CheckTableResult>> future = AOServDaemon.executorService.submit(() -> {
			List<Database.CheckTableResult> allTableResults = new ArrayList<>();
			for(final Table_Name tableName : tableNames) {
				if(!Database.isSafeName(tableName.toString())) {
					allTableResults.add(
						new Database.CheckTableResult(
							tableName,
							0,
							Database.CheckTableResult.MsgType.error,
							"Unsafe table name, refusing to check table"
						)
					);
				} else {
					try {
						allTableResults.addAll(
							checkTableLimiter.executeSerialized(
								new CheckTableConcurrencyKey(
									failoverRoot,
									// serverName, // Not needed, is already unique by port
									port,
									databaseName,
									tableName
								),
								() -> {
									final String dbNamePrefix = databaseName.toString()+'.';
									final long startTime = System.currentTimeMillis();
									try (Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, serverName, port)) {
										try {
											String currentSQL = null;
											try (
												Statement stmt = conn.createStatement();
												ResultSet results = stmt.executeQuery(currentSQL = "CHECK TABLE `" + databaseName + "`.`" + tableName + "` FAST QUICK")
											) {
												long duration = System.currentTimeMillis() - startTime;
												if(duration<0) duration = 0; // System time possibly reset
												final List<Database.CheckTableResult> tableResults = new ArrayList<>();
												while(results.next()) {
													try {
														String table = results.getString("Table");
														if(table.startsWith(dbNamePrefix)) table = table.substring(dbNamePrefix.length());
														final String msgType = results.getString("Msg_type");
														tableResults.add(
															new Database.CheckTableResult(
																Table_Name.valueOf(table),
																duration,
																msgType==null ? null : Database.CheckTableResult.MsgType.valueOf(msgType),
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
											} catch(Error | RuntimeException | SQLException e) {
												ErrorPrinter.addSQL(e, currentSQL);
												throw e;
											}
										} catch(SQLException e) {
											conn.abort(AOServDaemon.executorService);
											throw e;
										}
									}
								}
							)
						);
					} catch(InterruptedException e) {
						// Restore the interrupted status
						Thread.currentThread().interrupt();
						throw new SQLException(e);
					} catch(ExecutionException e) {
						// Maintain expected exception types while not losing stack trace
						ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
						ExecutionExceptions.wrapAndThrow(e, SQLException.class, SQLException::new);
						throw new SQLException(e);
					}
				}
			}
			return allTableResults;
		});
		try {
			List<Database.CheckTableResult> allTableResults = future.get(60, TimeUnit.SECONDS);
			out.write(AOServDaemonProtocol.NEXT);
			int size = allTableResults.size();
			out.writeCompressedInt(size);
			for(int c=0;c<size;c++) {
				Database.CheckTableResult checkTableResult = allTableResults.get(c);
				out.writeUTF(checkTableResult.getTable().toString());
				out.writeLong(checkTableResult.getDuration());
				out.writeNullEnum(checkTableResult.getMsgType());
				out.writeNullUTF(checkTableResult.getMsgText());
			}
		} catch(InterruptedException e) {
			// Restore the interrupted status
			Thread.currentThread().interrupt();
			InterruptedIOException ioErr = new InterruptedIOException();
			ioErr.initCause(e);
			throw ioErr;
		} catch(TimeoutException e) {
			throw new SQLException(e);
		} catch(ExecutionException e) {
			// Maintain expected exception types while not losing stack trace
			ExecutionExceptions.wrapAndThrow(e, IOException.class, IOException::new);
			ExecutionExceptions.wrapAndThrow(e, SQLException.class, SQLException::new);
			throw new SQLException(e);
		} finally {
			future.cancel(false);
		}
	}
}
