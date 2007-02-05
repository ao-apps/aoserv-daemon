package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import java.io.*;
import java.sql.*;
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
    private static final UnixFile
        newRcptHosts=new UnixFile("/etc/qmail/rcpthosts.new"),
        rcptHosts=new UnixFile("/etc/qmail/rcpthosts"),
        oldRcptHosts=new UnixFile("/etc/qmail/rcpthosts.old")
    ;

    private static EmailDomainManager emailDomainManager;

    private EmailDomainManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, EmailDomainManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailDomainManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Grab the list of domains from the database
                List<EmailDomain> domains = thisAOServer.getEmailDomains();

                // Create the new file
                boolean isQmail=thisAOServer.isQmail();
                UnixFile unixFile=isQmail?newRcptHosts:newFile;
                ChainWriter out = new ChainWriter(
                    new BufferedOutputStream(
                        unixFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, false)
                    )
                );
		try {
		    // qmail always lists localhost
		    if(isQmail) out.print("localhost\n");

		    for (EmailDomain domain : domains) out.println(domain.getDomain());
		} finally {
		    out.flush();
		    out.close();
		}

                // Move the old one to be backup
                if(isQmail) {
                    rcptHosts.renameTo(oldRcptHosts);
                    newRcptHosts.renameTo(rcptHosts);
                } else {
                    configFile.renameTo(backupFile);
                    newFile.renameTo(configFile);
                }

                // Reload the MTA
                reloadMTA();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object reloadLock=new Object();
    private static final String[] reloadQmailCommand={
        "/var/qmail/bin/qmailctl",
        "reload"
    };
    private static final String[] reloadSendmailCommandRedHat={
        "/usr/bin/killall",
        "-HUP",
        "sendmail"
    };
    private static final String[] reloadSendmailCommandMandrake10_1={
        "/usr/bin/killall",
        "-HUP",
        "sendmail.sendmail"
    };
    public static void reloadMTA() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailDomainManager.class, "reloadMTA()", null);
        try {
            synchronized(reloadLock) {
                if(AOServDaemon.getThisAOServer().isQmail()) {
                    AOServDaemon.exec(reloadQmailCommand);
                } else {
                    int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
                    String[] cmd;
                    //if(osv==OperatingSystemVersion.REDHAT_7_2_I686) cmd=reloadSendmailCommandRedHat7_2;
                    //else if(osv==OperatingSystemVersion.MANDRAKE_9_2_I586) cmd=reloadSendmailCommandMandrake9_2;
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                    ) {
                        cmd=reloadSendmailCommandMandrake10_1;
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        cmd=reloadSendmailCommandRedHat;
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    AOServDaemon.exec(cmd);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailDomainManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(EmailDomainManager.class) && emailDomainManager==null) {
                synchronized(System.out) {
                    if(emailDomainManager==null) {
                        System.out.print("Starting EmailDomainManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        emailDomainManager=new EmailDomainManager();
                        connector.emailDomains.addTableListener(emailDomainManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, EmailDomainManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild Email Domains";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
