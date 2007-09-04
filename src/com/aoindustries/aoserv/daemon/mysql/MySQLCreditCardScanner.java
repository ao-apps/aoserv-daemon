package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.lang.StringBuilder;

/**
 * Controls the MySQL databases.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLCreditCardScanner implements CronJob {

    private MySQLCreditCardScanner() {
    }

    /*
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLDatabaseManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
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

    public static void backupDatabase(CompressedDataInputStream masterIn, CompressedDataOutputStream masterOut) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MySQLDatabaseManager.class, "backupDatabase(CompressedDataInputStream,CompressedDataOutputStream)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
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
                Process P=Runtime.getRuntime().exec(command);
                long dataSize;
                BufferedReader dumpIn=new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    dataSize=Long.parseLong(dumpIn.readLine());
                } finally {
                    dumpIn.close();
                }
                try {
                    int retCode=P.waitFor();
                    if(retCode!=0) throw new IOException("backup_mysql_database exited with non-zero return code: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }
            } finally {
                if(tempFile.getStat().exists()) tempFile.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
*/

    private static MySQLCreditCardScanner mySQLCreditCardScanner;

    public static void start() throws IOException, SQLException {
        if(AOServDaemonConfiguration.isManagerEnabled(MySQLCreditCardScanner.class) && mySQLCreditCardScanner==null) {
            synchronized(System.out) {
                if(mySQLCreditCardScanner==null) {
                    System.out.print("Starting MySQLCreditCardScanner: ");
                    mySQLCreditCardScanner=new MySQLCreditCardScanner();
                    CronDaemon.addCronJob(mySQLCreditCardScanner, AOServDaemon.getErrorHandler());
                    System.out.println("Done");
                }
            }
        }
    }

    public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        return
            minute==30
            && hour==2
            && dayOfWeek==Calendar.SUNDAY
        ;
    }
    
     public int getCronJobScheduleMode() {
         return CRON_JOB_SCHEDULE_SKIP;
     }

    public String getCronJobName() {
        return "MySQLCreditCardScanner";
    }
    
    /**
     * Performs the scheduled task.
     */
    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        scanMySQLForCards();
    }
    
    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY-2;
    }
    
    public static void main(String[] args) {
// XXX       for(int i : new int[] {1, 2, 3, 4}) {
// XXX           System.out.println(i);
// XXX       }
        scanMySQLForCards();
    }

    private static void scanMySQLForCards() {
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            List<MySQLServer> mysqlServers = thisAOServer.getMySQLServers();
            for(MySQLServer mysqlServer : mysqlServers) {
                System.out.print("mysqlServer=");
                //System.out.println(mysqlServer==null ? "null" : mysqlServer.toString());
                System.out.println(mysqlServer);
                List<MySQLDatabase> mysqlDatabases = mysqlServer.getMySQLDatabases();
                for(MySQLDatabase database : mysqlDatabases) {
                    String name=database.getName();
                    System.out.print("    database=");
                    System.out.println(database);
                    
                    // Get connection to the database
                    Class.forName(AOServDaemonConfiguration.getMySqlDriver()).newInstance();
                    Connection conn = DriverManager.getConnection(
                        "jdbc:mysql://"+thisAOServer.getPrimaryIPAddress().getIPAddress()+":"+database.getMySQLServer().getNetBind().getPort().getPort()+"/"+database.getName(),
                        AOServDaemonConfiguration.getMySqlUser(),
                        AOServDaemonConfiguration.getMySqlPassword()
                    );
                    try {
                        scanForCards(conn, name);
                    } finally {
                        
                        conn.close();
                    }
                }
            }
        } catch(ClassNotFoundException err) {
            AOServDaemon.reportError(err, null);
        } catch(InstantiationException err) {
            AOServDaemon.reportError(err, null);
        } catch(IllegalAccessException err) {
            AOServDaemon.reportError(err, null);
        } catch(IOException err) {
            AOServDaemon.reportError(err, null);
        } catch(SQLException err) {
            AOServDaemon.reportError(err, null);
        }
    }
    
    public static void scanForCards(Connection conn, String catalog) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        String[] tableTypes = new String[] {"TABLE"};
        ResultSet tables = metaData.getTables(catalog, null, null, tableTypes);
        try {
            while(tables.next()) {
                String table = tables.getString(3);
                StringBuilder buffer = new StringBuilder();
                ResultSet columns = metaData.getColumns(catalog, null, table, null);
                
          
                long ccCount = 0;
                try {
                    boolean isFirst = true;
                    while(columns.next()) {
                        String column = columns.getString(4);
                        if(isFirst) isFirst = false;
                        else buffer.append(" OR ");
                        //System.out.println("                        columnName:" +  column);
                        // ####-####-####-####
                        buffer.append("(length(").append(column).append(")<30 AND ").append(column).append(" regexp '^\\w*[0-9]{4}[\\w-]*[0-9]{4}[\\w-]*[0-9]{4}[\\w-]*[0-9]{4}\\w*$')");
                        // TODO: Add other patterns
                    }
                    //System.out.println("select count(*) from " + table + " where " + buffer.toString());
                    Statement stmnt = conn.createStatement();
                    ResultSet cardnumbers = stmnt.executeQuery("select count(*) from " + table + " where " + buffer.toString());
                     try {
                        if(!cardnumbers.next()) throw new SQLException("No results returned!");
                        ccCount = cardnumbers.getLong(1);
                    } finally {
                        cardnumbers.close();
                    }
                } finally {
                    columns.close();
                }
                // Find total number of rows
                long rowCount;
                Statement stmt = conn.createStatement();
                try {
                    ResultSet results = stmt.executeQuery("select count(*) from "+table);
                    try {
                        if(!results.next()) throw new SQLException("No results returned!");
                        rowCount = results.getLong(1);
                    } finally {
                        results.close();
                    }
                    //int matches = select count(*) from tablename where column1 like ... or column2 like ...
                    //if(matches*10>total) alert.
                } finally {
                    stmt.close();
                }
                // TODO : log ccCount and count to the database
                if(ccCount>0) {
                    System.out.println("          tableName:" +  table);
                    System.out.println("                        rowCount:" +  rowCount);
                    System.out.println("                        rows with ccNumbers: " + ccCount);
                }
            }
        } finally {
            tables.close();
        }
        // TODO: Scan for both PostgreSQL and MySQL here
        // for loop through each table
        // Some select statement to find credit card patterns
        // If more X% of rows match the credit card pattern, notify admin and customer
        // credit card pattern will be on specific types, and such
        // try to query each table only once, where (column1 like pattern or column2 like pattern or ...)
        // TODO: end for loop
    }
}
