package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
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

    private static SmtpRelayManager smtpRelayManager;

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();
            Server server = aoServer.getServer();

            int osv=server.getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            EmailSmtpRelayType allowRelay=connector.getEmailSmtpRelayTypes().get(EmailSmtpRelayType.ALLOW_RELAY);

            // The IP addresses that have been used
            Set<String> usedIPs=new HashSet<String>();

            synchronized(rebuildLock) {
                UnixFile access, newFile;
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ChainWriter out=new ChainWriter(bout);
                try {
                    // Rebuild the new config file
                    out.print("# These entries were generated by ").print(SmtpRelayManager.class.getName()).print('\n');

                    access=new UnixFile(ACCESS_FILENAME);
                    newFile=new UnixFile(NEW_FILE);

                    // Allow all of the local IP addresses
                    for(NetDevice nd : server.getNetDevices()) {
                        for(IPAddress ia : nd.getIPAddresses()) {
                            String ip=ia.getIPAddress();
                            if(!usedIPs.contains(ip)) {
                                writeAccessLine(out, ip, allowRelay);
                                usedIPs.add(ip);
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
                                long expiration=ssr.getExpiration();
                                if(expiration==EmailSmtpRelay.NO_EXPIRATION || expiration>System.currentTimeMillis()) {
                                    String ip=ssr.getHost();
                                    if(!usedIPs.contains(ip)) {
                                        writeAccessLine(out, ip, esrt);
                                        usedIPs.add(ip);
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
                                long expiration=ssr.getExpiration();
                                if(expiration==EmailSmtpRelay.NO_EXPIRATION || expiration>System.currentTimeMillis()) {
                                    String ip=ssr.getHost();
                                    if(!usedIPs.contains(ip)) {
                                        writeAccessLine(out, ip, esrt);
                                        usedIPs.add(ip);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    if(out!=null) {
                        out.close();
                    }
                }
                byte[] newBytes = bout.toByteArray();

                if(!access.getStat().exists() || !access.contentEquals(newBytes)) {
                    FileOutputStream newOut = newFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        newOut.write(newBytes);
                    } finally {
                        newOut.close();
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

    private static void writeAccessLine(ChainWriter out, String host, EmailSmtpRelayType type) throws IOException, SQLException {
        out.print("Connect:").print(host).print('\t').print(StringUtility.replace(type.getSendmailConfig(), "%h", host)).print('\n');
    }

    private static final String[] centosSendmailMakemapCommand={
        "/opt/aoserv-daemon/bin/make_sendmail_access_map"
    };

    private static void makeAccessMap() throws IOException, SQLException {
        String[] command;
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
            || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) command = centosSendmailMakemapCommand;
        else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
        AOServDaemon.exec(command);
    }

    private SmtpRelayManager() {
    }

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
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(SmtpRelayManager.class)
                && smtpRelayManager==null
            ) {
                System.out.print("Starting SmtpRelayManager: ");
                AOServConnector<?,?> connector=AOServDaemon.getConnector();
                smtpRelayManager=new SmtpRelayManager();
                connector.getEmailSmtpRelays().getTable().addTableListener(smtpRelayManager, 0);
                connector.getIpAddresses().getTable().addTableListener(smtpRelayManager, 0);
                connector.getNetDevices().getTable().addTableListener(smtpRelayManager, 0);
                new Thread(smtpRelayManager, "SmtpRelayManaged").start();
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild SMTP Relays";
    }

    @Override
    public long getProcessTimerMaximumTime() {
        return (long)30*60*1000;
    }
}
