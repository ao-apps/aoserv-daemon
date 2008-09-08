package com.aoindustries.aoserv.daemon.net;

import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Watches the IP address of the server and tells the master when the IP address changes.
 */
final public class DhcpManager implements Runnable {

    public static final int POLL_INTERVAL=5*60*1000;

    private static final String GET_DHCP_ADDRESS="/usr/aoserv/daemon/bin/get_dhcp_address";

    private static Thread thread;

    private DhcpManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DhcpManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public static String getDhcpAddress(String device) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, DhcpManager.class, "getDhcpAddress(String)", null);
        try {
            String[] cmd={
                GET_DHCP_ADDRESS,
                device
            };
	    String ip;
            Process P=Runtime.getRuntime().exec(cmd);
            try {
                P.getOutputStream().close();
                BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    ip=in.readLine();
                } finally {
                    in.close();
                }
            } finally {
                AOServDaemon.waitFor(cmd, P);
            }
            if(ip==null || (ip=ip.trim()).length()==0) throw new IOException("Unable to find IP address for device: "+device);
            return ip;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, DhcpManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(DhcpManager.class) && thread==null) {
                synchronized(System.out) {
                    if(thread==null) {
                        // Only start if at least one IP Address on the server is DHCP-enabled
                        boolean found=false;
                        for(IPAddress ia : AOServDaemon.getThisAOServer().getServer().getIPAddresses()) {
                            if(ia.isDHCP()) {
                                found=true;
                                break;
                            }
                        }
                        if(found) {
                            System.out.print("Starting DhcpManager: ");
                            thread=new Thread(new DhcpManager());
                            thread.setDaemon(true);
                            thread.setName("DhcpManager");
                            thread.start();
                            System.out.println("Done");
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, DhcpManager.class, "run()", null);
        try {
            while(true) {
                try {
                    while(true) {
                        try {
                            Thread.sleep(POLL_INTERVAL);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                        for(NetDevice nd : AOServDaemon.getThisAOServer().getServer().getNetDevices()) {
                            IPAddress primaryIP=nd.getPrimaryIPAddress();
                            if(primaryIP.isDHCP()) {
                                String dhcpAddress=getDhcpAddress(nd.getNetDeviceID().getName());
                                if(!primaryIP.getIPAddress().equals(dhcpAddress)) {
                                    primaryIP.setDHCPAddress(dhcpAddress);
                                }
                            }
                        }
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(POLL_INTERVAL);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
