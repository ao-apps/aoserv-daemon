package com.aoindustries.aoserv.daemon.dns;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.DnsZone;
import com.aoindustries.aoserv.client.IndexedSet;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.client.validator.NetPort;
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
import java.util.SortedSet;
import java.util.TreeSet;
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
        + " 64.71.143.176/29;" // Firewalls
        + " 66.160.183.0/24;"  // Hosts
        + " 64.62.174.0/24;"   // More Hosts
        // Amsterdam
        + " 64.62.145.40/29;"  // Firewalls
        + " 64.71.144.0/25;"   // Hosts
        // Mobile
        + " 67.177.66.203;"    // 7262 Bull Pen Cir
        // Spain
        + " 81.19.103.96/28;"  // Firewalls
        + " 81.19.103.64/27;"  // Hosts
        // Secure Medical
        + " 66.17.86.0/24;"
    ;

    private static final UnixFile
        newConfFile=new UnixFile("/etc/named.conf.new"),
        confFile=new UnixFile("/etc/named.conf")
    ;

    private static final UnixFile namedZoneDir=new UnixFile("/var/named");

    /**
     * Files and directories in /var/named that are never removed.
     */
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
        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) return centos5StaticFiles;
        throw new IllegalArgumentException("Unsupported OperatingSystemVersion: "+osv);
    }

    private static DNSManager dnsManager;

    private DNSManager() {
    }

    /**
     * Each zone is only rebuilt if the zone file does not exist or its serial has changed.
     */
    private static final Map<DnsZone,Long> zoneSerials=new HashMap<DnsZone,Long>();

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServConnector<?,?> connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Only restart when needed
                boolean needsRestart=false;

                // Keep track of all files that should NOT be deleted in /var/named
                List<String> files=new ArrayList<String>();

                // This buffer is used throughout the rest of the method
                ByteArrayOutputStream bout = new ByteArrayOutputStream();

                /*
                 * Create the new /var/named files
                 */
                // By getting the list first, we get a snap-shot of the data
                SortedSet<DnsZone> zones = new TreeSet<DnsZone>(connector.getDnsZones().getSet());
                for(DnsZone zone : zones) {
                    long serial=zone.getSerial();
                    Long lastSerial=zoneSerials.get(zone);
                    String file=zone.getFile();
                    boolean skip;
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        // Skip the named.local file for CentOS 5
                        skip = "named.local".equals(file);
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
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
                    out.print("options {\n"
                            + "\tdirectory \"").print(namedZoneDir.getPath()).print("\";\n");
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("\tlisten-on-v6 port 53 { ::1; };\n"
                                + "\tdump-file \"/var/named/data/cache_dump.db\";\n"
                                + "\tstatistics-file \"/var/named/data/named_stats.txt\";\n"
                                + "\tmemstatistics-file \"/var/named/data/named_mem_stats.txt\";\n"
                                + "\tquery-source port 53;\n"
                                + "\tquery-source-v6 port 53;\n"
                        );
                    }
                    out.print("\tallow-transfer { 216.218.130.2; 216.218.131.2; 216.218.132.2; };\n"
                            + "\tnotify explicit;\n"
                            + "\talso-notify { 216.218.130.2; 216.218.131.2; 216.218.132.2; };\n"
                            + "\tallow-query { " + ACL + " };\n"
                            + "\tallow-recursion { " + ACL + " };\n");
                    Map<NetPort,Set<InetAddress>> alreadyAddedIPs = new HashMap<NetPort,Set<InetAddress>>();
                    for(NetBind nb : AOServDaemon.getThisAOServer().getServer().getNetBinds(connector.getProtocols().get(Protocol.DNS))) {
                        NetPort port = nb.getPort();
                        InetAddress ip = nb.getIpAddress().getIpAddress();
                        Set<InetAddress> ips = alreadyAddedIPs.get(port);
                        if(ips==null) alreadyAddedIPs.put(port, ips = new HashSet<InetAddress>());
                        if(!ips.contains(ip)) {
                            out.print("\tlisten-on port ").print(port).print(" { ").print(ip).print("; };\n");
                            ips.add(ip);
                        }
                    }
                    out.print("};\n");
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("logging {\n"
                                + "\tchannel default_debug {\n"
                                + "\t\tfile \"data/named.run\";\n"
                                + "\t\tseverity dynamic;\n"
                                + "\t};\n"
                                + "};\n"
                                + "include \"/etc/named.rfc1912.zones\";\n");
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    for(DnsZone zone : zones) {
                        String file = zone.getFile();
                        boolean skip;
                        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                            // Skip the named.local file for CentOS 5
                            skip = "named.local".equals(file);
                        } else {
                            throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
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
                } finally {
                    out.flush();
                    out.close();
                }
                byte[] newContents = bout.toByteArray();
                if(!confFile.getStat().exists() || !confFile.contentEquals(newContents)) {
                    needsRestart=true;
                    OutputStream newOut;
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        LinuxGroup lg = thisAOServer.getLinuxGroup(LinuxGroup.NAMED);
                        newOut = newConfFile.getSecureOutputStream(UnixFile.ROOT_UID, lg.getGid().getId(), 0640, true);
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    try {
                        newOut.write(newContents);
                    } finally {
                        newOut.flush();
                        newOut.close();
                    }
                    newConfFile.renameTo(confFile);
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
            LogFactory.getLogger(FTPManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    private static final Object restartLock=new Object();
    private static void restart() throws IOException, SQLException {
        Protocol dns = AOServDaemon.getConnector().getProtocols().get(Protocol.DNS);
        if(!AOServDaemon.getThisAOServer().getServer().getNetBinds(dns).isEmpty()) {
            synchronized(restartLock) {
                AOServDaemon.exec(restartCommand);
            }
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        IndexedSet<NetBind> netBinds = AOServDaemon.getThisAOServer().getServer().getNetBinds(AOServDaemon.getConnector().getProtocols().get(Protocol.DNS));
        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(DNSManager.class)
                && dnsManager==null
                && !netBinds.isEmpty()
            ) {
                System.out.print("Starting DNSManager: ");
                AOServConnector<?,?> conn=AOServDaemon.getConnector();
                dnsManager=new DNSManager();
                conn.getDnsZones().getTable().addTableListener(dnsManager, 0);
                conn.getDnsRecords().getTable().addTableListener(dnsManager, 0);
                conn.getNetBinds().getTable().addTableListener(dnsManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild DNS";
    }
}
