/*
 * Copyright 2001-2012 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net.ssh;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Handles the building of SSHD configs and files.
 */
final public class SshdManager extends BuilderThread {

    private static SshdManager sshdManager;

    private SshdManager() {
    }

    /**
     * Called by NetDeviceManager.doRebuild to ensure consistent state with the IP addresses.
     */
    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

            // Nothing is done for these operating systems
            if(
                osv==OperatingSystemVersion.CENTOS_5DOM0_I686
                || osv==OperatingSystemVersion.CENTOS_5DOM0_X86_64
            ) {
                throw new SQLException("Should not have been started");
            }

            // Otherwise, make sure it is a supported OS
            if(
                osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) {
                throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
            }

            synchronized(rebuildLock) {
                // Find all the IPs that should be bound to
                SortedSet<String> ips;
                {
                    Protocol sshProtocol=connector.getProtocols().get(Protocol.SSH);
                    List<NetBind> nbs=thisAOServer.getServer().getNetBinds(sshProtocol);
                    ips=new TreeSet<String>();
                    for(int c=0;c<nbs.size();c++) {
                        NetBind nb = nbs.get(c);
                        if(nb.getNetTcpRedirect()==null) {
                            int port = nb.getPort().getPort();
                            if(port!=22) throw new IOException("SSH only supported on port 22 at this time");
                            IPAddress ip = nb.getIPAddress();
                            if(ip==null) throw new NullPointerException("nbs["+c+"].getIPAddress() is null");
                            NetDevice nd=ip.getNetDevice();
                            if(nd==null) throw new NullPointerException("nbs["+c+"].getIPAddress().getNetDevice() is null");
                            if(nd.getNetDeviceID().isLoopback()) throw new IOException("Can't use localhost for SSH for chroot failover support: nbs["+c+"].getIPAddress().getNetDevice().getNetDeviceId().isLoopback()==true");
                            if(ip.isWildcard()) throw new IOException("Can't use wildcard for SSH for chroot failover support: ip.isWildcard()==true");
                            String address=ip.getIPAddress();
                            if(!ips.contains(address)) ips.add(address);
                        }
                    }
                }
                if(ips.isEmpty()) throw new IOException("No IP addresses found for SSH, refusing to update sshd_config");

                // Build the new config file to RAM
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ChainWriter out = new ChainWriter(bout);
                try {
                    if(
                        osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                    ) {
                        out.print("#\n"
                                + "# This configuration file is automatically generated by\n"
                                + "# ").print(SshdManager.class.getName()).print("\n"
                                + "#\n"
                                + "Port 22\n"
                                + "Protocol 2\n");
                                // Changed to not allow Protocol 1 on 2005-02-01 by Dan Armstrong
                                //+ "Protocol 2,1\n");
                        for(String ip : ips) {
                            out.print("ListenAddress ").print(ip).print("\n");
                        }
                        out.print("SyslogFacility AUTHPRIV\n"
                                + "PermitRootLogin yes\n"
                                + "PasswordAuthentication yes\n"
                                + "ChallengeResponseAuthentication no\n"
                                + "GSSAPIAuthentication yes\n"
                                + "GSSAPICleanupCredentials yes\n"
                                + "UsePAM yes\n"
                                + "AcceptEnv LANG LC_CTYPE LC_NUMERIC LC_TIME LC_COLLATE LC_MONETARY LC_MESSAGES\n"
                                + "AcceptEnv LC_PAPER LC_NAME LC_ADDRESS LC_TELEPHONE LC_MEASUREMENT\n"
                                + "AcceptEnv LC_IDENTIFICATION LC_ALL\n"
                                + "X11Forwarding yes\n"
                                + "UsePrivilegeSeparation yes\n"
                                + "Subsystem sftp /usr/libexec/openssh/sftp-server\n"
                        );
                    } else {
                        throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                    }
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Write the new file and restart process only when update needed
                UnixFile configFile = new UnixFile("/etc/ssh/sshd_config");
                if(
                    !configFile.getStat().exists()
                    || !configFile.contentEquals(newBytes)
                ) {
                    // Write to temp file
                    UnixFile newConfigFile = new UnixFile("/etc/ssh/sshd_config.new");
                    OutputStream newConfigOut = newConfigFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    try {
                        newConfigOut.write(newBytes);
                    } finally {
                        newConfigOut.close();
                    }

                    // Atomically move into place
                    newConfigFile.renameTo(configFile);

                    // Try reload config first
                    try {
                        AOServDaemon.exec(
                            new String[] {
                                "/etc/rc.d/init.d/sshd",
                                "reload"
                            }
                        );
                    } catch(IOException err) {
                        LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err);

                        // Try more forceful stop/start
                        try {
                            AOServDaemon.exec(
                                new String[] {
                                    "/etc/rc.d/init.d/sshd",
                                    "stop"
                                }
                            );
                        } catch(IOException err2) {
                            LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err2);
                        }
                        try {
                            Thread.sleep(1000);
                        } catch(InterruptedException err2) {
                            LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err2);
                        }
                        AOServDaemon.exec(
                            new String[] {
                                "/etc/rc.d/init.d/sshd",
                                "start"
                            }
                        );
                    }
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(SshdManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(SshdManager.class)
                && sshdManager==null
            ) {
                System.out.print("Starting SshdManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                sshdManager=new SshdManager();
                conn.getNetBinds().addTableListener(sshdManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild SSH Configuration";
    }
}
