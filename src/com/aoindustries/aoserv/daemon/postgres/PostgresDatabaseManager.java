package com.aoindustries.aoserv.daemon.postgres;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.client.*;
import com.aoindustries.aoserv.daemon.server.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.md5.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import com.aoindustries.util.zip.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresDatabaseManager extends BuilderThread implements CronJob {

    private PostgresDatabaseManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresDatabaseManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public static void backupDatabase(CompressedDataInputStream masterIn, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, PostgresDatabaseManager.class, "backupDatabase(CompressedDataInputStream,CompressedDataOutputStream)", null);
        try {
            String minorVersion=masterIn.readUTF();
            String dbName=masterIn.readUTF();
            int port=masterIn.readCompressedInt();

            // Dump, count raw bytes, create MD5, and compress to a temp file
            int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            String commandPath;
            if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                commandPath = "/opt/aoserv-daemon/bin/backup_postgres_database";
            } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                commandPath = "/usr/aoserv/daemon/bin/backup_postgres_database";
            } else {
                throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            }
            String[] command={
                commandPath,
                minorVersion,
                Integer.toString(port),
                dbName
            };
            Process P=Runtime.getRuntime().exec(command);
            BufferedReader dumpIn=new BufferedReader(new InputStreamReader(P.getInputStream()));
            long dataSize=Long.parseLong(dumpIn.readLine());
            List<UnixFile> unixFiles=new ArrayList<UnixFile>();
            try {
                String line;
                while((line=dumpIn.readLine())!=null) {
                    UnixFile uf=new UnixFile(line);
                    uf.getFile().deleteOnExit();
                    unixFiles.add(uf);
                }
                dumpIn.close();
                try {
                    int retCode=P.waitFor();
                    if(retCode!=0) throw new IOException("backup_postgres_database exited with non-zero return code: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }

                // Convert to an array of files and calculate the compressedSize
                long compressedSize = 0;
                File[] files=new File[unixFiles.size()];
                for(int c=0;c<files.length;c++) {
                    File file = files[c] = unixFiles.get(c).getFile();
                    long len = file.length();
                    if(len < 0) throw new IOException("Unable to get length for file: " + file.getPath());
                    compressedSize += len;
                }

                // Build the MD5 hash
                MD5InputStream md5In=new MD5InputStream(new CorrectedGZIPInputStream(new BufferedInputStream(new MultiFileInputStream(files))));
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
                        masterOut.writeLong(compressedSize);
                        masterOut.writeLong(md5_hi);
                        masterOut.writeLong(md5_lo);
                        masterOut.flush();

                        boolean sendData=masterIn.readBoolean();
                        if(sendData) {
                            long daemonKey=masterIn.readLong();
                            int toAOServer=masterIn.readCompressedInt();
                            String toIpAddress=masterIn.readUTF();
                            int toPort=masterIn.readCompressedInt();
                            String toProtocol=masterIn.readUTF();
                            int toPoolSize=masterIn.readCompressedInt();
                            AOServDaemonConnector daemonConnector=AOServDaemonConnector.getConnector(
                                toAOServer,
                                toIpAddress,
                                AOServDaemon.getThisAOServer().getDaemonBind().getIPAddress().getIPAddress(),
                                toPort,
                                toProtocol,
                                null,
                                toPoolSize,
                                AOPool.DEFAULT_MAX_CONNECTION_AGE,
                                SSLConnector.class,
                                SSLConnector.sslProviderLoaded,
                                AOServClientConfiguration.getSslTruststorePath(),
                                AOServClientConfiguration.getSslTruststorePassword(),
                                AOServDaemon.getErrorHandler()
                            );
                            AOServDaemonConnection daemonConn=daemonConnector.getConnection();
                            try {
                                // Start the replication
                                CompressedDataOutputStream out=daemonConn.getOutputStream();
                                out.writeCompressedInt(AOServDaemonProtocol.BACKUP_POSTGRES_DATABASE_SEND_DATA);
                                out.writeLong(daemonKey);
                                out.flush();

                                CompressedDataInputStream in=daemonConn.getInputStream();
                                InputStream tmpIn=new MultiFileInputStream(files);
                                int ret;
                                while((ret=tmpIn.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                                    out.write(AOServDaemonProtocol.NEXT);
                                    out.writeShort(ret);
                                    out.write(buff, 0, ret);
                                }
                                out.write(AOServDaemonProtocol.DONE);
                                out.flush();
                                int result=in.read();
                                if(result!=AOServDaemonProtocol.DONE) {
                                    if (result == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
                                    else if (result == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
                                    else throw new IOException("Unknown result: " + result);
                                }
                            } catch(IOException err) {
                                daemonConn.close();
                                throw err;
                            } catch(SQLException err) {
                                daemonConn.close();
                                throw err;
                            } finally {
                                daemonConnector.releaseConnection(daemonConn);
                            }
                            masterOut.write(AOServDaemonProtocol.DONE);
                            masterOut.flush();
                        }
                    } finally {
                        BufferManager.release(buff);
                    }
                } finally {
                    md5In.close();
                }
            } finally {
                for(UnixFile unixFile : unixFiles) unixFile.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresDatabaseManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

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
                        List<String> existing=new SortedArrayList<String>();
                        Statement stmt=conn.createStatement();
                        try {
                            ResultSet results=stmt.executeQuery("select datname from pg_database");
                            try {
                                while(results.next()) existing.add(results.getString(1));
                            } finally {
                                results.close();
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
                                            throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                                        }
                                        AOServDaemon.suexec(LinuxAccount.POSTGRES, createlang+" -p "+port+" plpgsql "+name);
                                        if(
                                            version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                            || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                            || version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_0+'.')
                                            || version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                            // Not supported as of 8.3 - it has built-in full text indexing
                                        ) {
                                            AOServDaemon.suexec(LinuxAccount.POSTGRES, psql+" -p "+port+" -c \"create function fti() returns opaque as '"+lib+"/mfti.so' language 'c';\" "+name);
                                        }
                                        if(database.getEnablePostgis()) {
                                            AOServDaemon.suexec(LinuxAccount.POSTGRES, psql+" -p "+port+" "+name+" -f "+share+"/lwpostgis.sql");
                                            AOServDaemon.suexec(LinuxAccount.POSTGRES, psql+" -p "+port+" "+name+" -f "+share+"/spatial_ref_sys.sql");
                                        }
                                    } catch(IOException err) {
                                        try {
                                            // Drop the new database if not fully configured
                                            stmt.executeUpdate("drop database "+name);
                                            conn.commit();
                                        } catch(SQLException err2) {
                                            AOServDaemon.reportError(err2, null);
                                            throw err2;
                                        }
                                        throw err;
                                    } catch(SQLException err) {
                                        try {
                                            // Drop the new database if not fully configured
                                            stmt.executeUpdate("drop database "+name);
                                            conn.commit();
                                        } catch(SQLException err2) {
                                            AOServDaemon.reportError(err2, null);
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
                                    || dbName.equals(PostgresDatabase.AOSERV_BACKUP)
                                    || dbName.equals(PostgresDatabase.AOINDUSTRIES)
                                    || dbName.equals(PostgresDatabase.AOWEB)
                                ) throw new SQLException("AOServ Daemon will not automatically drop database, please drop manually: "+dbName);
                                stmt.executeUpdate("drop database "+dbName);
                                conn.commit();
                            }
                        } finally {
                            stmt.close();
                        }
                    } finally {
                        pool.releaseConnection(conn);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void dumpDatabase(PostgresDatabase pd, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresDatabaseManager.class, "dumpDatabase(PostgresDatabase,CompressedDataOutputStream)", null);
        try {
            UnixFile tempFile=UnixFile.mktemp("/tmp/dump_postgres_database.sql.");
            tempFile.getFile().deleteOnExit();
            try {
                AOServConnector conn=AOServDaemon.getConnector();
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
                    throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                }
                String[] command={
                    commandPath,
                    minorVersion,
                    Integer.toString(port),
                    dbName,
                    tempFile.getFilename()
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
			BufferManager.release(buff);
		    }
		} finally {
		    dumpin.close();
		}
            } finally {
                if(tempFile.getStat().exists()) tempFile.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static PostgresDatabaseManager postgresDatabaseManager;
    private static boolean cronStarted = false;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresDatabaseManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(PostgresDatabaseManager.class) && postgresDatabaseManager==null) {
                synchronized(System.out) {
                    if(postgresDatabaseManager==null || !cronStarted) {
                        System.out.print("Starting PostgresDatabaseManager: ");
                        if(postgresDatabaseManager==null) {
                            AOServConnector conn=AOServDaemon.getConnector();
                            postgresDatabaseManager=new PostgresDatabaseManager();
                            conn.postgresDatabases.addTableListener(postgresDatabaseManager, 0);
                        }
                        if(!cronStarted) {
                            CronDaemon.addCronJob(postgresDatabaseManager, AOServDaemon.getErrorHandler());
                            cronStarted=true;
                        }
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresDatabaseManager.class, "waitForRebuild()", null);
        try {
            if(postgresDatabaseManager!=null) postgresDatabaseManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, PostgresDatabaseManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild PostgreSQL Databases";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * Runs once a month for automatic vacuuming and reindexing of all user tables, at 1:05 every Sunday.
     * REINDEX is only called on the first Sunday of the month.
     */
    public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        return
            minute==5
            && hour==1
            && dayOfWeek==Calendar.SUNDAY
        ;
    }

    public int getCronJobScheduleMode() {
        return CRON_JOB_SCHEDULE_SKIP;
    }

    public String getCronJobName() {
        return "PostgresDatabaseManager";
    }

    /**
     * Since the VACUUM FULL and REINDEX commands use exclusive locks on each table, we want it to finish as soon as possible.
     */
    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY+2;
    }

    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresDatabaseManager.class, "runCronJob(int,int,int,int,int,int)", null);
        try {
            try {
                AOServConnector aoservConn = AOServDaemon.getConnector();
                PostgresDatabaseTable postgresDatabaseTable = aoservConn.postgresDatabases;
                // Only REINDEX on the first Sunday of the month
                boolean isReindexTime=dayOfMonth<=7;
                List<String> tableNames=new ArrayList<String>();
                List<String> schemas=new ArrayList<String>();
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
                                Statement stmt=conn.createStatement();
                                ResultSet results=stmt.executeQuery(
                                    postgresServerHasSchemas
                                    ? ("select tablename, schemaname from pg_tables where tableowner!='"+PostgresUser.POSTGRES+"'")
                                    : ("select tablename from pg_tables where tableowner!='"+PostgresUser.POSTGRES+"'")
                                );
                                tableNames.clear();
                                if(postgresServerHasSchemas) schemas.clear();
                                while(results.next()) {
                                    tableNames.add(results.getString(1));
                                    if(postgresServerHasSchemas) schemas.add(results.getString(2));
                                }
                                results.close();
                                for(int c=0;c<tableNames.size();c++) {
                                    String tableName=tableNames.get(c);
                                    String schema=postgresServerHasSchemas ? schemas.get(c) : null;
                                    if(
                                        postgresDatabaseTable.isValidDatabaseName(tableName.toLowerCase())
                                    ) {
                                        if(!postgresServerHasSchemas || postgresDatabaseTable.isValidDatabaseName(schema.toLowerCase())) {
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
                                            AOServDaemon.reportWarning(
                                                new SQLWarning("Warning: not calling VACUUM or REINDEX because schema name does not pass the database name checks.  This is to make sure specially-crafted schema names cannot be used to execute arbitrary SQL with administrative privileges."),
                                                new Object[] {"schema="+schema}
                                            );
                                        }
                                    } else {
                                        AOServDaemon.reportWarning(
                                            new SQLWarning("Warning: not calling VACUUM or REINDEX because table name does not pass the database name checks.  This is to make sure specially-crafted table names cannot be used to execute arbitrary SQL with administrative privileges."),
                                            new Object[] {"tableName="+tableName}
                                        );
                                    }
                                }
                                stmt.close();
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
                AOServDaemon.reportError(T, null);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
