package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2006-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
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
    protected boolean doRebuild() {
        //AOServConnector connector=AOServDaemon.getConnector();

        synchronized(rebuildLock) {
            // TODO: Add and initialize any missing /var/lib/mysql/name
            // TODO: Add/update any /etc/rc.d/init.d/mysql-name
            // TODO: restart any that need started/restarted
        }
        return true;
    }

    private static final Map<MySQLServer,AOConnectionPool> pools=new HashMap<MySQLServer,AOConnectionPool>();
    static AOConnectionPool getPool(MySQLServer ms) throws IOException, SQLException {
        synchronized(pools) {
            AOConnectionPool pool=pools.get(ms);
            if(pool==null) {
                MySQLDatabase md=ms.getMysqlDatabase(MySQLDatabase.MYSQL);
                pool=new AOConnectionPool(
                    AOServDaemonConfiguration.getMysqlDriver(),
                    "jdbc:mysql://127.0.0.1:"+md.getMysqlServer().getNetBind().getPort().getPort()+"/"+md.getName(),
                    AOServDaemonConfiguration.getMysqlUser().getId(),
                    AOServDaemonConfiguration.getMysqlPassword(),
                    AOServDaemonConfiguration.getMySqlConnections(),
                    AOServDaemonConfiguration.getMySqlMaxConnectionAge(),
                    LogFactory.getLogger(MySQLServerManager.class)
                );
                pools.put(ms, pool);
            }
            return pool;
        }
    }

    private static MySQLServerManager mysqlServerManager;
    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(MySQLServerManager.class)
                && mysqlServerManager==null
            ) {
                System.out.print("Starting MySQLServerManager: ");
                AOServConnector<?,?> conn=AOServDaemon.getConnector();
                mysqlServerManager=new MySQLServerManager();
                conn.getMysqlServers().getTable().addTableListener(mysqlServerManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(mysqlServerManager!=null) mysqlServerManager.waitForBuild();
    }

    public String getProcessTimerDescription() {
        return "Rebuild MySQL Servers";
    }

    public static void restartMySQL(MySQLServer ms) throws IOException, SQLException {
        ServerManager.controlProcess("mysql-"+ms.getName(), "restart");
    }

    public static void startMySQL(MySQLServer ms) throws IOException, SQLException {
        ServerManager.controlProcess("mysql-"+ms.getName(), "start");
    }

    public static void stopMySQL(MySQLServer ms) throws IOException, SQLException {
        ServerManager.controlProcess("mysql-"+ms.getName(), "stop");
    }

    private static final Object flushLock=new Object();
    static void flushPrivileges(MySQLServer mysqlServer) throws IOException, SQLException {
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();

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
                osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) {
                path="/opt/mysql-"+mysqlServer.getMinorVersion()+"-i686/bin/mysqladmin";
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
                "--password="+AOServDaemonConfiguration.getMysqlPassword(),
                "reload"
            };
            AOServDaemon.exec(cmd);
        }
    }
}
