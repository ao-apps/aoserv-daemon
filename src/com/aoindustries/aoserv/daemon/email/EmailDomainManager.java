package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailDomain;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * @author  AO Industries, Inc.
 */
public final class EmailDomainManager extends BuilderThread {

    /**
     * email configs.
     */
    private static final UnixFile
        newFile=new UnixFile("/etc/mail/local-host-names.new"),
        configFile=new UnixFile("/etc/mail/local-host-names"),
        backupFile=new UnixFile("/etc/mail/local-host-names.old")
    ;

    /**
     * qmail configs.
     */
    /*private static final UnixFile
        newRcptHosts=new UnixFile("/etc/qmail/rcpthosts.new"),
        rcptHosts=new UnixFile("/etc/qmail/rcpthosts"),
        oldRcptHosts=new UnixFile("/etc/qmail/rcpthosts.old")
    ;*/

    private static EmailDomainManager emailDomainManager;

    private EmailDomainManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        //AOServConnector connector=AOServDaemon.getConnector();
        AOServer thisAOServer=AOServDaemon.getThisAOServer();

        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
        ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

        synchronized(rebuildLock) {
            // Grab the list of domains from the database
            List<EmailDomain> domains = thisAOServer.getEmailDomains();

            // Create the new file
            //boolean isQmail=thisAOServer.isQmail();
            UnixFile unixFile=/*isQmail?newRcptHosts:*/newFile;
            ChainWriter out = new ChainWriter(
                new BufferedOutputStream(
                    unixFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, false)
                )
            );
            try {
                // qmail always lists localhost
                // if(isQmail) out.print("localhost\n");

                for (EmailDomain domain : domains) out.println(domain.getDomain());
            } finally {
                out.flush();
                out.close();
            }

            // Move the old one to be backup
            /*if(isQmail) {
                rcptHosts.renameTo(oldRcptHosts);
                newRcptHosts.renameTo(rcptHosts);
            } else {*/
                configFile.renameTo(backupFile);
                newFile.renameTo(configFile);
            //}

            // Reload the MTA
            reloadMTA();
        }
    }

    private static final Object reloadLock=new Object();
    /*private static final String[] reloadQmailCommand={
        "/var/qmail/bin/qmailctl",
        "reload"
    };*/
    private static final String[] reloadSendmailCommandRedHat={
        "/usr/bin/killall",
        "-HUP",
        "sendmail"
    };
    private static final String[] reloadSendmailCommandMandriva={
        "/usr/bin/killall",
        "-HUP",
        "sendmail.sendmail"
    };
    public static void reloadMTA() throws IOException, SQLException {
        synchronized(reloadLock) {
            /*if(AOServDaemon.getThisAOServer().isQmail()) {
                AOServDaemon.exec(reloadQmailCommand);
            } else {*/
                int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
                String[] cmd;
                if(
                    osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    cmd=reloadSendmailCommandMandriva;
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    cmd=reloadSendmailCommandRedHat;
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                AOServDaemon.exec(cmd);
            //}
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
                && AOServDaemonConfiguration.isManagerEnabled(EmailDomainManager.class)
                && emailDomainManager==null
            ) {
                System.out.print("Starting EmailDomainManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                emailDomainManager=new EmailDomainManager();
                connector.emailDomains.addTableListener(emailDomainManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild Email Domains";
    }
}
