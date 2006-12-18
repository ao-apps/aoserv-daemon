package com.aoindustries.aoserv.daemon.dns;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.ftp.*;
import com.aoindustries.aoserv.daemon.unix.linux.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

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
        // Loopbackup IP
        + " 127.0.0.0/8;"
        // Kansas City
        + " 65.77.181.128/29;" // Firewalls
        + " 65.77.211.0/24;"   // Hosts
        // Fremont
        + " 64.71.143.176/29;" // Firewalls
        + " 66.160.183.0/24;"  // Hosts
        + " 64.62.174.0/24;"   // ultimateshoppingcart.com
        // Amsterdam
        + " 64.62.145.40/29;"  // Firewalls
        + " 64.71.144.0/25;"   // Hosts
        // Mobile
        + " 68.63.51.29;"      // 2200 Dogwood Ct N
        + " 70.91.161.42;"     // 816 Azalea Rd
        // Spain
        + " 81.19.103.96/28;"  // Firewalls
        + " 81.19.103.64/27;"  // Hosts
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
        ROOT_CACHE="named.ca",
        NEW_ROOT_CACHE="named.ca.new"
    ;

    /**
     * Files in /var/named that are never removed.
     */
    private static final String[] staticFiles={
        ROOT_CACHE,
        "named_dump.db"
    };

    private static DNSManager dnsManager;

    private DNSManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DNSManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    /**
     * Each zone is only rebuild if the zone file does not exist or its serial has changed.
     */
    private static final Map<DNSZone,Long> zoneSerials=new HashMap<DNSZone,Long>();

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, DNSManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Only restart when needed
                boolean needsRestart=false;

                // Keep track of all files that should NOT be deleted in /var/named
                List<String> files=new ArrayList<String>();

                /*
                 * Create the new /var/named files
                 */
                // By getting the list first, we get a snap-shot of the data
                List<DNSZone> zones=connector.dnsZones.getRows();
                for(DNSZone zone : zones) {
                    long serial=zone.getSerial();
                    Long lastSerial=zoneSerials.get(zone);
                    String file=zone.getFile();
                    UnixFile realFile=new UnixFile(namedZoneDir, file, false);
                    Stat realFileStat = realFile.getStat();
                    if(
                        lastSerial==null
                        || lastSerial.longValue()!=serial
                        || !realFileStat.exists()
                    ) {
                        UnixFile newFile=new UnixFile(namedZoneDir, file+".new", false);

                        PrintWriter out=new PrintWriter(
                            new BufferedOutputStream(
                                newFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                            )
                        );
			try {
			    zone.printZoneFile(out);
			} finally {
			    out.flush();
			    out.close();
			}

                        if(!realFileStat.exists() || !newFile.contentEquals(realFile)) {
                            newFile.renameTo(realFile);
                            needsRestart=true;
                        } else newFile.delete();

                        zoneSerials.put(zone, Long.valueOf(serial));
                    }
                    files.add(file);
                }

                /*
                 * Create the new /etc/named.conf file
                 */
                ChainWriter out=new ChainWriter(
                    new BufferedOutputStream(
                        newConfFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                    )
                );
		try {
		    out.print("options {\n");
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                    ) {
                        out.print("\tpid-file \"/var/run/named/named.pid\";\n");
                    }
                    out.print("\tdirectory \"").print(namedZoneDir.getFilename()).print("\";\n"
                             + "\tallow-transfer { 216.218.130.2; 216.218.131.2; 216.218.132.2; };\n"
                             + "\tnotify explicit;\n"
                             + "\talso-notify { 216.218.130.2; 216.218.131.2; 216.218.132.2; };\n"
                             + "\tallow-query { " + ACL + " };\n"
                             + "\tallow-recursion { " + ACL + " };\n");
		    for(NetBind nb : AOServDaemon.getThisAOServer().getNetBinds(connector.protocols.get(Protocol.DNS))) {
			out.print("\tlisten-on port ").print(nb.getPort().getPort()).print(" { ").print(nb.getIPAddress().getIPAddress()).print("; };\n");
		    }
		    out.print("\tquery-source address * port 53;\n"
			      + "};\n"
			      + "zone \".\" IN {\n"
			      + "\ttype hint;\n"
			      + "\tfile \"named.ca\";\n"
			      + "};\n");
		    for(DNSZone zone : zones) {
			out.print("\n"
                                + "zone \"").print(zone.getZone()).print("\" IN {\n"
                                + "\ttype master;\n"
                                + "\tfile \"").print(zone.getFile()).print("\";\n"
                                + "\tallow-query { any; };\n"
                                + "\tallow-update { none; };\n"
                                + "};\n");
		    }
		    // Print the key
		    out.print("key \"key\" {\n"
			      + "\talgorithm hmac-md5;\n"
			      + "\tsecret \"JvWjWXkcPvOzMcDdFt0wtEbLfhwOQVzXviyeaZoEjZCdWpNXaOHRzQYNmcij\";\n"
			      + "};\n");
		} finally {
		    out.flush();
		    out.close();
		}
                if(confFile.getStat().exists() && newConfFile.contentEquals(confFile)) {
                    newConfFile.delete();
                } else {
                    needsRestart=true;
                    newConfFile.renameTo(confFile);
                }

                /*
                 * Write the new root cache file.
                 */
                UnixFile newRoot=new UnixFile(namedZoneDir, NEW_ROOT_CACHE, false);
                out=new ChainWriter(
                    newRoot.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                );
		try {
                    if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) {
                        out.print(";       This file holds the information on root name servers needed to\n"
                                  + ";       initialize cache of Internet domain name servers\n"
                                  + ";       (e.g. reference this file in the \"cache  .  <file>\"\n"
                                  + ";       configuration file of BIND domain name servers).\n"
                                  + ";\n"
                                  + ";       This file is made available by InterNIC\n"
                                  + ";       under anonymous FTP as\n"
                                  + ";           file                /domain/named.root\n"
                                  + ";           on server           FTP.INTERNIC.NET\n"
                                  + ";\n"
                                  + ";       last update:    Nov 5, 2002\n"
                                  + ";       related version of root zone:   2002110501\n"
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
                                  + "B.ROOT-SERVERS.NET.      3600000      A     128.9.0.107\n"
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
                                  + "; housed in LINX, operated by RIPE NCC\n"
                                  + ";\n"
                                  + ".                        3600000      NS    K.ROOT-SERVERS.NET.\n"
                                  + "K.ROOT-SERVERS.NET.      3600000      A     193.0.14.129\n"
                                  + ";\n"
                                  + "; operated by IANA\n"
                                  + ";\n"
                                  + ".                        3600000      NS    L.ROOT-SERVERS.NET.\n"
                                  + "L.ROOT-SERVERS.NET.      3600000      A     198.32.64.12\n"
                                  + ";\n"
                                  + "; housed in Japan, operated by WIDE\n"
                                  + ";\n"
                                  + ".                        3600000      NS    M.ROOT-SERVERS.NET.\n"
                                  + "M.ROOT-SERVERS.NET.      3600000      A     202.12.27.33\n"
                                  + "; End of File\n"
                          );
                    } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
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
                    } else throw new SQLException("Unexpected OperatingSystemVersion: "+osv);
		} finally {
		    out.flush();
		    out.close();
		}
                UnixFile rootCache=new UnixFile(namedZoneDir, ROOT_CACHE, false);
                if(rootCache.getStat().exists() && newRoot.contentEquals(rootCache)) {
                    newRoot.delete();
                } else {
                    needsRestart=true;
                    newRoot.renameTo(rootCache);
                }

                /*
                 * Restart the daemon
                 */
                if(needsRestart) restart();

                /*
                 * Remove any files that should no longer be in /var/named
                 */
                for(int c=0;c<staticFiles.length;c++) files.add(staticFiles[c]);
                FTPManager.trimFiles(namedZoneDir, files);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object restartLock=new Object();
    private static void restart() throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, DNSManager.class, "restart()", null);
        try {
            if(AOServDaemon.getThisAOServer().isDNS()) {
                synchronized(restartLock) {
                    AOServDaemon.exec(restartCommand);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, DNSManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(DNSManager.class) && dnsManager==null) {
                if(AOServDaemon.getThisAOServer().isDNS()) {
                    synchronized(System.out) {
                        if(dnsManager==null) {
                            System.out.print("Starting DNSManager: ");
                            AOServConnector conn=AOServDaemon.getConnector();
                            dnsManager=new DNSManager();
                            conn.dnsZones.addTableListener(dnsManager, 0);
                            conn.dnsRecords.addTableListener(dnsManager, 0);
                            conn.netBinds.addTableListener(dnsManager, 0);
                            System.out.println("Done");
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, DNSManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild DNS";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
