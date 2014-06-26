/*
 * Copyright 2000-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.dns;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.DNSZone;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of name server processes and files.
 *
 * @author  AO Industries, Inc.
 */
final public class DNSManager extends BuilderThread {

    private static final String[] restartCommand={
        "/etc/rc.d/init.d/named",
        "restart"
    };

    /**
     * The IPs allowed to query non-AO names.
     */
    private static final String ACL =
        // Private IP addresses used internally
          "10.0.0.0/8;"
        + " 172.16.0.0/24;"
        + " 192.168.0.0/16;"
        // Loopback IP
        + " 127.0.0.0/8;"
        // Kansas City
        + " 207.126.57.0/24;"  // Hosts
        // Fremont
        //+ " 64.71.143.176/29;" // Firewalls
        + " 66.160.183.0/24;"  // Virtual Servers
        + " 64.62.174.0/24;"   // Virtual Servers
        + " 64.71.144.0/25;"   // Virtual Servers
        //+ " 66.220.7.80/29;"   // gtapolicemods.com
        // Fremont Management 
        + " 65.19.176.24/29;" // Firewalls
        + " 66.220.7.0/27;"  // Hosts
        // Amsterdam
        //+ " 64.62.145.40/29;"  // Firewalls
        // Mobile
        //+ " 70.91.161.42;"     // 7262 Bull Pen Cir
        // Spain
        //+ " 81.19.103.96/28;"  // Firewalls
        //+ " 81.19.103.64/27;"  // Hosts
        // Secure Medical
        //+ " 66.17.86.0/24;"
    ;

    private static final UnixFile
        newConfFile=new UnixFile("/etc/named.conf.new"),
        confFile=new UnixFile("/etc/named.conf")
    ;

    private static final UnixFile namedZoneDir=new UnixFile("/var/named");

    /**
     * The file that stores the root server cache.
     */
    public static final String
        MANDRIVA_ROOT_CACHE="named.ca",
        NEW_MANDRIVA_ROOT_CACHE="named.ca.new"
    ;

