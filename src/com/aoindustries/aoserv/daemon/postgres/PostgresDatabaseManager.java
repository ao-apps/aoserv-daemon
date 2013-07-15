/*
 * Copyright 2001-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresDatabase;
import com.aoindustries.aoserv.client.PostgresDatabaseTable;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresServerUser;
import com.aoindustries.aoserv.client.PostgresUser;
import com.aoindustries.aoserv.client.PostgresVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.SortedArrayList;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Level;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresDatabaseManager extends BuilderThread implements CronJob {

    private PostgresDatabaseManager() {
    }

    private static final Object rebuildLock=new Object();
	@Override
    protected boolean doRebuild() {
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                for(PostgresServer ps : thisAOServer.getPostgresServers()) {
                    int port=ps.getNetBind().getPort().getPort();
                    PostgresVersion pv=ps.getPostgresVersion();
                    String version=pv.getTechnologyVersion(connector).getVersion();
                    String minorVersion=pv.getMinorVersion();

                    // Get the connection to work through
                    AOConnectionPool pool=PostgresServerManager.getPool(ps);
                    Connection conn=pool.getConnection(false);
                    try {
                        // Get the list of all existing databases
                        List<String> existing=new SortedArrayList<>();
                        try (Statement stmt = conn.createStatement()) {
                            try (ResultSet results = stmt.executeQuery("select datname from pg_database")) {
                                while(results.next()) existing.add(results.getString(1));
                            }

                            // Create the databases that do not exist and should
                            for(PostgresDatabase database : ps.getPostgresDatabases()) {
                                String name=database.getName();
                                if(existing.contains(name)) existing.remove(name);
                                else {
                                    PostgresServerUser datdba=database.getDatDBA();
                                    if(
                                        version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_0+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                                    ) {
                                        stmt.executeUpdate("create database "+name+" with owner="+datdba.getPostgresUser().getUsername().getUsername()+" encoding='"+database.getPostgresEncoding().getEncoding()+'\'');
                                        conn.commit();
                                    } else if(
                                        version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                        || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                    ) {
                                        // Create the database
                                        stmt.executeUpdate("create database "+name+" with encoding='"+database.getPostgresEncoding().getEncoding()+"'");
                                        stmt.executeUpdate("update pg_database set datdba=(select usesysid from pg_user where usename='"+datdba.getPostgresUser().getUsername().getUsername()+"') where datname='"+name+"'");
                                        conn.commit();
                                    } else throw new SQLException("Unsupported version of PostgreSQL: "+version);

                                    // Automatically add plpgsql support
                                    try {
                                        String createlang;
                                        String psql;
                                        String lib;
                                        String share;
                                        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                            createlang = "/opt/postgresql-"+minorVersion+"-i686/bin/createlang";
                                            psql = "/opt/postgresql-"+minorVersion+"-i686/bin/psql";
                                            lib = "/opt/postgresql-"+minorVersion+"-i686/lib";
                                            share = "/opt/postgresql-"+minorVersion+"-i686/share";
                                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                            createlang = "/opt/postgresql-"+minorVersion+"/bin/createlang";
                                            psql = "/opt/postgresql-"+minorVersion+"/bin/psql";
                                            lib = "/opt/postgresql-"+minorVersion+"/lib";
                                            share = "/opt/postgresql-"+minorVersion+"/share";
                                        } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                            createlang = "/usr/postgresql/"+minorVersion+"/bin/createlang";
                                            psql = "/usr/postgresql/"+minorVersion+"/bin/psql";
                                            lib = "/usr/postgresql/"+minorVersion+"/lib";
                                            share = "/usr/postgresql/"+minorVersion+"/share";
                                        } else {
                                            throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                                        }
                                        AOServDaemon.suexec(LinuxAccount.POSTGRES, createlang+" -p "+port+" plpgsql "+name, 0);
                                        if(
                                            version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                            || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                            || version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_0+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                            // Not supported as of 8.3 - it has built-in full text indexing
                                        ) {
                                            AOServDaemon.suexec(LinuxAccount.POSTGRES, psql+" -p "+port+" -c \"create function fti() returns opaque as '"+lib+"/mfti.so' language 'c';\" "+name, 0);
                                        }
                                        if(database.getEnablePostgis()) {
                                            AOServDaemon.suexec(LinuxAccount.POSTGRES, psql+" -p "+port+" "+name+" -f "+share+"/lwpostgis.sql", 0);
                                            AOServDaemon.suexec(LinuxAccount.POSTGRES, psql+" -p "+port+" "+name+" -f "+share+"/spatial_ref_sys.sql", 0);
                                        }
                                    } catch(IOException err) {
                                        try {
                                            // Drop the new database if not fully configured
                                            stmt.executeUpdate("drop database "+name);
                                            conn.commit();
                                        } catch(SQLException err2) {
                                            LogFactory.getLogger(PostgresDatabaseManager.class).log(Level.SEVERE, null, err2);
                                            throw err2;
                                        }
                                        throw err;
                                    }
                                }
                            }

                            // Remove the extra databases
                            for(int d=0;d<existing.size();d++) {
                                // Remove the extra database
                                String dbName=existing.get(d);
                                if(
                                    dbName.equals(PostgresDatabase.TEMPLATE0)
                                    || dbName.equals(PostgresDatabase.TEMPLATE1)
                                    || dbName.equals(PostgresDatabase.AOSERV)
                                    || dbName.equals(PostgresDatabase.AOINDUSTRIES)
                                    || dbName.equals(PostgresDatabase.AOWEB)
                                ) throw new SQLException("AOServ Daemon will not automatically drop database, please drop manually: "+dbName);
                                stmt.executeUpdate("drop database "+dbName);
                                conn.commit();
                            }
                        }
                    } finally {
                        pool.releaseConnection(conn);
                    }
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(PostgresDatabaseManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    public static void dumpDatabase(PostgresDatabase pd, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        UnixFile tempFile=UnixFile.mktemp("/tmp/dump_postgres_database.sql.", true);
        try {
            //AOServConnector conn=AOServDaemon.getConnector();
            PostgresServer ps=pd.getPostgresServer();
            String minorVersion=ps.getPostgresVersion().getMinorVersion();
            int port=ps.getNetBind().getPort().getPort();
            String dbName=pd.getName();

            int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            String commandPath;
            if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                commandPath = "/opt/aoserv-daemon/bin/dump_postgres_database";
            } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                commandPath = "/usr/aoserv/daemon/bin/dump_postgres_database";
            } else {
                throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
            }
            String[] command={
                commandPath,
                minorVersion,
                Integer.toString(port),
                dbName,
                tempFile.getPath()
            };
            AOServDaemon.exec(command);
            try (InputStream dumpin = new FileInputStream(tempFile.getFile())) {
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
            }
        } finally {
            if(tempFile.getStat().exists()) tempFile.delete();
        }
    }

    private static PostgresDatabaseManager postgresDatabaseManager;
    private static boolean cronStarted = false;
    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(PostgresDatabaseManager.class)
                && (postgresDatabaseManager==null || !cronStarted)
            ) {
                System.out.print("Starting PostgresDatabaseManager: ");
                if(postgresDatabaseManager==null) {
                    AOServConnector conn=AOServDaemon.getConnector();
                    postgresDatabaseManager=new PostgresDatabaseManager();
                    conn.getPostgresDatabases().addTableListener(postgresDatabaseManager, 0);
                }
                if(!cronStarted) {
                    CronDaemon.addCronJob(postgresDatabaseManager, LogFactory.getLogger(PostgresDatabaseManager.class));
                    cronStarted=true;
                }
                System.out.println("Done");
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

    private static final Schedule schedule = new Schedule() {
        /**
         * Runs once a month for automatic vacuuming and reindexing of all user tables, at 1:05 every Sunday.
         * REINDEX is only called on the first Sunday of the month.
         */
		@Override
        public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
            return
                minute==5
                && hour==1
                && dayOfWeek==Calendar.SUNDAY
            ;
        }
    };

	@Override
    public Schedule getCronJobSchedule() {
        return schedule;
    }
    
	@Override
    public CronJobScheduleMode getCronJobScheduleMode() {
        return CronJobScheduleMode.SKIP;
    }

	@Override
    public String getCronJobName() {
        return "PostgresDatabaseManager";
    }

    /**
     * Since the VACUUM FULL and REINDEX commands use exclusive locks on each table, we want it to finish as soon as possible.
     */
	@Override
    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY+2;
    }

	@Override
    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        try {
            AOServConnector aoservConn = AOServDaemon.getConnector();
            PostgresDatabaseTable postgresDatabaseTable = aoservConn.getPostgresDatabases();
            // Only REINDEX on the first Sunday of the month
            boolean isReindexTime=dayOfMonth<=7;
            List<String> tableNames=new ArrayList<>();
            List<String> schemas=new ArrayList<>();
            for(PostgresServer postgresServer : AOServDaemon.getThisAOServer().getPostgresServers()) {
                String postgresServerVersion=postgresServer.getPostgresVersion().getTechnologyVersion(aoservConn).getVersion();
                boolean postgresServerHasSchemas=
                    !postgresServerVersion.startsWith(PostgresVersion.VERSION_7_1+'.')
                    || !postgresServerVersion.startsWith(PostgresVersion.VERSION_7_2+'.')
                ;
                boolean postgresServerHasVacuumFull=
                    !postgresServerVersion.startsWith(PostgresVersion.VERSION_7_1+'.')
                ;
                for(PostgresDatabase postgresDatabase : postgresServer.getPostgresDatabases()) {
                    if(
                        !postgresDatabase.isTemplate()
                        && postgresDatabase.allowsConnections()
                    ) {
                        AOConnectionPool pool;
                        Connection conn;
                        if(postgresDatabase.getName().equals(PostgresDatabase.AOSERV)) {
                            // If the aoserv database, use the existing connection pools
                            pool=PostgresServerManager.getPool(postgresServer);
                            conn=pool.getConnection();
                        } else {
                            // For other databases, establish a connection directly
                            pool=null;
                            Class.forName(postgresDatabase.getJdbcDriver()).newInstance();
                            conn=DriverManager.getConnection(
                                postgresDatabase.getJdbcUrl(true),
                                PostgresUser.POSTGRES,
                                AOServDaemonConfiguration.getPostgresPassword()
                            );
                        }
                        try {
                            conn.setAutoCommit(true);
							try (Statement stmt = conn.createStatement()) {
								try (
									ResultSet results = stmt.executeQuery(
										postgresServerHasSchemas
										? ("select tablename, schemaname from pg_tables where tableowner!='"+PostgresUser.POSTGRES+"'")
										: ("select tablename from pg_tables where tableowner!='"+PostgresUser.POSTGRES+"'")
									)
								) {
									tableNames.clear();
									if(postgresServerHasSchemas) schemas.clear();
									while(results.next()) {
										tableNames.add(results.getString(1));
										if(postgresServerHasSchemas) schemas.add(results.getString(2));
									}
								}
								for(int c=0;c<tableNames.size();c++) {
									String tableName=tableNames.get(c);
									String schema=postgresServerHasSchemas ? schemas.get(c) : null;
									if(
										postgresDatabaseTable.isValidDatabaseName(tableName.toLowerCase())
									) {
										if(
											!postgresServerHasSchemas
											|| "public".equals(schema)
											|| (schema!=null && postgresDatabaseTable.isValidDatabaseName(schema.toLowerCase()))
										) {
											// VACUUM the table
											stmt.executeUpdate(
												postgresServerHasVacuumFull
												? (
													postgresServerHasSchemas
													? ("vacuum full analyze "+schema+"."+tableName)
													: ("vacuum full analyze "+tableName)
												) : (
													postgresServerHasSchemas
													? ("vacuum analyze "+schema+"."+tableName)
													: ("vacuum analyze "+tableName)
												)
											);
											if(isReindexTime) {
												// REINDEX the table
												stmt.executeUpdate(
													postgresServerHasSchemas
													? ("reindex table "+schema+"."+tableName)
													: ("reindex table "+tableName)
												);
											}
										} else {
											LogFactory.getLogger(PostgresDatabaseManager.class).log(Level.WARNING, "schema="+schema, new SQLWarning("Warning: not calling VACUUM or REINDEX because schema name does not pass the database name checks.  This is to make sure specially-crafted schema names cannot be used to execute arbitrary SQL with administrative privileges."));
										}
									} else {
										LogFactory.getLogger(PostgresDatabaseManager.class).log(Level.WARNING, "tableName="+tableName, new SQLWarning("Warning: not calling VACUUM or REINDEX because table name does not pass the database name checks.  This is to make sure specially-crafted table names cannot be used to execute arbitrary SQL with administrative privileges."));
									}
								}
							}
                        } finally {
                            if(pool!=null) pool.releaseConnection(conn);
                            else conn.close();
                        }
                    }
                }
            }
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(PostgresDatabaseManager.class).log(Level.SEVERE, null, T);
        }
    }
}
