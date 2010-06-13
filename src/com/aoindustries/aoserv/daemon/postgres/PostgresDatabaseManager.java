package com.aoindustries.aoserv.daemon.postgres;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresDatabase;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresUser;
import com.aoindustries.aoserv.client.PostgresVersion;
import com.aoindustries.aoserv.client.validator.PostgresDatabaseName;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.util.BufferManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresDatabaseManager extends BuilderThread implements CronJob {

    private PostgresDatabaseManager() {
    }

    public boolean isValidIdentifier(String identifier) {
        try {
            PostgresDatabaseName.validate(identifier.toLowerCase(Locale.ENGLISH));
            return true;
        } catch(ValidationException err) {
            return false;
        }
    }

    /*
    public static void backupDatabase(CompressedDataInputStream masterIn, CompressedDataOutputStream masterOut) throws IOException, SQLException {
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
 * try.. finally on P
 * P.getOutputStream().close();
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
                            AOServClientConfiguration.getSslTruststorePath(),
                            AOServClientConfiguration.getSslTruststorePassword(),
                            TODO
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
    }
*/
    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                for(PostgresServer ps : thisAOServer.getPostgresServers()) {
                    int port=ps.getNetBind().getPort().getPort();
                    PostgresVersion pv=ps.getPostgresVersion();
                    String version=pv.getTechnologyVersion().getVersion();
                    String minorVersion=pv.getMinorVersion();

                    // Get the connection to work through
                    AOConnectionPool pool=PostgresServerManager.getPool(ps);
                    Connection conn=pool.getConnection(false);
                    try {
                        // Get the list of all existing databases
                        Set<PostgresDatabaseName> existing=new HashSet<PostgresDatabaseName>();
                        Statement stmt=conn.createStatement();
                        try {
                            ResultSet results=stmt.executeQuery("select datname from pg_database");
                            try {
                                while(results.next()) existing.add(PostgresDatabaseName.valueOf(results.getString(1)));
                            } finally {
                                results.close();
                            }

                            // Create the databases that do not exist and should
                            for(PostgresDatabase database : ps.getPostgresDatabases()) {
                                PostgresDatabaseName name=database.getName();
                                if(!existing.remove(name)) {
                                    PostgresUser datdba=database.getDatDBA();
                                    if(
                                        version.startsWith(PostgresVersion.VERSION_7_3+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_0+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_1+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_3+'.')
                                        || version.startsWith(PostgresVersion.VERSION_8_3+'R')
                                    ) {
                                        stmt.executeUpdate("create database "+name+" with owner="+datdba.getUsername().getUsername()+" encoding='"+database.getPostgresEncoding().getEncoding()+'\'');
                                        conn.commit();
                                    } else if(
                                        version.startsWith(PostgresVersion.VERSION_7_1+'.')
                                        || version.startsWith(PostgresVersion.VERSION_7_2+'.')
                                    ) {
                                        // Create the database
                                        stmt.executeUpdate("create database "+name+" with encoding='"+database.getPostgresEncoding().getEncoding()+"'");
                                        stmt.executeUpdate("update pg_database set datdba=(select usesysid from pg_user where usename='"+datdba.getUsername().getUsername()+"') where datname='"+name+"'");
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
                                        } else {
                                            throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
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
                                    } catch(SQLException err) {
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
                            for(PostgresDatabaseName dbName : existing) {
                                // Remove the extra database
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
                        } finally {
                            stmt.close();
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

            int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            String commandPath;
            if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                commandPath = "/opt/aoserv-daemon/bin/dump_postgres_database";
            } else {
                throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            }
            String[] command={
                commandPath,
                ps.getPostgresVersion().getMinorVersion(),
                ps.getNetBind().getPort().toString(),
                pd.getName().getName(),
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
                    BufferManager.release(buff);
                }
            } finally {
                dumpin.close();
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
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(PostgresDatabaseManager.class)
                && (postgresDatabaseManager==null || !cronStarted)
            ) {
                System.out.print("Starting PostgresDatabaseManager: ");
                if(postgresDatabaseManager==null) {
                    AOServConnector<?,?> conn=AOServDaemon.getConnector();
                    postgresDatabaseManager=new PostgresDatabaseManager();
                    conn.getPostgresDatabases().getTable().addTableListener(postgresDatabaseManager, 0);
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

    public String getProcessTimerDescription() {
        return "Rebuild PostgreSQL Databases";
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
        try {
            // Only REINDEX on the first Sunday of the month
            boolean isReindexTime=dayOfMonth<=7;
            List<String> tableNames=new ArrayList<String>();
            List<String> schemas=new ArrayList<String>();
            for(PostgresServer postgresServer : AOServDaemon.getThisAOServer().getPostgresServers()) {
                String postgresServerVersion=postgresServer.getPostgresVersion().getTechnologyVersion().getVersion();
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
                        && postgresDatabase.getAllowsConnections()
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
                                PostgresUser.POSTGRES.getId(),
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
                                if(isValidIdentifier(tableName)) {
                                    if(!postgresServerHasSchemas || "public".equals(schema) || isValidIdentifier(schema)) {
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
            LogFactory.getLogger(PostgresDatabaseManager.class).log(Level.SEVERE, null, T);
        }
    }
}
