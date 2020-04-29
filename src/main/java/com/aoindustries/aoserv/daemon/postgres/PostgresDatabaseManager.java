/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2016, 2017, 2018, 2019, 2020  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.postgresql.Database;
import com.aoindustries.aoserv.client.postgresql.Server;
import com.aoindustries.aoserv.client.postgresql.User;
import com.aoindustries.aoserv.client.postgresql.UserServer;
import com.aoindustries.aoserv.client.postgresql.Version;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.Schedule;
import com.aoindustries.io.stream.StreamableOutput;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.BufferManager;
import com.aoindustries.validation.ValidationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresDatabaseManager extends BuilderThread implements CronJob {

	private static final Logger logger = Logger.getLogger(PostgresDatabaseManager.class.getName());

	private PostgresDatabaseManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServConnector connector = AOServDaemon.getConnector();
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				for(Server ps : thisServer.getPostgresServers()) {
					List<Database> pds = ps.getPostgresDatabases();
					if(pds.isEmpty()) {
						logger.severe("No databases; refusing to rebuild config: " + ps);
					} else {
						int port = ps.getBind().getPort().getPort();
						Version pv = ps.getVersion();
						String version = pv.getTechnologyVersion(connector).getVersion();
						String minorVersion = pv.getMinorVersion();

						// Get the connection to work through
						AOConnectionPool pool = PostgresServerManager.getPool(ps);
						Connection conn = pool.getConnection(false);
						try {
							// Get the list of all existing databases
							Set<Database.Name> existing = new HashSet<>();
							try (Statement stmt = conn.createStatement()) {
								try (ResultSet results = stmt.executeQuery("SELECT datname FROM pg_database")) {
									try {
										while(results.next()) {
											Database.Name datname = Database.Name.valueOf(results.getString(1));
											if(!existing.add(datname)) throw new SQLException("Duplicate database name: " + datname);
										}
									} catch(ValidationException e) {
										throw new SQLException(e);
									}
								}
							}

							// Create the databases that do not exist and should
							for(Database database : pds) {
								Database.Name name = database.getName();
								if(!existing.remove(name)) {
									if(database.isSpecial()) {
										logger.log(
											Level.WARNING,
											null,
											new SQLException("Refusing to create special database: " + name + " on " + ps.getName())
										);
									} else {
										UserServer datdba = database.getDatDBA();
										if(
											version.startsWith(Version.VERSION_7_1 + '.')
											|| version.startsWith(Version.VERSION_7_2 + '.')
										) {
											// Create the database
											try (Statement stmt = conn.createStatement()) {
												stmt.executeUpdate("CREATE DATABASE \"" + name + "\" WITH TEMPLATE = template0 ENCODING = '" + database.getPostgresEncoding().getEncoding() + '\'');
											}
											// Set the owner
											try (PreparedStatement pstmt = conn.prepareStatement("UPDATE pg_database SET datdba=(SELECT usesysid FROM pg_user WHERE usename=?) WHERE datname=?")) {
												pstmt.setString(1, datdba.getPostgresUser_username().toString());
												pstmt.setString(2, name.toString());
												pstmt.executeUpdate();
											}
										} else if(
											version.startsWith(Version.VERSION_7_3 + '.')
											|| version.startsWith(Version.VERSION_8_0 + '.')
											|| version.startsWith(Version.VERSION_8_1 + '.')
											|| version.startsWith(Version.VERSION_8_3 + '.')
											|| version.startsWith(Version.VERSION_8_3 + 'R')
											|| version.startsWith(Version.VERSION_9_4 + '.')
											|| version.startsWith(Version.VERSION_9_4 + 'R')
											|| version.startsWith(Version.VERSION_9_5 + '.')
											|| version.startsWith(Version.VERSION_9_5 + 'R')
											|| version.startsWith(Version.VERSION_9_6 + '.')
											|| version.startsWith(Version.VERSION_9_6 + 'R')
											|| version.startsWith(Version.VERSION_10 + '.')
											|| version.startsWith(Version.VERSION_10 + 'R')
											|| version.startsWith(Version.VERSION_11 + '.')
											|| version.startsWith(Version.VERSION_11 + 'R')
										) {
											try (Statement stmt = conn.createStatement()) {
												stmt.executeUpdate("CREATE DATABASE \"" + name + "\" WITH OWNER = \"" + datdba.getPostgresUser_username() + "\" TEMPLATE = template0 ENCODING = '" + database.getPostgresEncoding().getEncoding() + '\'');
											}
										} else {
											throw new SQLException("Unsupported version of PostgreSQL: " + version);
										}

										// createlang
										try {
											final String createlang;
											final String psql;
											final String lib;
											final String share;
											switch(osvId) {
												case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64:
													createlang = "/opt/postgresql-" + minorVersion + "-i686/bin/createlang";
													psql = "/opt/postgresql-" + minorVersion + "-i686/bin/psql";
													lib = "/opt/postgresql-" + minorVersion + "-i686/lib";
													share = "/opt/postgresql-" + minorVersion + "-i686/share";
													break;
												case OperatingSystemVersion.REDHAT_ES_4_X86_64:
												case OperatingSystemVersion.CENTOS_7_X86_64:
													createlang = "/opt/postgresql-" + minorVersion + "/bin/createlang";
													psql = "/opt/postgresql-" + minorVersion + "/bin/psql";
													lib = "/opt/postgresql-" + minorVersion + "/lib";
													share = "/opt/postgresql-" + minorVersion + "/share";
													break;
												case OperatingSystemVersion.MANDRIVA_2006_0_I586:
													createlang = "/usr/postgresql/" + minorVersion + "/bin/createlang";
													psql = "/usr/postgresql/" + minorVersion + "/bin/psql";
													lib = "/usr/postgresql/" + minorVersion + "/lib";
													share = "/usr/postgresql/" + minorVersion + "/share";
													break;
												default:
													throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
											}
											// Automatically add plpgsql support for PostgreSQL 7 and 8
											// PostgreSQL 9 is already installed:
											//     bash-3.2$ /opt/postgresql-9.4-i686/bin/createlang -p 5461 plpgsql asdfdasf
											//     createlang: language "plpgsql" is already installed in database "asdfdasf"
											if(
												version.startsWith("7.")
												|| version.startsWith("8.")
											) {
												AOServDaemon.suexec(com.aoindustries.aoserv.client.linux.User.POSTGRES, createlang + " -p " + port + " plpgsql " + name, 0);
											}
											if(
												version.startsWith(Version.VERSION_7_1 + '.')
												|| version.startsWith(Version.VERSION_7_2 + '.')
												|| version.startsWith(Version.VERSION_7_3 + '.')
												|| version.startsWith(Version.VERSION_8_0 + '.')
												|| version.startsWith(Version.VERSION_8_1 + '.')
												// Not supported as of 8.3 - it has built-in full text indexing
											) {
												AOServDaemon.suexec(com.aoindustries.aoserv.client.linux.User.POSTGRES, psql + " -p " + port + " -c \"create function fti() returns opaque as '" + lib + "/mfti.so' language 'c';\" " + name, 0);
											}
											if(database.getEnablePostgis()) {
												AOServDaemon.suexec(com.aoindustries.aoserv.client.linux.User.POSTGRES, psql + " -p " + port + " " + name + " -f " + share + "/lwpostgis.sql", 0);
												AOServDaemon.suexec(com.aoindustries.aoserv.client.linux.User.POSTGRES, psql + " -p " + port + " " + name + " -f " + share + "/spatial_ref_sys.sql", 0);
											}
										} catch(IOException err) {
											try {
												// Drop the new database if not fully configured
												try (Statement stmt = conn.createStatement()) {
													stmt.executeUpdate("DROP DATABASE \"" + name + '"');
												}
											} catch(SQLException err2) {
												logger.log(Level.SEVERE, null, err2);
												throw err2;
											}
											throw err;
										}
									}
								}
							}

							// Remove the extra databases
							for(Database.Name dbName : existing) {
								// Remove the extra database
								if(Database.isSpecial(dbName)) {
									logger.log(
										Level.WARNING,
										null,
										new SQLException("Refusing to drop special database: " + dbName + " on " + ps)
									);
								} else {
									// Dump database before dropping
									dumpDatabase(
										ps,
										dbName,
										BackupManager.getNextBackupFile("-postgresql-" + ps.getName()+"-"+dbName+".sql.gz"),
										true
									);
									// Now drop
									try (Statement stmt = conn.createStatement()) {
										stmt.executeUpdate("DROP DATABASE \"" + dbName + '"');
									}
								}
							}
						} finally {
							pool.releaseConnection(conn);
						}
					}
				}
			}
			return true;
		} catch(RuntimeException | IOException | SQLException T) {
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void dumpDatabase(
		Database pd,
		AOServDaemonProtocol.Version protocolVersion,
		StreamableOutput masterOut,
		boolean gzip
	) throws IOException, SQLException {
		UnixFile tempFile=UnixFile.mktemp(
			gzip
				? "/tmp/dump_postgres_database.sql.gz."
				: "/tmp/dump_postgres_database.sql.",
			true
		);
		try {
			dumpDatabase(
				pd.getPostgresServer(),
				pd.getName(),
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
		Server ps,
		Database.Name dbName,
		File output,
		boolean gzip
	) throws IOException, SQLException {
		String commandPath;
		{
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				commandPath = "/opt/aoserv-daemon/bin/dump_postgres_database";
			} else if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
				commandPath = "/usr/aoserv/daemon/bin/dump_postgres_database";
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
		// Make sure perl is installed as required by dump_postgres_database
		PackageManager.installPackage(PackageManager.PackageName.PERL);
		if(gzip) PackageManager.installPackage(PackageManager.PackageName.GZIP);
		AOServDaemon.exec(
			commandPath,
			ps.getVersion().getMinorVersion(),
			Integer.toString(ps.getBind().getPort().getPort()),
			dbName.toString(),
			output.getPath(),
			Boolean.toString(gzip)
		);
		if(output.length() == 0) throw new SQLException("Empty dump file: " + output);
	}

	private static PostgresDatabaseManager postgresDatabaseManager;
	private static boolean cronStarted = false;
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
				&& AOServDaemonConfiguration.isManagerEnabled(PostgresDatabaseManager.class)
				&& (
					postgresDatabaseManager == null
					|| !cronStarted
				)
			) {
				System.out.print("Starting PostgresDatabaseManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					if(postgresDatabaseManager == null) {
						AOServConnector conn = AOServDaemon.getConnector();
						postgresDatabaseManager = new PostgresDatabaseManager();
						conn.getPostgresql().getDatabase().addTableListener(postgresDatabaseManager, 0);
					}
					if(!cronStarted) {
						CronDaemon.addCronJob(postgresDatabaseManager, logger);
						cronStarted = true;
					}
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void waitForRebuild() {
		if(postgresDatabaseManager!=null) postgresDatabaseManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild PostgreSQL Databases";
	}

	/**
	 * Runs for automatic vacuuming and reindexing of all user tables, at 1:05 every Sunday.
	 * REINDEX is only called on the first Sunday of the month.
	 */
	private static final Schedule schedule = (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) ->
		minute==5
		&& hour==1
		&& dayOfWeek==Calendar.SUNDAY
	;

	@Override
	public Schedule getSchedule() {
		return schedule;
	}

	/**
	 * Since the VACUUM FULL and REINDEX commands use exclusive locks on each table, we want it to finish as soon as possible.
	 */
	@Override
	public int getThreadPriority() {
		return Thread.NORM_PRIORITY + 2;
	}

	// TODO: This should be moved to scripts in the relevant postgresql-* packages, so the system still works correctly with disabled aoserv-daemon
	@Override
	public void run(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
		try {
			AOServConnector aoservConn = AOServDaemon.getConnector();
			//DatabaseTable postgresDatabaseTable = aoservConn.getPostgresql().getDatabase();
			// Only REINDEX on the first Sunday of the month
			boolean isReindexTime=dayOfMonth<=7;
			List<String> tableNames=new ArrayList<>();
			List<String> schemas=new ArrayList<>();
			for(Server postgresServer : AOServDaemon.getThisServer().getPostgresServers()) {
				Server.Name serverName = postgresServer.getName();
				String postgresServerVersion=postgresServer.getVersion().getTechnologyVersion(aoservConn).getVersion();
				boolean postgresServerHasSchemas =
					!postgresServerVersion.startsWith(Version.VERSION_7_1 + '.')
					&& !postgresServerVersion.startsWith(Version.VERSION_7_2 + '.')
				;
				boolean postgresServerHasVacuumFull =
					!postgresServerVersion.startsWith(Version.VERSION_7_1 + '.')
				;
				for(Database postgresDatabase : postgresServer.getPostgresDatabases()) {
					if(
						!postgresDatabase.isTemplate()
						&& postgresDatabase.allowsConnections()
					) {
						AOConnectionPool pool;
						Connection conn;
						if(postgresDatabase.getName().equals(Database.AOSERV)) {
							// If the aoserv database, use the existing connection pools
							pool = PostgresServerManager.getPool(postgresServer);
							conn = pool.getConnection();
						} else {
							// For other databases, establish a connection directly
							pool = null;
							Class.forName(postgresDatabase.getJdbcDriver()).getConstructor().newInstance();
							conn = DriverManager.getConnection(
								postgresDatabase.getJdbcUrl(true),
								User.POSTGRES.toString(),
								AOServDaemonConfiguration.getPostgresPassword(serverName)
							);
							conn.setAutoCommit(true);
						}
						try {
							try (
								PreparedStatement pstmt = conn.prepareStatement(
									postgresServerHasSchemas
									? "SELECT tablename, schemaname FROM pg_tables WHERE tableowner != ?"
									: "SELECT tablename FROM pg_tables WHERE tableowner != ?"
								)
							) {
								pstmt.setString(1, User.POSTGRES.toString());
								try (ResultSet results = pstmt.executeQuery()) {
									tableNames.clear();
									if(postgresServerHasSchemas) schemas.clear();
									while(results.next()) {
										tableNames.add(results.getString(1));
										if(postgresServerHasSchemas) schemas.add(results.getString(2));
									}
								}
							}
							for(int c=0;c<tableNames.size();c++) {
								String tableName = tableNames.get(c);
								String schema = postgresServerHasSchemas ? schemas.get(c) : null;
								if(Database.Name.validate(tableName).isValid()) {
									if(
										!postgresServerHasSchemas
										|| "public".equals(schema)
										|| (
											schema != null
											&& Database.Name.validate(schema).isValid()
										)
									) {
										// VACUUM the table
										try (Statement stmt = conn.createStatement()) {
											stmt.executeUpdate(
												postgresServerHasVacuumFull
												? (
													postgresServerHasSchemas
													? ("VACUUM FULL ANALYZE \"" + schema + "\".\"" + tableName + '"')
													: ("VACUUM FULL ANALYZE \"" + tableName + '"')
												) : (
													postgresServerHasSchemas
													? ("VACUUM ANALYZE \"" + schema + "\".\"" + tableName + '"')
													: ("VACUUM ANALYZE \"" + tableName + '"')
												)
											);
											if(isReindexTime) {
												// REINDEX the table
												stmt.executeUpdate(
													postgresServerHasSchemas
													? ("REINDEX TABLE \"" + schema + "\".\"" + tableName + '"')
													: ("REINDEX TABLE \"" + tableName + '"')
												);
											}
										}
									} else {
										logger.log(Level.WARNING, "schema=" + schema, new SQLWarning("Warning: not calling VACUUM or REINDEX because schema name does not pass the database name checks.  This is to make sure specially-crafted schema names cannot be used to execute arbitrary SQL with administrative privileges."));
									}
								} else {
									logger.log(Level.WARNING, "tableName=" + tableName, new SQLWarning("Warning: not calling VACUUM or REINDEX because table name does not pass the database name checks.  This is to make sure specially-crafted table names cannot be used to execute arbitrary SQL with administrative privileges."));
								}
							}
						} finally {
							if(pool != null) pool.releaseConnection(conn);
							else conn.close();
						}
					}
				}
			}
		} catch(RuntimeException | ReflectiveOperationException | IOException | SQLException T) {
			logger.log(Level.SEVERE, null, T);
		}
	}
}