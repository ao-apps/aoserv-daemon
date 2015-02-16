/*
 * Copyright 2001-2013, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailSmtpRelay;
import com.aoindustries.aoserv.client.EmailSmtpRelayType;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls access to the mail server, supports auto-expiring SMTP access.
 *
 * @author  AO Industries, Inc.
 */
public class SmtpRelayManager extends BuilderThread implements Runnable {

    private static final int REFRESH_PERIOD = 15*60*1000;

    /**
     * sendmail configs
     */
    private static final String ACCESS_FILENAME="/etc/mail/access";
    private static final String NEW_FILE="/etc/mail/access.new";

    /**
     * qmail configs
     */
    /*private static final String
        qmailFile="/etc/tcp.smtp",
        newQmailFile="/etc/tcp.smtp.new"
    ;*/

    private static SmtpRelayManager smtpRelayManager;

    private static final Object rebuildLock=new Object();
	@Override
    protected boolean doRebuild() {
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();
            Server server = aoServer.getServer();

            int osv=server.getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            EmailSmtpRelayType allowRelay=connector.getEmailSmtpRelayTypes().get(EmailSmtpRelayType.ALLOW_RELAY);
            //boolean isQmail=server.isQmail();

            // The IP addresses that have been used
            Set<String> usedHosts=new HashSet<>();

            synchronized(rebuildLock) {
                UnixFile access, newFile;
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                try (ChainWriter out = new ChainWriter(bout)) {
                    /*if(isQmail) {
                        access=new UnixFile(qmailFile);
                        newFile=new UnixFile(newQmailFile);
                    } else {*/
                        // Rebuild the new config file
                        out.print("# These entries were generated by ").print(SmtpRelayManager.class.getName()).print('\n');

                        access=new UnixFile(ACCESS_FILENAME);
                        newFile=new UnixFile(NEW_FILE);
                    //}

                    // Allow all of the local IP addresses
                    for(NetDevice nd : server.getNetDevices()) {
                        for(IPAddress ia : nd.getIPAddresses()) {
                            String ip=ia.getInetAddress().toString();
                            if(!usedHosts.contains(ip)) {
                                writeAccessLine(out, ip, allowRelay/*, isQmail*/);
                                usedHosts.add(ip);
                            }
                        }
                    }

                    // Deny first
                    List<EmailSmtpRelay> relays = aoServer.getEmailSmtpRelays();
                    for(EmailSmtpRelay ssr : relays) {
                        if(!ssr.isDisabled()) {
                            EmailSmtpRelayType esrt=ssr.getType();
                            String type=esrt.getName();
                            if(
                                type.equals(EmailSmtpRelayType.DENY)
                                || type.equals(EmailSmtpRelayType.DENY_SPAM)
                            ) {
                                Timestamp expiration=ssr.getExpiration();
                                if(expiration==null || expiration.getTime()>System.currentTimeMillis()) {
                                    String host=ssr.getHost().toString();
                                    if(!usedHosts.contains(host)) {
                                        writeAccessLine(out, host, esrt/*, isQmail*/);
                                        usedHosts.add(host);
                                    }
                                }
                            }
                        }
                    }

                    // Allow last
                    for(EmailSmtpRelay ssr : relays) {
                        if(!ssr.isDisabled()) {
                            EmailSmtpRelayType esrt=ssr.getType();
                            String type=esrt.getName();
                            if(
                                type.equals(EmailSmtpRelayType.ALLOW)
                                || type.equals(EmailSmtpRelayType.ALLOW_RELAY)
                            ) {
                                Timestamp expiration=ssr.getExpiration();
                                if(expiration==null || expiration.getTime()>System.currentTimeMillis()) {
                                    String host=ssr.getHost().toString();
                                    if(!usedHosts.contains(host)) {
                                        writeAccessLine(out, host, esrt/*, isQmail*/);
                                        usedHosts.add(host);
                                    }
                                }
                            }
                        }
                    }
                }
                byte[] newBytes = bout.toByteArray();

                if(!access.getStat().exists() || !access.contentEquals(newBytes)) {
                    try (FileOutputStream newOut = newFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)) {
                        newOut.write(newBytes);
                    }
                    newFile.renameTo(access);
                    makeAccessMap();
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(SmtpRelayManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    private static void writeAccessLine(ChainWriter out, String host, EmailSmtpRelayType type/*, boolean isQmail*/) throws IOException, SQLException {
        /*if(isQmail) out.print(host).print(':').print(StringUtility.replace(type.getQmailConfig(), "%h", host)).print('\n');
        else*/ out.print("Connect:").print(host).print('\t').print(StringUtility.replace(type.getSendmailConfig(), "%h", host)).print('\n');
    }
    /**
     * Gets the number of dots in the String, returning a maximum of 3 even if there are more
     */
    /*
    private static int getDotCount(String S) {
        int count=0;
        int len=S.length();
        for(int c=0;c<len;c++) {
            if(S.charAt(c)=='.') {
                count++;
                if(count>=3) break;
            }
        }
        return count;
    }*/

    /*private static final String[] qmailctlCdbCommand={
        "/var/qmail/bin/qmailctl",
        "cdb"
    };*/

    private static final String[] mandrivaSendmailMakemapCommand={
        "/usr/aoserv/daemon/bin/make_sendmail_access_map"
    };

    private static final String[] centosSendmailMakemapCommand={
        "/opt/aoserv-daemon/bin/make_sendmail_access_map"
    };

    private static void makeAccessMap() throws IOException, SQLException {
        String[] command;
        /*if(AOServDaemon.getThisAOServer().isQmail()) command = qmailctlCdbCommand;
        else {*/
            int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) command = mandrivaSendmailMakemapCommand;
            else if(
                osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) command = centosSendmailMakemapCommand;
            else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
        //}
        AOServDaemon.exec(command);
    }

    private SmtpRelayManager() {
    }

	@Override
    public void run() {
        long lastTime=Long.MIN_VALUE;
        while(true) {
            try {
                while(true) {
                    try {
                        Thread.sleep(REFRESH_PERIOD);
                    } catch(InterruptedException err) {
                        LogFactory.getLogger(SmtpRelayManager.class).log(Level.WARNING, null, err);
                    }
                    long time=System.currentTimeMillis();
                    boolean needRebuild=false;
                    for(EmailSmtpRelay relay : AOServDaemon.getThisAOServer().getEmailSmtpRelays()) {
                        Timestamp expiration=relay.getExpiration();
                        if(
                            expiration!=null
                            && expiration.getTime()>=lastTime
                            && expiration.getTime()<time
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
                LogFactory.getLogger(SmtpRelayManager.class).log(Level.SEVERE, null, T);
                try {
                    Thread.sleep(REFRESH_PERIOD);
                } catch(InterruptedException err) {
                    LogFactory.getLogger(SmtpRelayManager.class).log(Level.WARNING, null, err);
                }
            }
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(SmtpRelayManager.class)
                && smtpRelayManager==null
            ) {
                System.out.print("Starting SmtpRelayManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                smtpRelayManager=new SmtpRelayManager();
                connector.getEmailSmtpRelays().addTableListener(smtpRelayManager, 0);
                connector.getIpAddresses().addTableListener(smtpRelayManager, 0);
                connector.getNetDevices().addTableListener(smtpRelayManager, 0);
                new Thread(smtpRelayManager, "SmtpRelayManaged").start();
                System.out.println("Done");
            }
        }
    }

	@Override
    public String getProcessTimerDescription() {
        return "Rebuild SMTP Relays";
    }

    @Override
    public long getProcessTimerMaximumTime() {
        return (long)30*60*1000;
    }
}
