package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.sql.AOConnectionPool;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Controls the MySQL servers.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLServerManager extends BuilderThread {

    public static final File mysqlDirectory=new File(MySQLServer.DATA_BASE_DIR);

    private MySQLServerManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, MySQLServerManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();

            synchronized(rebuildLock) {
                // TODO: Add and initialize any missing /var/lib/mysql/name
                // TODO: Add/update any /etc/rc.d/init.d/mysql-name
                // TODO: restart any that need started/restarted
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final Map<Integer,AOConnectionPool> pools=new HashMap<Integer,AOConnectionPool>();
    static AOConnectionPool getPool(MySQLServer ms) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLServerManager.class, "getPool(MySQLServer)", null);
        try {
	    synchronized(pools) {
                Integer I=Integer.valueOf(ms.getPKey());
                AOConnectionPool pool=pools.get(I);
		if(pool==null) {
                    MySQLDatabase md=ms.getMySQLDatabase(MySQLDatabase.MYSQL);
                    if(md==null) throw new SQLException("Unable to find MySQLDatabase: "+MySQLDatabase.MYSQL+" on "+ms.toString());
		    pool=new AOConnectionPool(
                        AOServDaemonConfiguration.getMySqlDriver(),
                        "jdbc:mysql://127.0.0.1:"+md.getMySQLServer().getNetBind().getPort().getPort()+"/"+md.getName(),
                        AOServDaemonConfiguration.getMySqlUser(),
                        AOServDaemonConfiguration.getMySqlPassword(),
                        AOServDaemonConfiguration.getMySqlConnections(),
                        AOServDaemonConfiguration.getMySqlMaxConnectionAge(),
                        AOServDaemon.getErrorHandler()
                    );
                    pools.put(I, pool);
		}
		return pool;
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static MySQLServerManager mysqlServerManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLServerManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(MySQLServerManager.class) && mysqlServerManager==null) {
                synchronized(System.out) {
                    if(mysqlServerManager==null) {
                        System.out.print("Starting MySQLServerManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        mysqlServerManager=new MySQLServerManager();
                        conn.mysqlServers.addTableListener(mysqlServerManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        if(mysqlServerManager!=null) mysqlServerManager.waitForBuild();
    }

    public String getProcessTimerDescription() {
        return "Rebuild MySQL Servers";
    }

    public static void restartMySQL(MySQLServer ms) throws IOException, SQLException {
        ServerManager.controlProcess("mysql-"+ms.getMinorVersion(), "restart");
    }

    public static void startMySQL(MySQLServer ms) throws IOException, SQLException {
        ServerManager.controlProcess("mysql-"+ms.getMinorVersion(), "start");
    }

    public static void stopMySQL(MySQLServer ms) throws IOException, SQLException {
        ServerManager.controlProcess("mysql-"+ms.getMinorVersion(), "stop");
    }

    private static final Object flushLock=new Object();
    static void flushPrivileges(MySQLServer mysqlServer) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLServerManager.class, "flushPrivileges(MySQLServer)", null);
        try {
            int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();

            synchronized(flushLock) {
                /*
                This did not work properly, so we now invoke a native process instead.

                synchronized(flushLock) {
                    Connection conn=getPool().getConnection();
                    try {
                        Statement stmt=conn.createStatement();
                        try {
                            stmt.executeUpdate("flush privileges");
                        } finally {
                            stmt.close();
                        }
                    } finally {
                        getPool().releaseConnection(conn);
                    }
                }
                */
                String path;
                if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    path="/usr/mysql/"+mysqlServer.getMinorVersion()+"/bin/mysqladmin";
                } else if(
                    osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                ) {
                    path="/opt/mysql-"+mysqlServer.getMinorVersion()+"/bin/mysqladmin";
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                String[] cmd={
                    path,
                    "-h",
                    "127.0.0.1",
                    "-P",
                    Integer.toString(mysqlServer.getNetBind().getPort().getPort()),
                    "-u",
                    "root",
                    "--password="+AOServDaemonConfiguration.getMySqlPassword(),
                    "reload"
                };
                AOServDaemon.exec(cmd);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
