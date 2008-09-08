package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2003-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.aoserv.jilter.config.JilterConfiguration;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Builds the sendmail.mc and sendmail.cf files as necessary.
 *
 * @author  AO Industries, Inc.
 */
final public class SendmailCFManager extends BuilderThread {

    private static SendmailCFManager sendmailCFManager;

    private SendmailCFManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SendmailCFManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SendmailCFManager.class, "doRebuild()", null);
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            Server server = aoServer.getServer();
            AOServConnector conn=AOServDaemon.getConnector();

            int osv=server.getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Build the new version of /etc/mail/sendmail.mc
                UnixFile newMC=new UnixFile("/etc/mail/sendmail.mc.new");
                ChainWriter out=new ChainWriter(newMC.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true));
                try {
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                        || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                    ) {
                        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            out.print("divert(-1)\n"
                                    + "dnl This is the macro config file used to generate the /etc/sendmail.cf\n"
                                    + "dnl file. If you modify the file you will have to regenerate the\n"
                                    + "dnl /etc/mail/sendmail.cf by running this macro config through the m4\n"
                                    + "dnl preprocessor:\n"
                                    + "dnl\n"
                                    + "dnl        m4 /etc/mail/sendmail.mc > /etc/mail/sendmail.cf\n"
                                    + "dnl\n"
                                    + "dnl You will need to have the sendmail-cf package installed for this to\n"
                                    + "dnl work.\n");
                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print("divert(-1)dnl\n"
                                    + "dnl #\n"
                                    + "dnl # This is the sendmail macro config file for m4. If you make changes to\n"
                                    + "dnl # /etc/mail/sendmail.mc, you will need to regenerate the\n"
                                    + "dnl # /etc/mail/sendmail.cf file by confirming that the sendmail-cf package is\n"
                                    + "dnl # installed and then performing a\n"
                                    + "dnl #\n"
                                    + "dnl #     make -C /etc/mail\n"
                                    + "dnl #\n");
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        out.print("include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
                                + "VERSIONID(`AO Industries, Inc.')dnl\n"   // AO added
                                + "define(`confDEF_USER_ID',``mail:mail'')dnl\n"
                                + "OSTYPE(`linux')dnl\n"
                                + "undefine(`UUCP_RELAY')dnl\n"
                                + "undefine(`BITNET_RELAY')dnl\n"
                                + "define(`confALIAS_WAIT', `30')dnl\n"
                                + "define(`confTO_CONNECT', `1m')dnl\n"
                                + "define(`confTRY_NULL_MX_LIST',true)dnl\n"
                                + "define(`confDONT_PROBE_INTERFACES',true)dnl\n"
                                + "define(`PROCMAIL_MAILER_PATH',`/usr/bin/procmail')dnl\n");
                        if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print("define(`ALIAS_FILE', `/etc/aliases')dnl\n"
                                    + "define(`STATUS_FILE', `/var/log/mail/statistics')dnl\n"
                                    + "define(`UUCP_MAILER_MAX', `2000000')dnl\n"
                                    + "define(`confUSERDB_SPEC', `/etc/mail/userdb.db')dnl\n"
                                    + "FEATURE(`smrsh',`/usr/sbin/smrsh')dnl\n");
                        }
                        out.print("dnl define delivery mode: interactive, background, or queued\n"
                                + "define(`confDELIVERY_MODE', `background')\n"
                                + "FEATURE(`mailertable',`hash -o /etc/mail/mailertable.db')dnl\n"
                                + "FEATURE(`virtuser_entire_domain')dnl\n"
                                + "FEATURE(`virtusertable',`hash -o /etc/mail/virtusertable.db')dnl\n"
                                + "FEATURE(redirect)dnl\n"
                                + "FEATURE(use_cw_file)dnl\n");
                        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            out.print("FEATURE(local_procmail)dnl\n");
                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print("FEATURE(local_procmail,`',`procmail -t -Y -a $h -d $u')dnl\n");
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        out.print("FEATURE(`access_db',`hash -T<TMPF> /etc/mail/access.db')dnl\n"
                                + "FEATURE(`delay_checks')dnl\n"
                                + "FEATURE(`blacklist_recipients')dnl\n"
                                + "dnl\n"
                                + "dnl Next lines are for SMTP Authentication\n"
                                + "define(`confAUTH_OPTIONS', `A y')dnl\n");
                        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            out.print("TRUST_AUTH_MECH(`GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
                                    + "define(`confAUTH_MECHANISMS', `GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n");
                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print("TRUST_AUTH_MECH(`EXTERNAL DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
                                    + "define(`confAUTH_MECHANISMS', `EXTERNAL GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n");
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        out.print("dnl\n"
                                + "dnl STARTTLS configuration\n"
                                + "dnl extract from http://www.sendmail.org/~ca/email/starttls.html\n"
                                + "dnl\n"
                                + "define(`CERT_DIR', `/etc/ssl/sendmail')dnl\n"
                                + "define(`confCACERT_PATH', `CERT_DIR')dnl\n"
                                + "define(`confCACERT', `CERT_DIR/CAcert.pem')dnl\n"
                                + "define(`confSERVER_CERT', `CERT_DIR/MYcert.pem')dnl\n"
                                + "define(`confSERVER_KEY', `CERT_DIR/MYkey.pem')dnl\n"
                                + "define(`confCLIENT_CERT', `CERT_DIR/MYcert.pem')dnl\n"
                                + "define(`confCLIENT_KEY', `CERT_DIR/MYkey.pem')dnl\n"
                                + "dnl\n"
                                + "dnl Allow relatively high load averages\n"
                                + "define(`confQUEUE_LA', `50')dnl\n"
                                + "define(`confREFUSE_LA', `80')dnl\n"
                                + "dnl\n"
                                + "dnl Do not add the hostname to incorrectly formatted headers\n"
                                + "FEATURE(`nocanonify')dnl\n"
                                + "define(`confBIND_OPTS',`-DNSRCH -DEFNAMES')dnl\n"
                                + "dnl\n"
                                + "dnl Uncomment next lines to hide identity of mail server\n"
                                + "define(`confPRIVACY_FLAGS',`authwarnings,goaway,novrfy,noexpn,restrictqrun,restrictmailq,restrictexpand')dnl\n"
                                + "dnl define(`confSMTP_LOGIN_MSG', `$j server ready at $b')dnl\n"
                                + "dnl\n"
                                + "dnl Additional features added AO Industries on 2005-04-22\n"
                                + "define(`confBAD_RCPT_THROTTLE',`10')dnl\n"
                                + "define(`confCONNECTION_RATE_THROTTLE',`100')dnl\n"
                                + "define(`confDELAY_LA',`40')dnl\n"
                                + "define(`confMAX_DAEMON_CHILDREN',`1000')dnl\n"
                                + "define(`confMAX_MESSAGE_SIZE',`100000000')dnl\n"
                                + "define(`confMAX_QUEUE_CHILDREN',`100')dnl\n"
                                + "define(`confMIN_FREE_BLOCKS',`65536')dnl\n"
                                + "define(`confNICE_QUEUE_RUN',`10')dnl\n"
                                + "define(`confPROCESS_TITLE_PREFIX',`").print(aoServer.getHostname()).print("')dnl\n"
                                + "dnl\n");
                        if(AOServDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class)) {
                            out.print("dnl Enable Jilter\n"
                                    + "dnl\n"
                                    + "INPUT_MAIL_FILTER(`jilter',`S=inet:"+JilterConfiguration.MILTER_PORT+"@").print(aoServer.getPrimaryIPAddress().getIPAddress()).print(", F=R, T=S:60s;R:60s')\n"
                                    + "dnl\n");
                        }
                        out.print("dnl Only listen to the IP addresses of this logical server\n"
                                + "dnl\n"
                                + "FEATURE(`no_default_msa')dnl\n");
                        List<String> finishedIPs=new SortedArrayList<String>();
                        List<NetBind> nbs=server.getNetBinds(conn.protocols.get(Protocol.SMTP));
                        for(NetBind nb : nbs) {
                            IPAddress ia=nb.getIPAddress();
                            String ip=ia.getIPAddress();
                            if(
                                !ip.equals(IPAddress.LOOPBACK_IP)
                                && !finishedIPs.contains(ip)
                            ) {
                                out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getHostname():ia.getHostname()).print("-MTA, Modifiers=");
                                if(ia.isWildcard()) out.print("h");
                                else out.print("bh");
                                out.print("')dnl\n"); // AO added
                                finishedIPs.add(ip);
                            }
                        }
                        finishedIPs.clear();
                        nbs=server.getNetBinds(conn.protocols.get(Protocol.SMTPS));
                        for(NetBind nb : nbs) {
                            IPAddress ia=nb.getIPAddress();
                            String ip=ia.getIPAddress();
                            if(
                                !ip.equals(IPAddress.LOOPBACK_IP)
                                && !finishedIPs.contains(ip)
                            ) {
                                out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getHostname():ia.getHostname()).print("-TLSMSA, Modifiers=");
                                if(ia.isWildcard()) out.print("hs");
                                else out.print("bhs");
                                out.print("')dnl\n"); // AO added
                                finishedIPs.add(ip);
                            }
                        }
                        finishedIPs.clear();
                        nbs=server.getNetBinds(conn.protocols.get(Protocol.SUBMISSION));
                        for(NetBind nb : nbs) {
                            IPAddress ia=nb.getIPAddress();
                            String ip=ia.getIPAddress();
                            if(
                                !ip.equals(IPAddress.LOOPBACK_IP)
                                && !finishedIPs.contains(ip)
                            ) {
                                out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getHostname():ia.getHostname()).print("-MSA, Modifiers=");
                                if(ia.isWildcard()) out.print("Eh");
                                else out.print("Ebh");
                                out.print("')dnl\n"); // AO added
                                finishedIPs.add(ip);
                            }
                        }
                        out.print("dnl\n"
                                + "dnl Enable IDENT lookups\n"
                                // TO_IDENT set to 10s was causing normally 1 second email to become 30 second email on www.keepandshare.com
                                + "define(`confTO_IDENT',`0s')dnl\n");
                        if(aoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
                            out.print("MODIFY_MAILER_FLAGS(`SMTP',`+R')dnl\n"
                                    + "MODIFY_MAILER_FLAGS(`ESMTP',`+R')dnl\n"
                                    + "MODIFY_MAILER_FLAGS(`SMTP8',`+R')dnl\n"
                                    + "MODIFY_MAILER_FLAGS(`DSMTP',`+R')dnl\n");
                        }
                        out.print("MAILER(smtp)dnl\n"
                                + "MAILER(procmail)dnl\n"
                                + "Dj").print(aoServer.getHostname()).print("\n" // AO added
                                + "\n"
                        );
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                } finally {
                    out.flush();
                    out.close();
                }

                // Activate the new file if it is different than the old
                UnixFile mc=new UnixFile("/etc/mail/sendmail.mc");
                if(!newMC.contentEquals(mc)) {
                    newMC.renameTo(mc);
                    
                    // Rebuild the /etc/sendmail.cf file
                    String[] command;
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                    ) {
                        command=new String[] {"/usr/aoserv/daemon/bin/create_sendmail.cf.new"};
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        command=new String[] {"/opt/aoserv-daemon/bin/create_sendmail.cf.new"};
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    AOServDaemon.exec(command);
                    UnixFile newCF;
                    UnixFile oldCF;
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                        || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                    ) {
                        newCF=new UnixFile("/etc/mail/sendmail.cf.new");
                        oldCF=new UnixFile("/etc/mail/sendmail.cf");
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    if(!newCF.contentEquals(oldCF)) {
                        newCF.renameTo(oldCF);

                        /*if(!aoServer.isQmail())*/ reloadSendmail();
                    } else newCF.delete();
                } else newMC.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SendmailCFManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(SendmailCFManager.class) && sendmailCFManager==null) {
                synchronized(System.out) {
                    if(sendmailCFManager==null) {
                        System.out.print("Starting SendmailCFManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        sendmailCFManager=new SendmailCFManager();
                        connector.ipAddresses.addTableListener(sendmailCFManager, 0);
                        connector.netBinds.addTableListener(sendmailCFManager, 0);
                        connector.aoServers.addTableListener(sendmailCFManager, 0);
                        connector.serverFarms.addTableListener(sendmailCFManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SendmailCFManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild sendmail.cf";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    private static final String[] reloadCommand={
        "/etc/rc.d/init.d/sendmail",
        "reload"
    };

    private static final Object sendmailLock=new Object();
    public static void reloadSendmail() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, SendmailCFManager.class, "reloadSendmail()", null);
        try {
            synchronized(sendmailLock) {
                AOServDaemon.exec(reloadCommand);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
