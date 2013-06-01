/*
 * Copyright 2002-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLDatabase.CheckTableResult;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.BufferManager;
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
import java.util.concurrent.Callable;
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

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            //AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                for(MySQLServer mysqlServer : thisAOServer.getMySQLServers()) {
                    String version=mysqlServer.getVersion().getVersion();
                    boolean modified=false;
                    // Get the connection to work through
                    AOConnectionPool pool=MySQLServerManager.getPool(mysqlServer);
                    Connection conn=pool.getConnection(false);
                    try {
                        // Get the list of all existing databases
                        Set<String> existing=new HashSet<String>();
                        Statement stmt=conn.createStatement();
                        try {
                            ResultSet results=stmt.executeQuery("show databases");
                            try {
                                while(results.next()) existing.add(results.getString(1));
                            } finally {
                                results.close();
                            }

                            // Create the databases that do not exist and should
                            for(MySQLDatabase database : mysqlServer.getMySQLDatabases()) {
                                String name=database.getName();
                                if(existing.contains(name)) existing.remove(name);
                                else {
                                    // Create the database
                                    stmt.executeUpdate("create database "+name);

                                    modified=true;
                                }
                            }

                            // Remove the extra databases
                            for(String dbName : existing) {
                                if(
                                    dbName.equals(MySQLDatabase.MYSQL)
                                    || (version.startsWith(MySQLServer.VERSION_5_0_PREFIX) && dbName.equals(MySQLDatabase.INFORMATION_SCHEMA))
                                    || (version.startsWith(MySQLServer.VERSION_5_1_PREFIX) && dbName.equals(MySQLDatabase.INFORMATION_SCHEMA))
                                ) {
                                    LogFactory.getLogger(MySQLDatabaseManager.class).log(Level.WARNING, null, new SQLException("Refusing to drop critical MySQL Database: "+dbName+" on "+mysqlServer));
                                } else {
                                    // Remove the extra database
                                    stmt.executeUpdate("drop database "+dbName);

                                    modified=true;
                                }
                            }
                        } finally {
                            stmt.close();
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
    
    /*public static void backupDatabase(CompressedDataInputStream masterIn, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        int pkey=masterIn.readCompressedInt();
        MySQLDatabase md=AOServDaemon.getConnector().mysqlDatabases.get(pkey);
        if(md==null) throw new SQLException("Unable to find MySQLDatabase: "+pkey);
        String dbName=md.getName();
        MySQLServer ms=md.getMySQLServer();
        UnixFile tempFile=UnixFile.mktemp("/tmp/backup_mysql_database.sql.gz.", true);
        try {
            // Dump, count raw bytes, create MD5, and compress to a temp file
            String path;
            if(
                osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) {
                path="/usr/aoserv/daemon/bin/backup_mysql_database";
            } else if(
                osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) {
                path="/opt/aoserv-daemon/bin/backup_mysql_database";
            } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            String[] command={
                path,
                dbName,
                ms.getMinorVersion(),
                Integer.toString(ms.getNetBind().getPort().getPort()),
                tempFile.getPath()
            };
            long dataSize;
            Process P=Runtime.getRuntime().exec(command);
            try {
                P.getOutputStream().close();
                BufferedReader dumpIn=new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    dataSize=Long.parseLong(dumpIn.readLine());
                } finally {
                    dumpIn.close();
                }
            } finally {
                try {
                    int retCode=P.waitFor();
                    if(retCode!=0) throw new IOException("backup_mysql_database exited with non-zero return code: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }
            }

            MD5InputStream md5In=new MD5InputStream(new CorrectedGZIPInputStream(new BufferedInputStream(new FileInputStream(tempFile.getPath()))));
            try {
                byte[] buff=BufferManager.getBytes();
                try {
                    while(md5In.read(buff, 0, BufferManager.BUFFER_SIZE)!=-1);
                    md5In.close();

                    byte[] md5=md5In.hash();
                    long md5_hi=MD5.getMD5Hi(md5);
                    long md5_lo=MD5.getMD5Lo(md5);

                    masterOut.write(AOServDaemonProtocol.NEXT);
                    masterOut.writeLong(dataSize);
                    masterOut.writeLong(md5_hi);
                    masterOut.writeLong(md5_lo);
                    masterOut.flush();

                    boolean sendData=masterIn.readBoolean();
                    if(sendData) {
                        InputStream tmpIn=new FileInputStream(tempFile.getFile());
                        int ret;
                        while((ret=tmpIn.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                            masterOut.write(AOServDaemonProtocol.NEXT);
                            masterOut.writeShort(ret);
                            masterOut.write(buff, 0, ret);
                        }
                    }
                } finally {
                    BufferManager.release(buff);
                }
                masterOut.write(AOServDaemonProtocol.DONE);
                masterOut.flush();
            } finally {
                md5In.close();
            }
        } finally {
            if(tempFile.getStat().exists()) tempFile.delete();
        }
    }*/

    public static void dumpDatabase(MySQLDatabase md, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        UnixFile tempFile=UnixFile.mktemp("/tmp/dump_mysql_database.sql.", true);
        try {
            String dbName=md.getName();
            MySQLServer ms=md.getMySQLServer();
            String path;
            if(
                osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) {
                path="/usr/aoserv/daemon/bin/dump_mysql_database";
            } else if(
                osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) {
                path="/opt/aoserv-daemon/bin/dump_mysql_database";
            } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
            String[] command={
                path,
                dbName,
                ms.getMinorVersion(),
                Integer.toString(ms.getNetBind().getPort().getPort()),
                tempFile.getPath()
            };
            AOServDaemon.exec(command);

            InputStream dumpin=new FileInputStream(tempFile.getFile());
            try {
                byte[] buff=BufferManager.getBytes();
                try {
                    int ret;
                    while((ret=dumpin.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                        masterOut.writeByte(AOServDaemonProtocol.NEXT);
                        masterOut.writeShort(ret);
                        masterOut.write(buff, 0, ret);
                    }
                } finally {
                    BufferManager.release(buff, false);
                }
            } finally {
                dumpin.close();
            }
        } finally {
            if(tempFile.getStat().exists()) tempFile.delete();
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(MySQLDatabaseManager.class)
                && mysqlDatabaseManager==null
            ) {
                System.out.print("Starting MySQLDatabaseManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                mysqlDatabaseManager=new MySQLDatabaseManager();
                conn.getMysqlDatabases().addTableListener(mysqlDatabaseManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(mysqlDatabaseManager!=null) mysqlDatabaseManager.waitForBuild();
    }

    public String getProcessTimerDescription() {
        return "Rebuild MySQL Databases";
    }
    
    public static void getMasterStatus(int mysqlServer, CompressedDataOutputStream out) throws IOException, SQLException {
        // Use the existing pools
        MySQLServer ms = AOServDaemon.getConnector().getMysqlServers().get(mysqlServer);
        if(ms==null) throw new SQLException("Unable to find MySQLServer: "+mysqlServer);

        AOConnectionPool pool=MySQLServerManager.getPool(ms);
        Connection conn=pool.getConnection(true);
        try {
            Statement stmt = conn.createStatement();
            try {
                ResultSet results = stmt.executeQuery("SHOW MASTER STATUS");
                try {
                    if(results.next()) {
                        out.write(AOServDaemonProtocol.NEXT);
                        out.writeNullUTF(results.getString("File"));
                        out.writeNullUTF(results.getString("Position"));
                    } else {
                        out.write(AOServDaemonProtocol.DONE);
                    }
                } finally {
                    results.close();
                }
            } finally {
                stmt.close();
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
        if(nestedOperatingSystemVersion==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            file = new File(failoverRoot+"/etc/aoserv/daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
        } else if(
            nestedOperatingSystemVersion==OperatingSystemVersion.REDHAT_ES_4_X86_64
            || nestedOperatingSystemVersion==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) {
            file = new File(failoverRoot+"/etc/opt/aoserv-daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
        } else throw new SQLException("Unsupport OperatingSystemVersion: "+nestedOperatingSystemVersion);
        if(!file.exists()) throw new SQLException("Properties file doesn't exist: "+file.getPath());
        Properties nestedProps = new Properties();
        InputStream in = new FileInputStream(file);
        try {
            nestedProps.load(in);
        } finally {
            in.close();
        }
        String user = nestedProps.getProperty("aoserv.daemon.mysql.user");
        String password = nestedProps.getProperty("aoserv.daemon.mysql.password");

        // For simplicity, doesn't use connection pools
        try {
            Class.forName(AOServDaemonConfiguration.getMySqlDriver()).newInstance();
        } catch(ClassNotFoundException err) {
            SQLException sqlErr = new SQLException();
            sqlErr.initCause(err);
            throw sqlErr;
        } catch(InstantiationException err) {
            SQLException sqlErr = new SQLException();
            sqlErr.initCause(err);
            throw sqlErr;
        } catch(IllegalAccessException err) {
            SQLException sqlErr = new SQLException();
            sqlErr.initCause(err);
            throw sqlErr;
        }
        try {
            return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:"+port+"/mysql",
                user,
                password
            );
        } catch(SQLException err) {
            LogFactory.getLogger(MySQLDatabaseManager.class).log(Level.SEVERE, null, err);
            throw new SQLException("Unable to connect to slave.");
        }
    }

    public static void getSlaveStatus(String failoverRoot, int nestedOperatingSystemVersion, int port, CompressedDataOutputStream out) throws IOException, SQLException {
        Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, port);
        try {
            Statement stmt = conn.createStatement();
            try {
                ResultSet results = stmt.executeQuery("SHOW SLAVE STATUS");
                try {
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
                } finally {
                    results.close();
                }
            } finally {
                stmt.close();
            }
        } finally {
            conn.close();
        }
    }

    public static void getTableStatus(String failoverRoot, int nestedOperatingSystemVersion, int port, String databaseName, CompressedDataOutputStream out) throws IOException, SQLException {
        List<MySQLDatabase.TableStatus> tableStatuses = new ArrayList<MySQLDatabase.TableStatus>();
        Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, port);
        try {
            Statement stmt = conn.createStatement();
            try {
                boolean isMySQL40;
                ResultSet results = stmt.executeQuery("SELECT version()");
                try {
                    if(!results.next()) throw new SQLException("No row returned");
                    isMySQL40 = results.getString(1).startsWith(MySQLServer.VERSION_4_0_PREFIX);
                } finally {
                    results.close();
                }
                results = stmt.executeQuery("SHOW TABLE STATUS FROM "+databaseName);
                try {
                    while(results.next()) {
                        String engine = results.getString(isMySQL40 ? "Type" : "Engine");
                        Integer version;
                        if(isMySQL40) version = null;
                        else {
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
                            IOException ioErr = new IOException(err.toString());
                            ioErr.initCause(err);
                            throw ioErr;
                        }
                    }
                } finally {
                    results.close();
                }
            } finally {
                stmt.close();
            }
        } finally {
            conn.close();
        }
        out.write(AOServDaemonProtocol.NEXT);
        int size = tableStatuses.size();
        out.writeCompressedInt(size);
        for(int c=0;c<size;c++) {
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

	private static final ConcurrencyLimiter<CheckTableConcurrencyKey,List<MySQLDatabase.CheckTableResult>> checkTableLimiter = new ConcurrencyLimiter<CheckTableConcurrencyKey,List<MySQLDatabase.CheckTableResult>>();

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
		Future<List<MySQLDatabase.CheckTableResult>> future = AOServDaemon.executorService.submit(
			new Callable<List<MySQLDatabase.CheckTableResult>>() {
				public List<CheckTableResult> call() throws Exception {
					List<MySQLDatabase.CheckTableResult> allTableResults = new ArrayList<MySQLDatabase.CheckTableResult>();
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
									checkTableLimiter.executeSerialized(
										new CheckTableConcurrencyKey(
											failoverRoot,
											port,
											databaseName,
											tableName
										),
										new Callable<List<MySQLDatabase.CheckTableResult>>() {
											public List<MySQLDatabase.CheckTableResult> call() throws Exception {
												final String dbNamePrefix = databaseName+'.';
												final long startTime = System.currentTimeMillis();
												final Connection conn = getMySQLConnection(failoverRoot, nestedOperatingSystemVersion, port);
												try {
													final Statement stmt = conn.createStatement();
													try {
														final ResultSet results = stmt.executeQuery("CHECK TABLE `"+databaseName+"`.`"+tableName+"` FAST QUICK");
														try {
															long duration = System.currentTimeMillis() - startTime;
															if(duration<0) duration = 0; // System time possibly reset
															final List<MySQLDatabase.CheckTableResult> tableResults = new ArrayList<MySQLDatabase.CheckTableResult>();
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
																	IOException ioErr = new IOException(err.toString());
																	ioErr.initCause(err);
																	throw ioErr;
																}
															}
															return tableResults;
														} finally {
															results.close();
														}
													} finally {
														stmt.close();
													}
												} finally {
													conn.close();
												}
											}
										}
									)
								);
							} catch(InterruptedException e) {
								SQLException sqlExc = new SQLException();
								sqlExc.initCause(e);
								throw sqlExc;
							} catch(ExecutionException e) {
								SQLException sqlExc = new SQLException();
								sqlExc.initCause(e);
								throw sqlExc;
							}
						}
					}
					return allTableResults;
				}
			}
		);
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
			IOException newExc = new InterruptedIOException();
			newExc.initCause(exc);
			throw newExc;
		} catch(ExecutionException exc) {
			SQLException newExc = new SQLException();
			newExc.initCause(exc);
			throw newExc;
		} catch(TimeoutException exc) {
			SQLException newExc = new SQLException();
			newExc.initCause(exc);
			throw newExc;
		} finally {
			future.cancel(false);
		}
    }
}
