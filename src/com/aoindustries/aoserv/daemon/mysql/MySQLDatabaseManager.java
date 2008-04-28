package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2002-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.server.*;
import com.aoindustries.aoserv.daemon.util.*;
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
import java.util.List;
import java.util.Properties;

/**
 * Controls the MySQL databases.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLDatabaseManager extends BuilderThread {

    private MySQLDatabaseManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLDatabaseManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLDatabaseManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                for(MySQLServer mysqlServer : thisAOServer.getMySQLServers()) {
                    String version=mysqlServer.getVersion().getVersion();
                    boolean modified=false;
                    // Get the connection to work through
                    AOConnectionPool pool=MySQLServerManager.getPool(mysqlServer);
                    Connection conn=pool.getConnection(false);
                    try {
                        // Get the list of all existing databases
                        List<String> existing=new SortedArrayList<String>();
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
                            for(int c=0;c<existing.size();c++) {
                                String dbName=existing.get(c);
                                if(
                                    dbName.equals(MySQLDatabase.MYSQL)
                                    || (version.startsWith("5.0.") && dbName.equals(MySQLDatabase.INFORMATION_SCHEMA))
                                ) {
                                    AOServDaemon.reportWarning(new SQLException("Refusing to drop critical MySQL Database: "+dbName+" on "+mysqlServer), null);
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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static MySQLDatabaseManager mysqlDatabaseManager;
    
    public static void backupDatabase(CompressedDataInputStream masterIn, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MySQLDatabaseManager.class, "backupDatabase(CompressedDataInputStream,CompressedDataOutputStream)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            int pkey=masterIn.readCompressedInt();
            MySQLDatabase md=AOServDaemon.getConnector().mysqlDatabases.get(pkey);
            if(md==null) throw new SQLException("Unable to find MySQLDatabase: "+pkey);
            String dbName=md.getName();
            MySQLServer ms=md.getMySQLServer();
            UnixFile tempFile=UnixFile.mktemp("/tmp/backup_mysql_database.sql.gz.");
            tempFile.getFile().deleteOnExit();
            try {
                // Dump, count raw bytes, create MD5, and compress to a temp file
                String path;
                if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    path="/usr/aoserv/daemon/bin/backup_mysql_database";
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    path="/opt/aoserv-daemon/bin/backup_mysql_database";
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                String[] command={
                    path,
                    dbName,
                    ms.getMinorVersion(),
                    Integer.toString(ms.getNetBind().getPort().getPort()),
                    tempFile.getFilename()
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

                MD5InputStream md5In=new MD5InputStream(new CorrectedGZIPInputStream(new BufferedInputStream(new FileInputStream(tempFile.getFilename()))));
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
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void dumpDatabase(MySQLDatabase md, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLDatabaseManager.class, "dumpDatabase(MySQLDatabase,CompressedDataOutputStream)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            UnixFile tempFile=UnixFile.mktemp("/tmp/dump_mysql_database.sql.");
            tempFile.getFile().deleteOnExit();
            try {
                String dbName=md.getName();
                MySQLServer ms=md.getMySQLServer();
                String path;
                if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    path="/usr/aoserv/daemon/bin/dump_mysql_database";
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    path="/opt/aoserv-daemon/bin/dump_mysql_database";
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                String[] command={
                    path,
                    dbName,
                    ms.getMinorVersion(),
                    Integer.toString(ms.getNetBind().getPort().getPort()),
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

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLDatabaseManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(MySQLDatabaseManager.class) && mysqlDatabaseManager==null) {
                synchronized(System.out) {
                    if(mysqlDatabaseManager==null) {
                        System.out.print("Starting MySQLDatabaseManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        mysqlDatabaseManager=new MySQLDatabaseManager();
                        conn.mysqlDatabases.addTableListener(mysqlDatabaseManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLDatabaseManager.class, "waitForRebuild()", null);
        try {
            if(mysqlDatabaseManager!=null) mysqlDatabaseManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLDatabaseManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild MySQL Databases";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public static void getMasterStatus(int mysqlServer, CompressedDataOutputStream out) throws IOException, SQLException {
        // Use the existing pools
        MySQLServer ms = AOServDaemon.getConnector().mysqlServers.get(mysqlServer);
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

    public static void getSlaveStatus(String failoverRoot, int nestedOperatingSystemVersion, int port, CompressedDataOutputStream out) throws IOException, SQLException {
        // Load the properties from the failover image
        File file;
        if(nestedOperatingSystemVersion==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            file = new File(failoverRoot+"/etc/aoserv/daemon/com/aoindustries/aoserv/daemon/aoserv-daemon.properties");
        } else if(nestedOperatingSystemVersion==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
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
        Connection conn;
        try {
            conn = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:"+port+"/mysql",
                user,
                password
            );
        } catch(SQLException err) {
            AOServDaemon.reportError(err, null);
            throw new SQLException("Unable to connect to slave.");
        }
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
}
