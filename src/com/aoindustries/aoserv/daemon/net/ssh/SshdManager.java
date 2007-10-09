package com.aoindustries.aoserv.daemon.net.ssh;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Handles the building of SSHD configs and files.
 */
final public class SshdManager extends BuilderThread {

    public static final int IPS_PER_SERVER=16;

    private static SshdManager sshdManager;

    private SshdManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SshdManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    /**
     * Called by NetDeviceManager.doRebuild to ensure consistent state with the IP addresses.
     */
    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SshdManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            final Stat tempStat = new Stat();

            synchronized(rebuildLock) {
                // Figure out how many sshd daemons exist
                int beforeCount=0;
                while(new File("/etc/rc.d/init.d/sshd"+(beforeCount+1)).exists()) beforeCount++;

                // Find all the IPs that should be bound to
                String[] ips;
                {
                    Protocol sshProtocol=connector.protocols.get(Protocol.SSH);
                    List<NetBind> nbs=thisAOServer.getNetBinds(sshProtocol);
                    List<String> uniqueIPs=new SortedArrayList<String>();
                    for(int c=0;c<nbs.size();c++) {
                        if(nbs.get(c).getNetTcpRedirect()==null) {
                            IPAddress ip=nbs.get(c).getIPAddress();
                            if(ip==null) throw new NullPointerException("nbs["+c+"].getIPAddress() is null");
                            NetDevice nd=ip.getNetDevice();
                            if(
                                nd!=null
                                && !nd.getNetDeviceID().isLoopback()
                                && !ip.isWildcard()
                            ) {
                                String address=ip.getIPAddress();
                                if(!uniqueIPs.contains(address)) uniqueIPs.add(address);
                            }
                        }
                    }
                    ips=new String[uniqueIPs.size()];
                    uniqueIPs.toArray(ips);
                }

                // Determine the new number of servers
                int numServers=ips.length/IPS_PER_SERVER;
                if((ips.length%IPS_PER_SERVER)>0) numServers++;

                int ipPos=0;
                for(int c=1;c<=numServers;c++) {
                    // Create the new config file
                    UnixFile unixFile = new UnixFile("/etc/ssh/sshd_config"+c+".new");
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            unixFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, false)
                        )
                    );
		    try {
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        ) {
                            out.print("#       $OpenBSD: sshd_config,v 1.59 2002/09/25 11:17:16 markus Exp $\n"
                                    + "\n"
                                    + "# This is the sshd server system-wide configuration file.  See\n"
                                    + "# sshd_config(5) for more information.\n"
                                    + "\n"
                                    + "# This sshd was compiled with PATH=/usr/local/bin:/bin:/usr/bin:/usr/X11R6/bin\n"
                                    + "\n"
                                    + "# The strategy used for options in the default sshd_config shipped with\n"
                                    + "# OpenSSH is to specify options with their default value where\n"
                                    + "# possible, but leave them commented.  Uncommented options change a\n"
                                    + "# default value.\n"
                                    + "\n"
                                    + "# This configuration file is automatically generated by\n"
                                    + "# "+SshdManager.class.getName()+"\n"
                                    + "\n"
                                    + "Port 22\n"
                                    + "Protocol 2\n");
                                    // Changed to now allow Protocol 1 on 2005-02-01 by Dan Armstrong
                                    //+ "Protocol 2,1\n");
                            for(int d=0;ipPos<ips.length && d<IPS_PER_SERVER;d++) {
                                out.print("ListenAddress ").print(ips[ipPos++]).print("\n");
                            }
                            out.print("\n"
                                    + "# HostKey for protocol version 1\n"
                                    + "HostKey /etc/ssh/ssh_host_key\n"
                                    + "# HostKeys for protocol version 2\n"
                                    + "HostKey /etc/ssh/ssh_host_rsa_key\n"
                                    + "HostKey /etc/ssh/ssh_host_dsa_key\n"
                                    + "\n"
                                    + "# Lifetime and size of ephemeral version 1 server key\n"
                                    + "#KeyRegenerationInterval 3600\n"
                                    + "#ServerKeyBits 768\n"
                                    + "\n"
                                    + "# Logging\n"
                                    + "#obsoletes QuietMode and FascistLogging\n"
                                    + "#SyslogFacility AUTH\n"
                                    + "#LogLevel INFO\n"
                                    + "\n"
                                    + "# Authentication:\n"
                                    + "\n"
                                    + "#LoginGraceTime 120\n"
                                    + "PermitRootLogin yes\n"
                                    + "#StrictModes yes\n"
                                    + "\n"
                                    + "#RSAAuthentication yes\n"
                                    + "#PubkeyAuthentication yes\n"
                                    + "#AuthorizedKeysFile     .ssh/authorized_keys\n"
                                    + "\n"
                                    + "# rhosts authentication should not be used\n"
                                    + "#RhostsAuthentication no\n"
                                    + "# Don't read the user's ~/.rhosts and ~/.shosts files\n"
                                    + "#IgnoreRhosts yes\n"
                                    + "# For this to work you will also need host keys in /etc/ssh/ssh_known_hosts\n"
                                    + "#RhostsRSAAuthentication no\n"
                                    + "# similar for protocol version 2\n"
                                    + "#HostbasedAuthentication no\n"
                                    + "# Change to yes if you don't trust ~/.ssh/known_hosts for\n"
                                    + "# RhostsRSAAuthentication and HostbasedAuthentication\n"
                                    + "#IgnoreUserKnownHosts no\n"
                                    + "\n"
                                    + "# To disable tunneled clear text passwords, change to no here!\n"
                                    + "#PasswordAuthentication yes\n"
                                    + "#PermitEmptyPasswords no\n"
                                    + "\n"
                                    + "# Change to no to disable s/key passwords\n"
                                    + "#ChallengeResponseAuthentication yes\n"
                                    + "\n"
                                    + "# Kerberos options\n"
                                    + "#KerberosAuthentication no\n"
                                    + "#KerberosOrLocalPasswd yes\n"
                                    + "#KerberosTicketCleanup yes\n"
                                    + "\n"
                                    + "#AFSTokenPassing no\n"
                                    + "\n"
                                    + "# Kerberos TGT Passing only works with the AFS kaserver\n"
                                    + "#KerberosTgtPassing no\n"
                                    + "\n"
                                    + "# Set this to 'yes' to enable PAM keyboard-interactive authentication\n"
                                    + "# Warning: enabling this may bypass the setting of 'PasswordAuthentication'\n"
                                    + "#PAMAuthenticationViaKbdInt no\n"
                                    + "\n"
                                    + "X11Forwarding yes\n"
                                    + "#X11DisplayOffset 10\n"
                                    + "#X11UseLocalhost yes\n"
                                    + "#PrintMotd yes\n"
                                    + "#PrintLastLog yes\n"
                                    + "#KeepAlive yes\n"
                                    + "#UseLogin no\n"
                                    + "UsePrivilegeSeparation yes\n"
                                    + "#PermitUserEnvironment no\n"
                                    + "#Compression yes\n"
                                    + "\n"
                                    + "#MaxStartups 10\n"
                                    + "# no default banner path\n"
                                    + "#Banner /some/path\n"
                                    + "#VerifyReverseMapping no\n"
                                    + "\n"
                                    + "# override default of no subsystems\n"
                                    + "Subsystem       sftp    /usr/lib/ssh/sftp-server\n"
                            );

                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
		    } finally {
			out.flush();
			out.close();
		    }
                }

                // Move the new config files into place
                IntList needsRestarted=new SortedIntArrayList();
                for(int c=1;c<=numServers;c++) {
                    UnixFile configFile=new UnixFile("/etc/ssh/sshd_config"+c);
                    UnixFile newFile=new UnixFile("/etc/ssh/sshd_config"+c+".new");
                    if(!configFile.getStat(tempStat).exists() || !configFile.contentEquals(newFile)) {
                        needsRestarted.add(c);
                        newFile.renameTo(configFile);
                    } else newFile.delete();
                }

                // Shutdown all old daemons or those whos configs have changed
                for(int c=1;c<=beforeCount;c++) {
                    if(c>numServers || needsRestarted.contains(c)) {
                        try {
                            AOServDaemon.exec(
                                new String[] {
                                    "/etc/rc.d/init.d/sshd"+c,
                                    "stop"
                                }
                            );
                        } catch(IOException err) {
                            AOServDaemon.reportError(err, null);
                        }
                    }
                }

                // Remove old daemons that should no longer exist
                for(int c=(numServers+1);c<=beforeCount;c++) {
                    try {
                        AOServDaemon.exec(
                            new String[] {
                                "/sbin/chkconfig",
                                "--del",
                                "sshd"+c
                            }
                        );
                        new File("/etc/rc.d/init.d/sshd"+c).delete();
                        new File("/etc/ssh/sshd_config"+c).delete();
                    } catch(IOException err) {
                        AOServDaemon.reportError(err, null);
                    }
                }

                // Create the new scripts for new daemons
                // TODO: use reload on sshd when possible
                for(int c=(beforeCount+1);c<=numServers;c++) {
                    needsRestarted.add(c);
                    try {
                        ChainWriter out=new ChainWriter(
                            new BufferedOutputStream(
                                new UnixFile("/etc/rc.d/init.d/sshd"+c).getSecureOutputStream(
                                    UnixFile.ROOT_UID,
                                    UnixFile.ROOT_GID,
                                    0700,
                                    false
                                )
                            )
                        );
			try {
                            if(
                                osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            ) {
                                out.print("#!/bin/bash\n"
                                        + "#\n"
                                        + "# Init file for OpenSSH server daemon\n"
                                        + "#\n"
                                        + "# chkconfig: 2345 55 25\n"
                                        + "# description: OpenSSH server daemon ").print(c).print("\n"
                                        + "#\n"
                                        + "# processname: sshd\n"
                                        + "# config: /etc/ssh/ssh_host_key\n"
                                        + "# config: /etc/ssh/ssh_host_key.pub\n"
                                        + "# config: /etc/ssh/ssh_random_seed\n"
                                        + "# config: /etc/ssh/sshd_config").print(c).print("\n"
                                        + "# pidfile: /var/run/sshd").print(c).print(".pid\n"
                                        + "\n"
                                        + "# source function library\n"
                                        + ". /etc/rc.d/init.d/functions\n"
                                        + "\n"
                                        + "# pull in sysconfig settings\n"
                                        + "[ -f /etc/sysconfig/sshd ] && . /etc/sysconfig/sshd\n"
                                        + "\n"
                                        + "RETVAL=0\n"
                                        + "prog=\"sshd").print(c).print("\"\n"
                                        + "\n"
                                        + "# Some functions to make the below more readable\n"
                                        + "KEYGEN=/usr/bin/ssh-keygen\n"
                                        + "SSHD=/usr/sbin/sshd\n"
                                        + "RSA1_KEY=/etc/ssh/ssh_host_key\n"
                                        + "RSA_KEY=/etc/ssh/ssh_host_rsa_key\n"
                                        + "DSA_KEY=/etc/ssh/ssh_host_dsa_key\n"
                                        + "PID_FILE=/var/run/sshd").print(c).print(".pid\n"
                                        + "\n"
                                        + "do_rsa1_keygen() {\n"
                                        + "\tif [ ! -s $RSA1_KEY ]; then\n"
                                        + "\t\tgprintf \"Generating SSH1 RSA host key: \"\n"
                                        + "\t\tif $KEYGEN -q -t rsa1 -f $RSA1_KEY -C '' -N '' >&/dev/null; then\n"
                                        + "\t\t\tchmod 600 $RSA1_KEY\n"
                                        + "\t\t\tchmod 644 $RSA1_KEY.pub\n"
                                        + "\t\t\tsuccess \"RSA1 key generation\"\n"
                                        + "\t\t\techo\n"
                                        + "\t\telse\n"
                                        + "\t\t\tfailure \"RSA1 key generation\"\n"
                                        + "\t\t\techo\n"
                                        + "\t\t\texit 1\n"
                                        + "\t\tfi\n"
                                        + "\tfi\n"
                                        + "}\n"
                                        + "\n"
                                        + "do_rsa_keygen() {\n"
                                        + "\tif [ ! -s $RSA_KEY ]; then\n"
                                        + "\t\tgprintf \"Generating SSH2 RSA host key: \"\n"
                                        + "\t\tif $KEYGEN -q -t rsa -f $RSA_KEY -C '' -N '' >&/dev/null; then\n"
                                        + "\t\t\tchmod 600 $RSA_KEY\n"
                                        + "\t\t\tchmod 644 $RSA_KEY.pub\n"
                                        + "\t\t\tsuccess \"RSA key generation\"\n"
                                        + "\t\t\techo\n"
                                        + "\t\telse\n"
                                        + "\t\t\tfailure \"RSA key generation\"\n"
                                        + "\t\t\techo\n"
                                        + "\t\t\texit 1\n"
                                        + "\t\tfi\n"
                                        + "\tfi\n"
                                        + "}\n"
                                        + "\n"
                                        + "do_dsa_keygen() {\n"
                                        + "\tif [ ! -s $DSA_KEY ]; then\n"
                                        + "\t\tgprintf \"Generating SSH2 DSA host key: \"\n"
                                        + "\t\tif $KEYGEN -q -t dsa -f $DSA_KEY -C '' -N '' >&/dev/null; then\n"
                                        + "\t\t\tchmod 600 $DSA_KEY\n"
                                        + "\t\t\tchmod 644 $DSA_KEY.pub\n"
                                        + "\t\t\tsuccess \"DSA key generation\"\n"
                                        + "\t\t\techo\n"
                                        + "\t\telse\n"
                                        + "\t\t\tfailure \"DSA key generation\"\n"
                                        + "\t\t\techo\n"
                                        + "\t\t\texit 1\n"
                                        + "\t\tfi\n"
                                        + "\tfi\n"
                                        + "}\n"
                                        + "\n"
                                        + "do_restart_sanity_check()\n"
                                        + "{\n"
                                        + "\t$SSHD -f /etc/ssh/sshd_config").print(c).print(" -t\n"
                                        + "\tRETVAL=$?\n"
                                        + "\tif [ ! \"$RETVAL\" = 0 ]; then\n"
                                        + "\t\tfailure \"Configuration file or keys are invalid\"\n"
                                        + "\t\techo\n"
                                        + "\tfi\n"
                                        + "}\n"
                                        + "\n"
                                        + "start()\n"
                                        + "{\n"
                                        + "\t# Create keys if necessary\n"
                                        + "\tdo_rsa1_keygen\n"
                                        + "\tdo_rsa_keygen\n"
                                        + "\tdo_dsa_keygen\n"
                                        + "\n"
                                        + "\tgprintf \"Starting %s:\" \"$prog\"\n"
                                        + "\tinitlog -c \"$SSHD -f /etc/ssh/sshd_config").print(c).print(" $OPTIONS\" && success \"startup\" || failure \"startup\"\n"
                                        + "\tRETVAL=$?\n"
                                        + "\t[ \"$RETVAL\" = 0 ] && touch /var/lock/subsys/sshd").print(c).print("\n"
                                        + "\techo\n"
                                        + "}\n"
                                        + "\n"
                                        + "stop()\n"
                                        + "{\n"
                                        + "\tgprintf \"Stopping %s:\" \"$prog\"\n"
                                        + "\tif [ -r /var/run/sshd").print(c).print(".pid ]; then\n"
                                        + "\t\tkill -TERM `cat /var/run/sshd").print(c).print(".pid`\n"
                                        + "\t\tRETVAL=$?\n"
                                        + "\t\t[ \"$RETVAL\" = 0 ] && success \"stop\" || failure \"stop\"\n"
                                        + "\telse\n"
                                        + "\t\tsuccess \"already stopped\"\n"
                                        + "\t\tRETVAL=0\n"
                                        + "\tfi\n"
                                        + "\t[ \"$RETVAL\" = 0 ] && rm -f /var/lock/subsys/sshd").print(c).print("\n"
                                        + "\techo\n"
                                        + "}\n"
                                        + "\n"
                                        + "reload()\n"
                                        + "{\n"
                                        + "\tgprintf \"Reloading %s:\" \"$prog\"\n"
                                        + "\tif [ -r /var/run/sshd").print(c).print(".pid ]; then\n"
                                        + "\t\tkill -HUP `cat /var/run/sshd").print(c).print(".pid`\n"
                                        + "\t\tRETVAL=$?\n"
                                        + "\t\t[ \"$RETVAL\" = 0 ] && success \"config reload\" || failure \"config reload\"\n"
                                        + "\telse\n"
                                        + "\t\tfailure \"not running so config reload\"\n"
                                        + "\t\tRETVAL=1\n"
                                        + "\tfi\n"
                                        + "\techo\n"
                                        + "}\n"
                                        + "\n"
                                        + "case \"$1\" in\n"
                                        + "\tstart)\n"
                                        + "\t\tstart\n"
                                        + "\t\t;;\n"
                                        + "\tstop)\n"
                                        + "\t\tstop\n"
                                        + "\t\t;;\n"
                                        + "\trestart)\n"
                                        + "\t\tstop\n"
                                        + "\t\tstart\n"
                                        + "\t\t;;\n"
                                        + "\treload)\n"
                                        + "\t\treload\n"
                                        + "\t\t;;\n"
                                        + "\tcondrestart)\n"
                                        + "\t\tif [ -f /var/lock/subsys/sshd").print(c).print(" ] ; then\n"
                                        + "\t\t\tdo_restart_sanity_check\n"
                                        + "\t\t\tif [ \"$RETVAL\" = 0 ] ; then\n"
                                        + "\t\t\t\tstop\n"
                                        + "\t\t\t\t# avoid race\n"
                                        + "\t\t\t\tsleep 3\n"
                                        + "\t\t\t\tstart\n"
                                        + "\t\t\tfi\n"
                                        + "\t\tfi\n"
                                        + "\t\t;;\n"
                                        + "\tstatus)\n"
                                        + "\t\tstatus $SSHD\n"
                                        + "\t\tRETVAL=$?\n"
                                        + "\t\t;;\n"
                                        + "\t*)\n"
                                        + "\t\tgprintf \"Usage: %s {start|stop|restart|reload|condrestart|status}\n\" \"$0\"\n"
                                        + "\t\tRETVAL=1\n"
                                        + "esac\n"
                                        + "exit $RETVAL\n"
                                );
                            } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
			} finally {
			    out.flush();
			    out.close();
			}

                        // Add using /sbin/chkconfig
                        AOServDaemon.exec(
                            new String[] {
                                "/sbin/chkconfig",
                                "--add",
                                "sshd"+c
                            }
                        );
                    } catch(IOException err) {
                        AOServDaemon.reportError(err, null);
                    }
                }

                // Restart all necessary daemons
                for(int c=1;c<=numServers;c++) {
                    if(needsRestarted.contains(c)) {
                        try {
                            AOServDaemon.exec(
                                new String[] {
                                    "/etc/rc.d/init.d/sshd"+c,
                                    "start"
                                }
                            );
                        } catch(IOException err) {
                            AOServDaemon.reportError(err, null);
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SshdManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(SshdManager.class) && sshdManager==null) {
                synchronized(System.out) {
                    if(sshdManager==null) {
                        System.out.print("Starting SshdManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        sshdManager=new SshdManager();
                        conn.netBinds.addTableListener(sshdManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SshdManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild SSH Configuration";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
