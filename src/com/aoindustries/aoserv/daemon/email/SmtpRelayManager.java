package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.Package;
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
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
 * Accepts authentication information from the various mail protocols.
 * Stores this authentication information in the database, updating
 * the database no more than once per hour per client.
 * <p>
 * Using this authentication information, the <code>/etc/mail/access</code>
 * and <code>/etc/mail/access.db</code> files are rebuilt not more than
 * once every 60 seconds, with an initial 5 second delay from being idle.
 * <p>
 * Rebuilding this file causes the MTA to allow anybody who successfully
 * logs into a mail program to also use the SMTP server.  This lets our
 * clients seemlessly use our SMTP without us maintaining any data manually.
 *
 * @author  AO Industries, Inc.
 */
public class SmtpRelayManager extends BuilderThread implements Runnable {

    public static final int REFRESH_PERIOD=15*60*1000;

    /**
     * sendmail configs
     */
    private static final String ACCESS_FILENAME="/etc/mail/access";
    private static final String NEW_FILE="/etc/mail/access.new";

    /**
     * qmail configs
     */
    private static final String
        qmailFile="/etc/tcp.smtp",
        newQmailFile="/etc/tcp.smtp.new"
    ;

    private static SmtpRelayManager smtpRelayManager;

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpRelayManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer server=AOServDaemon.getThisAOServer();

            int osv=server.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            EmailSmtpRelayType allowRelay=connector.emailSmtpRelayTypes.get(EmailSmtpRelayType.ALLOW_RELAY);
            boolean isQmail=server.isQmail();

            synchronized(rebuildLock) {
                // The IP addresses that have been used
                List<String> usedIPs=new SortedArrayList<String>();

                UnixFile access, newFile;
                ChainWriter out=null;
                try {
                    if(isQmail) {
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                new UnixFile(newQmailFile).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                            )
                        );

                        access=new UnixFile(qmailFile);
                        newFile=new UnixFile(newQmailFile);
                    } else {
                        // Rebuild the new config file
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                new UnixFile(NEW_FILE).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                            )
                        );
                        out.print("# These entries were generated by ").print(SmtpRelayManager.class.getName()).print('\n');

                        access=new UnixFile(ACCESS_FILENAME);
                        newFile=new UnixFile(NEW_FILE);
                    }

                    // Allow all of the local IP addresses
                    for(NetDevice nd : server.getNetDevices()) {
                        for(IPAddress ia : nd.getIPAddresses()) {
                            String ip=ia.getIPAddress();
                            if(!usedIPs.contains(ip)) {
                                writeAccessLine(out, ip, allowRelay, isQmail);
                                usedIPs.add(ip);
                            }
                        }
                    }

                    // Deny first
                    List<EmailSmtpRelay> relays = server.getEmailSmtpRelays();
                    for(EmailSmtpRelay ssr : relays) {
                        if(ssr.getDisableLog()==null) {
                            EmailSmtpRelayType esrt=ssr.getType();
                            String type=esrt.getName();
                            if(
                                type.equals(EmailSmtpRelayType.DENY)
                                || type.equals(EmailSmtpRelayType.DENY_SPAM)
                            ) {
                                long expiration=ssr.getExpiration();
                                if(expiration==EmailSmtpRelay.NO_EXPIRATION || expiration>System.currentTimeMillis()) {
                                    String ip=ssr.getHost();
                                    if(!usedIPs.contains(ip)) {
                                        writeAccessLine(out, ip, esrt, isQmail);
                                        usedIPs.add(ip);
                                    }
                                }
                            }
                        }
                    }

                    // Allow last
                    for(EmailSmtpRelay ssr : relays) {
                        if(ssr.getDisableLog()==null) {
                            EmailSmtpRelayType esrt=ssr.getType();
                            String type=esrt.getName();
                            if(
                                type.equals(EmailSmtpRelayType.ALLOW)
                                || type.equals(EmailSmtpRelayType.ALLOW_RELAY)
                            ) {
                                long expiration=ssr.getExpiration();
                                if(expiration==EmailSmtpRelay.NO_EXPIRATION || expiration>System.currentTimeMillis()) {
                                    String ip=ssr.getHost();
                                    if(!usedIPs.contains(ip)) {
                                        writeAccessLine(out, ip, esrt, isQmail);
                                        usedIPs.add(ip);
                                    }
                                }
                            }
                        }
                    }
                } finally {
		    if(out!=null) {
			out.flush();
			out.close();
		    }
                }

                if(access.getStat().exists() && !newFile.contentEquals(access)) {
                    newFile.renameTo(access);
                    makeAccessMap();
                } else newFile.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    private static void writeAccessLine(ChainWriter out, String host, EmailSmtpRelayType type, boolean isQmail) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, SmtpRelayManager.class, "writeAccessLine(ChainWriter,String,EmailSmtpRelayType,boolean)", null);
        try {
            if(isQmail) out.print(host).print(':').print(StringUtility.replace(type.getQmailConfig(), "%h", host)).print('\n');
            else out.print(host).print('\t').print(StringUtility.replace(type.getSendmailConfig(), "%h", host)).print('\n');
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    /**
     * Gets the number of dots in the String, returning a maximum of 3 even if there are more
     */
    private static int getDotCount(String S) {
        Profiler.startProfile(Profiler.FAST, SmtpRelayManager.class, "getDotCount(String)", null);
        try {
            int count=0;
            int len=S.length();
            for(int c=0;c<len;c++) {
                if(S.charAt(c)=='.') {
                    count++;
                    if(count>=3) break;
                }
            }
            return count;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String[] qmailctlCdbCommand={
        "/var/qmail/bin/qmailctl",
        "cdb"
    };

    private static final String[] sendmailMakemapCommand={
        "/usr/aoserv/daemon/bin/make_sendmail_access_map"
    };

    private static void makeAccessMap() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpRelayManager.class, "makeAccessMap()", null);
        try {
            AOServDaemon.exec(
                AOServDaemon.getThisAOServer().isQmail()?qmailctlCdbCommand
                :sendmailMakemapCommand
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private SmtpRelayManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpRelayManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpRelayManager.class, "run()", null);
        try {
            long lastTime=Long.MIN_VALUE;
            while(true) {
                try {
                    while(true) {
                        try {
                            Thread.sleep(REFRESH_PERIOD);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                        long time=System.currentTimeMillis();
                        boolean needRebuild=false;
                        for(EmailSmtpRelay relay : AOServDaemon.getThisAOServer().getEmailSmtpRelays()) {
                            long expires=relay.getExpiration();
                            if(
                                expires!=EmailSmtpRelay.NO_EXPIRATION
                                && expires>=lastTime
                                && expires<time
                            ) {
                                needRebuild=true;
                                break;
                            }
                        }
                        lastTime=time;
                        if(needRebuild) doRebuild();
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(REFRESH_PERIOD);
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
        Profiler.startProfile(Profiler.UNKNOWN, SmtpRelayManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(SmtpRelayManager.class) && smtpRelayManager==null) {
                synchronized(System.out) {
                    if(smtpRelayManager==null) {
                        System.out.print("Starting SmtpRelayManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        smtpRelayManager=new SmtpRelayManager();
                        connector.emailSmtpRelays.addTableListener(smtpRelayManager, 0);
                        connector.ipAddresses.addTableListener(smtpRelayManager, 0);
                        connector.netDevices.addTableListener(smtpRelayManager, 0);
                        new Thread(smtpRelayManager, "SmtpRelayManaged").start();
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpRelayManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild SMTP Relays";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SmtpRelayManager.class, "getProcessTimerMaximumTime()", null);
        try {
            return (long)30*60*1000;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
