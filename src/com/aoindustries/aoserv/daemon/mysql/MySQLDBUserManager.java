package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2002-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.List;

/**
 * Controls the MySQL DB Users.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLDBUserManager extends BuilderThread {

    private MySQLDBUserManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLDBUserManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLDBUserManager.class, "doRebuild()", null);
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
                for(MySQLServer mysqlServer : connector.mysqlServers) {
                    String version=mysqlServer.getVersion().getVersion();
                    boolean modified=false;

                    // Get the connection to work through
                    AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
                    Connection conn = pool.getConnection();
                    try {
                        // Get the list of all existing db entries
                        List<String> existing = new SortedArrayList<String>();
                        Statement stmt = conn.createStatement();
                        try {
                            ResultSet results = stmt.executeQuery("select db, user from db");
                            try {
                                while (results.next()) existing.add(results.getString(1) + '|' + results.getString(2));
                            } finally {
                                results.close();
                            }
                        } finally {
                            stmt.close();
                        }

                        // Get the list of all db entries that should exist
                        List<MySQLDBUser> dbUsers = mysqlServer.getMySQLDBUsers();

                        // Add the db entries that do not exist and should
                        String insertSQL;
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                            || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        ) {
                            if(version.startsWith("4.0.")) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                            else if(version.startsWith("4.1.")) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                            else if(version.startsWith("5.0.")) insertSQL="insert into db values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                            else throw new SQLException("Unsupported MySQL version: "+version);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        PreparedStatement pstmt = conn.prepareStatement(insertSQL);
                        try {
                            for(MySQLDBUser mdu : dbUsers) {
                                MySQLDatabase md = mdu.getMySQLDatabase();
                                String db=md.getName();
                                MySQLServerUser msu=mdu.getMySQLServerUser();
                                String user=msu.getMySQLUser().getUsername().getUsername();

                                // These must both be on the same server !!!
                                if(!md.getMySQLServer().equals(msu.getMySQLServer())) throw new SQLException(
                                    "Server mismatch in mysql_db_users.pkey="
                                    +mdu.getPkey()
                                    +": ((mysql_databases.pkey="
                                    +md.getPkey()
                                    +").mysql_server="
                                    +md.getMySQLServer().getPkey()
                                    +")!=((mysql_server_users.pkey="
                                    +msu.getPkey()
                                    +").mysql_server="
                                    +msu.getMySQLServer().getPkey()
                                    +')'
                                );

                                String key=db+'|'+user;
                                if (existing.contains(key)) existing.remove(key);
                                else {
                                    // Add the db entry
                                    String host=MySQLServerUser.ANY_HOST;
                                    pstmt.setString(1, host);
                                    pstmt.setString(2, db);
                                    pstmt.setString(3, user);
                                    pstmt.setString(4, mdu.canSelect()?"Y":"N");
                                    pstmt.setString(5, mdu.canInsert()?"Y":"N");
                                    pstmt.setString(6, mdu.canUpdate()?"Y":"N");
                                    pstmt.setString(7, mdu.canDelete()?"Y":"N");
                                    pstmt.setString(8, mdu.canCreate()?"Y":"N");
                                    pstmt.setString(9, mdu.canDrop()?"Y":"N");
                                    pstmt.setString(10, mdu.canGrant()?"Y":"N");
                                    pstmt.setString(11, mdu.canReference()?"Y":"N");
                                    pstmt.setString(12, mdu.canIndex()?"Y":"N");
                                    pstmt.setString(13, mdu.canAlter()?"Y":"N");
                                    pstmt.setString(14, mdu.canCreateTempTable()?"Y":"N");
                                    pstmt.setString(15, mdu.canLockTables()?"Y":"N");
                                    if(version.startsWith("5.0.")) {
                                        pstmt.setString(16, mdu.canCreateView()?"Y":"N");
                                        pstmt.setString(17, mdu.canShowView()?"Y":"N");
                                        pstmt.setString(18, mdu.canCreateRoutine()?"Y":"N");
                                        pstmt.setString(19, mdu.canAlterRoutine()?"Y":"N");
                                        pstmt.setString(20, mdu.canExecute()?"Y":"N");
                                    }
                                    pstmt.executeUpdate();

                                    modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Remove the extra db entries
                        if (!existing.isEmpty()) {
                            pstmt = conn.prepareStatement("delete from db where db=? and user=?");
                            try {
                                for (int c = 0; c < existing.size(); c++) {
                                    // Remove the extra db entry
                                    String key=existing.get(c);
                                    int pos=key.indexOf('|');
                                    pstmt.setString(1, key.substring(0, pos));
                                    pstmt.setString(2, key.substring(pos+1));
                                    pstmt.executeUpdate();

                                    modified = true;
                                }
                            } finally {
                                pstmt.close();
                            }
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

    private static MySQLDBUserManager mysqlDBUserManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLDBUserManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(MySQLDBUserManager.class) && mysqlDBUserManager==null) {
                synchronized(System.out) {
                    if(mysqlDBUserManager==null) {
                        System.out.print("Starting MySQLDBUserManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        mysqlDBUserManager=new MySQLDBUserManager();
                        conn.mysqlDBUsers.addTableListener(mysqlDBUserManager, 0);
                        conn.mysqlDatabases.addTableListener(mysqlDBUserManager, 0);
                        conn.mysqlServerUsers.addTableListener(mysqlDBUserManager, 0);
                        conn.mysqlUsers.addTableListener(mysqlDBUserManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLDBUserManager.class, "waitForRebuild()", null);
        try {
            if(mysqlDBUserManager!=null) mysqlDBUserManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLDBUserManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild MySQL DB Users";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}