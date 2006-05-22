package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2003-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
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
            AOServConnector conn=AOServDaemon.getConnector();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Build the new version of /etc/mail/sendmail.mc
                UnixFile newMC=new UnixFile("/etc/mail/sendmail.mc.new");
                ChainWriter out=new ChainWriter(newMC.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true));
                try {
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    ) {
                        out.print("divert(-1)\n"
                                + "dnl This is the macro config file used to generate the /etc/sendmail.cf\n"
                                + "dnl file. If you modify the file you will have to regenerate the\n"
                                + "dnl /etc/mail/sendmail.cf by running this macro config through the m4\n"
                                + "dnl preprocessor:\n"
                                + "dnl\n"
                                + "dnl        m4 /etc/mail/sendmail.mc > /etc/mail/sendmail.cf\n"
                                + "dnl\n"
                                + "dnl You will need to have the sendmail-cf package installed for this to\n"
                                + "dnl work.\n"
                                + "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
                                + "VERSIONID(`AO Industries, Inc.')dnl\n"   // AO added
                                + "define(`confDEF_USER_ID',``mail:mail'')dnl\n"
                                + "OSTYPE(`linux')dnl\n"
                                + "undefine(`UUCP_RELAY')dnl\n"
                                + "undefine(`BITNET_RELAY')dnl\n"
                                + "define(`confALIAS_WAIT', `30')dnl\n"
                                + "define(`confTO_CONNECT', `1m')dnl\n"
                                + "define(`confTRY_NULL_MX_LIST',true)dnl\n"
                                + "define(`confDONT_PROBE_INTERFACES',true)dnl\n"
                                + "define(`PROCMAIL_MAILER_PATH',`/usr/bin/procmail')dnl\n"
                                + "dnl define delivery mode: interactive, background, or queued\n"
                                // + "dnl define(`confDELIVERY_MODE', `i')\n" // AO removed
                                + "define(`confDELIVERY_MODE', `background')\n" // AO added
                                // + "MASQUERADE_AS(`localhost.localdomain')dnl\n" // AO removed
                                // + "FEATURE(`limited_masquerade')dnl\n" // AO removed
                                // + "FEATURE(`masquerade_envelope')dnl\n" // AO removed
                                // + "FEATURE(`smrsh',`/usr/sbin/smrsh')dnl\n" // AO removed
                                + "FEATURE(mailertable)dnl\n"
                                + "dnl virtusertable: redirect incoming mail to virtual domain to particular user or domain\n"
                                + "FEATURE(`virtusertable',`hash -o /etc/mail/virtusertable')dnl\n"
                                // + "dnl genericstable: rewrite sender address for outgoing mail\n" // AO removed
                                // + "FEATURE(genericstable)dnl\n" // AO removed
                                // + "FEATURE(always_add_domain)dnl\n" // AO removed
                                + "FEATURE(redirect)dnl\n"
                                + "FEATURE(use_cw_file)dnl\n"
                                + "FEATURE(local_procmail)dnl\n"
                                // + "FEATURE(`access_db')dnl\n" // AO removed
                                + "FEATURE(`access_db',`btree -T<TMPF> /etc/mail/access.db')dnl\n" // AO added
                                + "FEATURE(`blacklist_recipients')dnl\n"
                                // + "FEATURE(`relay_based_on_MX')dnl\n" // AO removed
                                // Was using dsbl, ordb, and spamhaus
                                // + "dnl FEATURE(dnsbl, `blackholes.mail-abuse.org', `Rejected - see  http://www.mail-abuse.org/rbl/')dnl\n" // AO removed
                                // + "dnl FEATURE(dnsbl, `dialups.mail-abuse.org', `Dialup - see http://www.mail-abuse.org/dul/')dnl\n" // AO removed
                                // + "dnl FEATURE(dnsbl, `relays.mail-abuse.org', `Open spam relay - see http://www.mail-abuse.org/rss/')dnl\n" // AO removed
                                + "dnl FEATURE(dnsbl, `relays.ordb.org', ` Mail from $&{client_addr} rejected;\\ see http://www.ordb.org/faq/\\#why_rejected')dnl\n"
                                + "dnl FEATURE(dnsbl,`sbl.spamhaus.org',` Mail from $&{client_addr} rejected;\\ see http://www.spamhaus.org/sbl')dnl\n"
                                + "dnl FEATURE(dnsbl,`list.dsbl.org',` Mail from $&{client_addr} rejected;\\ see http://www.dsbl.org/sender')dnl\n"
                                // + "FEATURE(`delay_checks')dnl\n" // AO removed
                                // + "FEATURE(`stickyhost')dnl\n" // AO removed
                                + "dnl SASL Configuration\n"
                                + "dnl extract from http://www.sendmail.org/~ca/email/auth.html\n"
                                + "dnl\n"
                                + "dnl Next lines are for SMTP Authentication\n"
                                + "define(`confAUTH_OPTIONS', `A y')dnl\n" // AO added
                                //+ "TRUST_AUTH_MECH(`LOGIN PLAIN')dnl\n" // AO removed
                                + "TRUST_AUTH_MECH(`GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n" // AO added
                                //+ "define(`confAUTH_MECHANISMS', `LOGIN PLAIN')dnl\n" // AO removed
                                + "define(`confAUTH_MECHANISMS', `GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n" // AO added
                                + "dnl\n"
                                //+ "dnl Next line stops sendmail from allowing auth without encryption\n" // AO removed
                                //+ "define(`confAUTH_OPTIONS', `A p y')dnl\n" // AO removed
                                + "dnl\n"
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
                                + "dnl Allow relatively high load averages\n" // AO added
                                + "dnl\n" // AO added
                                + "define(`confQUEUE_LA', `50')dnl\n" // AO added
                                + "define(`confREFUSE_LA', `80')dnl\n" // AO added
                                + "dnl\n" // AO added
                                + "dnl Do not add the hostname to incorrectly formatted headers\n" // AO added
                                + "dnl\n" // AO added
                                + "FEATURE(`nocanonify')dnl\n" // AO added
                                + "define(`confBIND_OPTS',`-DNSRCH -DEFNAMES')dnl\n" // AO added
                                + "dnl\n" // AO added
                                // + "dnl Uncomment next lines to hide identity of mail serve\n" // AO removed
                                + "dnl Uncomment next lines to hide identity of mail server\n" // AO added
                                // + "define(`confPRIVACY_FLAGS',`goaway,restrictqrun,restrictmailq')dnl\n" // AO removed
                                // + "define(`confPRIVACY_FLAGS',`authwarnings,goaway,novrfy,noexpn,restrictqrun,restrictmailq')dnl\n" // AO removed on 2005-04-14
                                + "define(`confPRIVACY_FLAGS',`authwarnings,goaway,novrfy,noexpn,restrictqrun,restrictmailq,restrictexpand')dnl\n" // AO added on 2005-04-14
                                + "dnl define(`confSMTP_LOGIN_MSG', `$j server ready at $b')dnl\n"
                                + "dnl Additional features added AO Industries on 2005-04-22\n"
                                + "define(`confBAD_RCPT_THROTTLE',`10')dnl\n"
                                + "define(`confCONNECTION_RATE_THROTTLE',`100')dnl\n"
                                + "define(`confDELAY_LA',`40')dnl\n"
                                + "define(`confDONT_PROBE_INTERFACES',`true')dnl\n"
                                + "define(`confMAX_DAEMON_CHILDREN',`1000')dnl\n"
                                + "define(`confMAX_MESSAGE_SIZE',`100000000')dnl\n"
                                + "define(`confMAX_QUEUE_CHILDREN',`100')dnl\n"
                                + "define(`confMIN_FREE_BLOCKS',`65536')dnl\n"
                                + "define(`confNICE_QUEUE_RUN',`10')dnl\n"
                                + "define(`confPROCESS_TITLE_PREFIX',`").print(aoServer.getServer().getHostname()).print("')dnl\n"
                                + "dnl\n"
                                + "dnl Only listen to the IP addresses of this logical server\n"
                                + "dnl\n"
                                + "FEATURE(`no_default_msa')dnl\n"); // AO added
                        List<String> finishedIPs=new SortedArrayList<String>();
                        List<NetBind> nbs=aoServer.getNetBinds(conn.protocols.get(Protocol.SMTP));
                        for(NetBind nb : nbs) {
                            IPAddress ia=nb.getIPAddress();
                            String ip=ia.getIPAddress();
                            if(
                                !ip.equals(IPAddress.LOOPBACK_IP)
                                && !finishedIPs.contains(ip)
                            ) {
                                out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getServer().getHostname():ia.getHostname()).print("-MTA, Modifiers=");
                                if(ia.isWildcard()) out.print("h");
                                else out.print("bh");
                                out.print("')\n"); // AO added
                                finishedIPs.add(ip);
                            }
                        }
                        finishedIPs.clear();
                        nbs=aoServer.getNetBinds(conn.protocols.get(Protocol.SUBMISSION));
                        for(NetBind nb : nbs) {
                            IPAddress ia=nb.getIPAddress();
                            String ip=ia.getIPAddress();
                            if(
                                !ip.equals(IPAddress.LOOPBACK_IP)
                                && !finishedIPs.contains(ip)
                            ) {
                                out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getServer().getHostname():ia.getHostname()).print("-MSA, Modifiers=");
                                if(ia.isWildcard()) out.print("Eh");
                                else out.print("Ebh");
                                out.print("')\n"); // AO added
                                finishedIPs.add(ip);
                            }
                        }
                        out.print("dnl\n"
                                + "dnl Disable IDENT lookups\n"
                                + "define(`confTO_IDENT',`0s')dnl\n"
                                + "MAILER(smtp)dnl\n"
                                + "MAILER(procmail)dnl\n"
                                + "Dj").print(aoServer.getServer().getHostname()).print("\n" // AO added
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
                    AOServDaemon.exec(new String[] {"/usr/aoserv/daemon/bin/create_sendmail.cf.new"});
                    UnixFile newCF;
                    UnixFile oldCF;
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    ) {
                        newCF=new UnixFile("/etc/mail/sendmail.cf.new");
                        oldCF=new UnixFile("/etc/mail/sendmail.cf");
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    if(!newCF.contentEquals(oldCF)) {
                        newCF.renameTo(oldCF);

                        if(!aoServer.isQmail()) reloadSendmail();
                    }
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
