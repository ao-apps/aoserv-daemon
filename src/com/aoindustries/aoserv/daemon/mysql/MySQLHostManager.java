package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2002-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.Hostname;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.sql.AOConnectionPool;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the MySQL Hosts.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLHostManager extends BuilderThread {

    private MySQLHostManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServConnector<?,?> connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized (rebuildLock) {
                for(MySQLServer mysqlServer : connector.getMysqlServers().getSet()) {
                    String version=mysqlServer.getVersion().getVersion();
                    boolean modified = false;

                    // Get the connection to work through
                    AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
                    Connection conn = pool.getConnection();
                    try {
                        // Get the list of all existing hosts
                        Set<Hostname> existing = new HashSet<Hostname>();
                        Statement stmt = conn.createStatement();
                        try {
                            ResultSet results = stmt.executeQuery("select host from host");
                            try {
                                while (results.next()) existing.add(Hostname.valueOf(results.getString(1)));
                            } finally {
                                results.close();
                            }
                        } finally {
                            stmt.close();
                        }

                        // Get the list of all hosts that should exist
                        Set<Hostname> hosts=new HashSet<Hostname>();
                        // Always include loopback, just in case of data errors
                        hosts.add(Hostname.valueOf("127.0.0.1"));
                        hosts.add(Hostname.valueOf("localhost"));
                        hosts.add(Hostname.valueOf("localhost.localdomain"));
                        // Include all of the local IP addresses
                        for(NetDevice nd : thisAOServer.getServer().getNetDevices()) {
                            for(IPAddress ia : nd.getIpAddresses()) hosts.add(Hostname.valueOf(ia.getIpAddress()));
                        }

                        // Add the hosts that do not exist and should
                        String insertSQL;
                        if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX))      insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
                        else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
                        else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
                        else if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
                        else throw new SQLException("Unsupported MySQL version: "+version);

                        PreparedStatement pstmt = conn.prepareStatement(insertSQL);
                        try {
                            for (Hostname hostname : hosts) {
                                if(!existing.remove(hostname)) {
                                    // Add the host
                                    pstmt.setString(1, hostname.toString());
                                    pstmt.executeUpdate();

                                    modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Remove the extra hosts
                        if (!existing.isEmpty()) {
                            pstmt = conn.prepareStatement("delete from host where host=?");
                            try {
                                for(Hostname dbName : existing) {
                                    // Remove the extra host entry
                                    pstmt.setString(1, dbName.toString());
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
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(MySQLHostManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    private static MySQLHostManager mysqlHostManager;
    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(MySQLHostManager.class)
                && mysqlHostManager==null
            ) {
                System.out.print("Starting MySQLHostManager: ");
                AOServConnector<?,?> conn=AOServDaemon.getConnector();
                mysqlHostManager=new MySQLHostManager();
                conn.getIpAddresses().getTable().addTableListener(mysqlHostManager, 0);
                conn.getMysqlServers().getTable().addTableListener(mysqlHostManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild MySQL Hosts";
    }
}