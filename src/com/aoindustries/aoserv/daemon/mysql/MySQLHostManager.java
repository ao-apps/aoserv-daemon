package com.aoindustries.aoserv.daemon.mysql;

/*
 * Copyright 2002-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
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
 * Controls the MySQL Hosts.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLHostManager extends BuilderThread {

    private MySQLHostManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLHostManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLHostManager.class, "doRebuild()", null);
        try {
            AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized (rebuildLock) {
                for(MySQLServer mysqlServer : connector.mysqlServers) {
                    String version=mysqlServer.getVersion().getVersion();
                    boolean modified = false;

                    // Get the connection to work through
                    AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
                    Connection conn = pool.getConnection();
                    try {
                        // Get the list of all existing hosts
                        List<String> existing = new SortedArrayList<String>();
                        Statement stmt = conn.createStatement();
                        try {
                            ResultSet results = stmt.executeQuery("select host from host");
                            try {
                                while (results.next()) existing.add(results.getString(1));
                            } finally {
                                results.close();
                            }
                        } finally {
                            stmt.close();
                        }

                        // Get the list of all hosts that should exist
                        List<String> hosts=new SortedArrayList<String>();
                        // Always include loopback, just in case of data errors
                        hosts.add(IPAddress.LOOPBACK_IP);
                        hosts.add("localhost");
                        hosts.add("localhost.localdomain");
                        // Include all of the local IP addresses
                        for(NetDevice nd : thisAOServer.getNetDevices()) {
                            for(IPAddress ia : nd.getIPAddresses()) {
                                if(!ia.isWildcard()) {
                                    String ip=ia.getIPAddress();
                                    if(!hosts.contains(ip)) hosts.add(ip);
                                }
                            }
                        }

                        // Add the hosts that do not exist and should
                        String insertSQL;
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                            || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        ) {
                            if(version.startsWith("4.1.")) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
                            else if(version.startsWith("5.0.")) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
                            else throw new SQLException("Unsupported MySQL version: "+version);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                        PreparedStatement pstmt = conn.prepareStatement(insertSQL);
                        try {
                            for (int c = 0; c < hosts.size(); c++) {
                                String hostname = hosts.get(c);
                                if (existing.contains(hostname)) existing.remove(hostname);
                                else {
                                    // Add the host
                                    pstmt.setString(1, hostname);
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
                                for (int c = 0; c < existing.size(); c++) {
                                    // Remove the extra host entry
                                    pstmt.setString(1, existing.get(c));
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

    private static MySQLHostManager mysqlHostManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MySQLHostManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(MySQLHostManager.class) && mysqlHostManager==null) {
                synchronized(System.out) {
                    if(mysqlHostManager==null) {
                        System.out.print("Starting MySQLHostManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        mysqlHostManager=new MySQLHostManager();
                        conn.ipAddresses.addTableListener(mysqlHostManager, 0);
                        conn.mysqlServers.addTableListener(mysqlHostManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MySQLHostManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild MySQL Hosts";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}