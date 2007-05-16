package com.aoindustries.aoserv.daemon.monitor;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class NetStatMonitor implements Runnable {
    
    private static final String[] netstat={"/bin/netstat", "-ln"};
    
    /**
     * The minimum delay between scans.
     */
    public static final int MIN_DELAY=5*60*1000;
    
    /**
     * The minimum delay after an error occurs.
     */
    public static final int MIN_ERROR_DELAY=15*60*1000;
    
    /**
     * The maximum delay between scans.
     */
    public static final int MAX_DELAY=15*60*1000;
    
    private static Thread thread;
    
    private NetStatMonitor() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, NetStatMonitor.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }
    
    private static int getRandomDelay() {
        Profiler.startProfile(Profiler.UNKNOWN, NetStatMonitor.class, "getRandomDelay()", null);
        try {
            return MIN_DELAY+AOServDaemon.getRandom().nextInt(MAX_DELAY-MIN_DELAY);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, NetStatMonitor.class, "run()", null);
        try {
            while(true) {
                try {
                    while(true) {
                        try {
                            Thread.sleep(getRandomDelay());
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                        
                        // Get the current list of binds for this server
                        List<NetBind> nbs=AOServDaemon.getThisAOServer().getNetBinds();
                        
                        // Build a ArrayList that will be trimmed as matches are found
                        List<NetBind> dbBinds=new ArrayList<NetBind>(nbs.size());
                        for(int c=0;c<nbs.size();c++) dbBinds.add(nbs.get(c));
                        
                        // Keep track of net binds that are returned by netstat but not in dbBinds
                        List<NetStat> extraBinds=new ArrayList<NetStat>();
                        
                        // Parse through each net bind returned by netstat
                        Process P=Runtime.getRuntime().exec(netstat);
                        BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
			try {
			    List<NetStat> allNetStats=new SortedArrayList<NetStat>();
			    String line;
			    while((line=in.readLine())!=null) {
				NetStat netStat=NetStat.parseNetStat(line);
				if(netStat!=null) {
                                    // Skip port 1 raw
				    if(netStat.port!=1 || !netStat.net_protocol.equals(NetProtocol.RAW)) {
					// Must be unique to continue
					if(!allNetStats.contains(netStat)) {
					    allNetStats.add(netStat);
					    // Find one or more matches and remove, otherwise add to extra bind info
					    boolean found=false;
					    for(int c=0;c<dbBinds.size();c++) {
						NetBind nb=dbBinds.get(c);
						if(
						   nb.getIPAddress().getIPAddress().equals(netStat.ip_address)
						   && nb.getNetProtocol().getProtocol().equals(netStat.net_protocol)
						   && nb.getPort().getPort()==netStat.port
						   ) {
						    dbBinds.remove(c);
						    c--;
						    found=true;
						}
					    }
					    if(!found) extraBinds.add(netStat);
					}
				    }
				}
			    }
			} finally {
			    in.close();
			}
                        int retCode=P.waitFor();
                        if(retCode!=0) throw new IOException("Non-zero return code from /bin/netstat: "+retCode);
                        
                        // Report and discrepancy
                        int missing=0;
                        for(int c=0;c<dbBinds.size();c++) {
                            NetBind dbBind=dbBinds.get(c);
                            if(dbBind.isMonitoringEnabled()) missing++;
                        }
                        int extra=extraBinds.size();
                        if(missing!=0 || extra!=0) {
                            StringBuilder SB=new StringBuilder();
                            SB.append("Discrepancies found in netstat -ln:");
                            if(missing!=0) SB.append(" missing ").append(missing).append(missing==1?" net bind":" net binds");
                            if(extra!=0) {
                                if(missing!=0) SB.append(" and");
                                SB.append(" found ").append(extra).append(" extra net ").append(extra==1?"bind":"binds");
                            }
                            SB.append('\n');
                            for(int c=0;c<dbBinds.size();c++) {
                                NetBind nb=dbBinds.get(c);
                                if(nb.isMonitoringEnabled()) {
                                    SB.append("Missing: ").append(nb.getNetProtocol()).append(' ').append(nb.getIPAddress().getIPAddress()).append(':').append(nb.getPort()).append('\n');
                                }
                            }
                            for(int c=0;c<extra;c++) {
                                NetStat ns=extraBinds.get(c);
                                SB.append("Extra: ").append(ns.net_protocol).append(' ').append(ns.ip_address).append(':').append(ns.port).append('\n');
                            }
                            AOServDaemon.reportFullMonitoringMessage(SB.toString());
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(Math.max(MIN_ERROR_DELAY, getRandomDelay()));
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, NetStatMonitor.class, "start()", null);
        try {
            // Avoid netstat monitoring when nested or in failover mode
            if(AOServDaemonConfiguration.isManagerEnabled(NetStatMonitor.class) && !AOServDaemonConfiguration.isNested() && AOServDaemon.getThisAOServer().getFailoverServer()==null) {
                if(thread==null) {
                    synchronized(System.out) {
                        if(thread==null) {
                            System.out.print("Starting NetStatMonitor: ");
                            (thread=new Thread(new NetStatMonitor())).start();
                            System.out.println("Done");
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