    /**
     * Files and directories in /var/named that are never removed.
     */
    private static final String[] mandrivaStaticFiles={
        MANDRIVA_ROOT_CACHE,
        "named_dump.db"
    };
    private static final String[] centos5StaticFiles={
        "chroot",
        "data",
        "localdomain.zone",
        "localhost.zone",
        "named.broadcast",
        "named.ca",
        "named.ip6.local",
        "named.local",
        "named.zero",
        "slaves"
    };
    private static String[] getStaticFiles(int osv) throws IllegalArgumentException {
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) return mandrivaStaticFiles;
        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) return centos5StaticFiles;
        throw new IllegalArgumentException("Unsupported OperatingSystemVersion: "+osv);
    }

    private static DNSManager dnsManager;

    private DNSManager() {
    }

    /**
     * Each zone is only rebuild if the zone file does not exist or its serial has changed.
     */
    private static final Map<DNSZone,Long> zoneSerials=new HashMap<>();

    private static final Object rebuildLock=new Object();
	@Override
    protected boolean doRebuild() {
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Only restart when needed
                boolean needsRestart=false;

                // Keep track of all files that should NOT be deleted in /var/named
                List<String> files=new ArrayList<>();

                // This buffer is used throughout the rest of the method
                ByteArrayOutputStream bout = new ByteArrayOutputStream();

                /*
                 * Create the new /var/named files
                 */
                // By getting the list first, we get a snap-shot of the data
                List<DNSZone> zones=connector.getDnsZones().getRows();
                for(DNSZone zone : zones) {
                    long serial=zone.getSerial();
                    Long lastSerial=zoneSerials.get(zone);
                    String file=zone.getFile();
                    boolean skip;
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        skip = false;
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        // Skip the named.local file for CentOS 5
                        skip = "named.local".equals(file);
                    } else {
                        throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                    }
                    if(!skip) {
                        UnixFile realFile=new UnixFile(namedZoneDir, file, false);
                        Stat realFileStat = realFile.getStat();
                        if(
                            lastSerial==null
                            || lastSerial.longValue()!=serial
                            || !realFileStat.exists()
                        ) {
                            // Build to a memory buffer
                            bout.reset();
                            PrintWriter out=new PrintWriter(bout);
                            try {
                                zone.printZoneFile(out);
                            } finally {
                                out.flush();
                                out.close();
                            }
                            byte[] newContents = bout.toByteArray();

                            if(!realFileStat.exists() || !realFile.contentEquals(newContents)) {
                                UnixFile newFile=new UnixFile(namedZoneDir, file+".new", false);
                                OutputStream newOut = newFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                                try {
                                    newOut.write(newContents);
                                } finally {
                                    newOut.flush();
                                    newOut.close();
                                }
                                newFile.renameTo(realFile);
                                needsRestart=true;
                            }

                            zoneSerials.put(zone, Long.valueOf(serial));
                        }
                        files.add(file);
                    }
                }

                /*
                 * Create the new /etc/named.conf file
                 */
                bout.reset();
                ChainWriter out=new ChainWriter(bout);
                try {
                    out.print("options {\n");
                    if(
                        osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                    ) {
                        out.print("\tpid-file \"/var/run/named/named.pid\";\n"
                                // safe-mail.net didn't resolve with this source port: + "\tquery-source address * port 53;\n"
                        );
                    }
                    out.print("\tdirectory \"").print(namedZoneDir.getPath()).print("\";\n");
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("\tlisten-on-v6 port 53 { ::1; };\n"
                                + "\tdump-file \"/var/named/data/cache_dump.db\";\n"
                                + "\tstatistics-file \"/var/named/data/named_stats.txt\";\n"
                                + "\tmemstatistics-file \"/var/named/data/named_mem_stats.txt\";\n"
                                // query-source equal to 53 also made it harder to distinguish incoming/outgoing queries on firewall
                                // safe-mail.net didn't resolve with this source port: + "\tquery-source port 53;\n"
                                // safe-mail.net didn't resolve with this source port: + "\tquery-source-v6 port 53;\n"
                        );
                    }
                    out.print("\tallow-transfer { 216.218.130.2; 216.218.131.2; 216.218.132.2; 216.66.1.2; 216.66.80.18; 216.218.133.2; };\n"
                            + "\tnotify explicit;\n"
                            + "\talso-notify { 216.218.130.2; 216.218.131.2; 216.218.132.2; 216.66.1.2; 216.66.80.18; 216.218.133.2; };\n"
                            + "\tallow-query { " + ACL + " };\n"
                            + "\tallow-recursion { " + ACL + " };\n");
                    Map<Integer,Set<InetAddress>> alreadyAddedIPs = new HashMap<>();
                    for(NetBind nb : AOServDaemon.getThisAOServer().getServer().getNetBinds(connector.getProtocols().get(Protocol.DNS))) {
                        int port = nb.getPort().getPort();
                        InetAddress ip = nb.getIPAddress().getInetAddress();
                        Set<InetAddress> ips = alreadyAddedIPs.get(port);
                        if(ips==null) alreadyAddedIPs.put(port, ips = new HashSet<>());
                        if(!ips.contains(ip)) {
                            if(ip.isIPv6()) {
                                // IPv6
                                out.print("\tlisten-on-v6 port ").print(port).print(" { ").print(ip.toString()).print("; };\n");
                            } else {
                                // IPv4
                                out.print("\tlisten-on port ").print(port).print(" { ").print(ip.toString()).print("; };\n");
                            }
                            ips.add(ip);
                        }
                    }
                    out.print("};\n");
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("zone \".\" IN {\n"
                                + "\ttype hint;\n"
                                + "\tfile \"named.ca\";\n"
                                + "};\n");
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("logging {\n"
                                + "\tchannel default_debug {\n"
                                + "\t\tfile \"data/named.run\";\n"
                                + "\t\tseverity dynamic;\n"
                                + "\t};\n"
                                + "};\n"
                                + "include \"/etc/named.rfc1912.zones\";\n");
                    } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                    for(DNSZone zone : zones) {
                        String file = zone.getFile();
                        boolean skip;
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            skip = false;
                        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                            // Skip the named.local file for CentOS 5
                            skip = "named.local".equals(file);
                        } else {
                            throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                        }
                        if(!skip) {
                            out.print("\n"
                                    + "zone \"").print(zone.getZone()).print("\" IN {\n"
                                    + "\ttype master;\n"
                                    + "\tfile \"").print(file).print("\";\n"
                                    + "\tallow-query { any; };\n"
                                    + "\tallow-update { none; };\n"
                                    + "};\n");
                        }
                    }
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        // Print the key
                        out.print("key \"key\" {\n"
                                  + "\talgorithm hmac-md5;\n"
                                  + "\tsecret \"JvWjWXkcPvOzMcDdFt0wtEbLfhwOQVzXviyeaZoEjZCdWpNXaOHRzQYNmcij\";\n"
                                  + "};\n");
                    }
                } finally {
                    out.flush();
                    out.close();
                }
                byte[] newContents = bout.toByteArray();
                if(!confFile.getStat().exists() || !confFile.contentEquals(newContents)) {
                    needsRestart=true;
                    OutputStream newOut;
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        newOut = newConfFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        LinuxServerGroup lsg = thisAOServer.getLinuxServerGroup(LinuxGroup.NAMED);
                        if(lsg==null) throw new SQLException("Unable to find LinuxServerGroup: "+LinuxGroup.NAMED+" on "+thisAOServer.getHostname());
                        newOut = newConfFile.getSecureOutputStream(UnixFile.ROOT_UID, lsg.getGid().getID(), 0640, true);
                    } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                    try {
                        newOut.write(newContents);
                    } finally {
                        newOut.flush();
                        newOut.close();
                    }
                    newConfFile.renameTo(confFile);
                }

                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    /*
                     * Write the new root cache file.
                     */
                    UnixFile newRoot=new UnixFile(namedZoneDir, NEW_MANDRIVA_ROOT_CACHE, false);
                    out=new ChainWriter(
                        newRoot.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                    );
                    try {
                        out.print("; Use \"dig @A.ROOT-SERVERS.NET . ns\" to update this file if it's outdated.\n"
                                + ";       This file holds the information on root name servers needed to\n"
                                + ";       initialize cache of Internet domain name servers\n"
                                + ";       (e.g. reference this file in the \"cache  .  <file>\"\n"
                                + ";       configuration file of BIND domain name servers).\n"
                                + ";\n"
                                + ";       This file is made available by InterNIC\n"
                                + ";       under anonymous FTP as\n"
                                + ";           file                /domain/named.root\n"
                                + ";           on server           FTP.INTERNIC.NET\n"
                                + ";       -OR-                    RS.INTERNIC.NET\n"
                                + ";\n"
                                + ";       last update:    Jan 29, 2004\n"
                                + ";       related version of root zone:   2004012900\n"
                                + ";\n"
                                + ";\n"
                                + "; formerly NS.INTERNIC.NET\n"
                                + ";\n"
                                + ".                        3600000  IN  NS    A.ROOT-SERVERS.NET.\n"
                                + "A.ROOT-SERVERS.NET.      3600000      A     198.41.0.4\n"
                                + ";\n"
                                + "; formerly NS1.ISI.EDU\n"
                                + ";\n"
                                + ".                        3600000      NS    B.ROOT-SERVERS.NET.\n"
                                + "B.ROOT-SERVERS.NET.      3600000      A     192.228.79.201\n"
                                + ";\n"
                                + "; formerly C.PSI.NET\n"
                                + ";\n"
                                + ".                        3600000      NS    C.ROOT-SERVERS.NET.\n"
                                + "C.ROOT-SERVERS.NET.      3600000      A     192.33.4.12\n"
                                + ";\n"
                                + "; formerly TERP.UMD.EDU\n"
                                + ";\n"
                                + ".                        3600000      NS    D.ROOT-SERVERS.NET.\n"
                                + "D.ROOT-SERVERS.NET.      3600000      A     128.8.10.90\n"
                                + ";\n"
                                + "; formerly NS.NASA.GOV\n"
                                + ";\n"
                                + ".                        3600000      NS    E.ROOT-SERVERS.NET.\n"
                                + "E.ROOT-SERVERS.NET.      3600000      A     192.203.230.10\n"
                                + ";\n"
                                + "; formerly NS.ISC.ORG\n"
                                + ";\n"
                                + ".                        3600000      NS    F.ROOT-SERVERS.NET.\n"
                                + "F.ROOT-SERVERS.NET.      3600000      A     192.5.5.241\n"
                                + ";\n"
                                + "; formerly NS.NIC.DDN.MIL\n"
                                + ";\n"
                                + ".                        3600000      NS    G.ROOT-SERVERS.NET.\n"
                                + "G.ROOT-SERVERS.NET.      3600000      A     192.112.36.4\n"
                                + ";\n"
                                + "; formerly AOS.ARL.ARMY.MIL\n"
                                + ";\n"
                                + ".                        3600000      NS    H.ROOT-SERVERS.NET.\n"
                                + "H.ROOT-SERVERS.NET.      3600000      A     128.63.2.53\n"
                                + ";\n"
                                + "; formerly NIC.NORDU.NET\n"
                                + ";\n"
                                + ".                        3600000      NS    I.ROOT-SERVERS.NET.\n"
                                + "I.ROOT-SERVERS.NET.      3600000      A     192.36.148.17\n"
                                + ";\n"
                                + "; operated by VeriSign, Inc.\n"
                                + ";\n"
                                + ".                        3600000      NS    J.ROOT-SERVERS.NET.\n"
                                + "J.ROOT-SERVERS.NET.      3600000      A     192.58.128.30\n"
                                + ";\n"
                                + "; operated by RIPE NCC\n"
                                + ";\n"
                                + ".                        3600000      NS    K.ROOT-SERVERS.NET.\n"
                                + "K.ROOT-SERVERS.NET.      3600000      A     193.0.14.129\n"
                                + ";\n"
                                + "; operated by ICANN\n"
                                + ";\n"
                                + ".                        3600000      NS    L.ROOT-SERVERS.NET.\n"
                                + "L.ROOT-SERVERS.NET.      3600000      A     198.32.64.12\n"
                                + ";\n"
                                + "; operated by WIDE\n"
                                + ";\n"
                                + ".                        3600000      NS    M.ROOT-SERVERS.NET.\n"
                                + "M.ROOT-SERVERS.NET.      3600000      A     202.12.27.33\n"
                                + "; End of File\n"
                        );
                    } finally {
                        out.flush();
                        out.close();
                    }
                    UnixFile rootCache=new UnixFile(namedZoneDir, MANDRIVA_ROOT_CACHE, false);
                    if(rootCache.getStat().exists() && newRoot.contentEquals(rootCache)) {
                        newRoot.delete();
                    } else {
                        needsRestart=true;
                        newRoot.renameTo(rootCache);
                    }
                }

                /*
                 * Restart the daemon
                 */
                if(needsRestart) restart();

                /*
                 * Remove any files that should no longer be in /var/named
                 */
                String[] staticFiles = getStaticFiles(osv);
                for(int c=0;c<staticFiles.length;c++) files.add(staticFiles[c]);
                FTPManager.trimFiles(namedZoneDir, files);
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(DNSManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    private static final Object restartLock=new Object();
    private static void restart() throws IOException, SQLException {
        Protocol dns = AOServDaemon.getConnector().getProtocols().get(Protocol.DNS);
        if(dns==null) throw new SQLException("Unable to find Protocol: "+Protocol.DNS);
        if(!AOServDaemon.getThisAOServer().getServer().getNetBinds(dns).isEmpty()) {
            synchronized(restartLock) {
                AOServDaemon.exec(restartCommand);
            }
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        Protocol dns = AOServDaemon.getConnector().getProtocols().get(Protocol.DNS);
        if(dns==null) throw new SQLException("Unable to find Protocol: "+Protocol.DNS);
        List<NetBind> netBinds = AOServDaemon.getThisAOServer().getServer().getNetBinds(dns);

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(DNSManager.class)
                && dnsManager==null
                && !netBinds.isEmpty()
            ) {
                System.out.print("Starting DNSManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                dnsManager=new DNSManager();
                conn.getDnsZones().addTableListener(dnsManager, 0);
                conn.getDnsRecords().addTableListener(dnsManager, 0);
                conn.getNetBinds().addTableListener(dnsManager, 0);
                System.out.println("Done");
            }
        }
    }

	@Override
    public String getProcessTimerDescription() {
        return "Rebuild DNS";
    }
}
