/*
 * Copyright 2003-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.ServerFarm;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Builds the sendmail.mc and sendmail.cf files as necessary.
 *
 * TODO: SELinux to support nonstandard ports.
 *
 * @author  AO Industries, Inc.
 */
final public class SendmailCFManager extends BuilderThread {

	private static SendmailCFManager sendmailCFManager;

	private static final UnixFile
		sendmailMc = new UnixFile("/etc/mail/sendmail.mc"),
		sendmailCf = new UnixFile("/etc/mail/sendmail.cf"),
		submitMc = new UnixFile("/etc/mail/submit.mc"),
		submitCf = new UnixFile("/etc/mail/submit.cf")
	;

	private static final File subsysLockFile = new File("/var/lock/subsys/sendmail");

	private static final UnixFile sendmailRcFile = new UnixFile("/etc/rc.d/rc3.d/S80sendmail");

	private SendmailCFManager() {
	}

	/**
	 * Builds the config for Mandriva 2006.
	 */
	private static void buildSendmailMcMandriva2006(
		ChainWriter out,
		AOServer thisAoServer,
		List<NetBind> smtpNetBinds,
		List<NetBind> smtpsNetBinds,
		List<NetBind> submissionNetBinds
	) throws IOException, SQLException {
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
				+ "VERSIONID(`AOServ Platform')dnl\n" // AO added
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
				+ "define(`confDELIVERY_MODE', `background')\n"
				+ "FEATURE(`mailertable',`hash -o /etc/mail/mailertable.db')dnl\n"
				+ "FEATURE(`virtuser_entire_domain')dnl\n"
				+ "FEATURE(`virtusertable',`hash -o /etc/mail/virtusertable.db')dnl\n"
				+ "FEATURE(redirect)dnl\n"
				+ "FEATURE(use_cw_file)dnl\n"
				+ "FEATURE(local_procmail)dnl\n"
				+ "FEATURE(`access_db',`hash -T<TMPF> /etc/mail/access.db')dnl\n"
				+ "FEATURE(`delay_checks')dnl\n"
				+ "FEATURE(`blacklist_recipients')dnl\n"
				+ "dnl\n"
				+ "dnl Next lines are for SMTP Authentication\n"
				+ "define(`confAUTH_OPTIONS', `A y')dnl\n"
				+ "TRUST_AUTH_MECH(`GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
				+ "define(`confAUTH_MECHANISMS', `GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
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
				+ "define(`confPROCESS_TITLE_PREFIX',`").print(thisAoServer.getHostname()).print("')dnl\n"
				+ "dnl\n");
		// Look for the configured net bind for the jilter
		NetBind jilterNetBind = JilterConfigurationWriter.getJilterNetBind();
		// Only configure when the net bind has been found
		if(jilterNetBind != null) {
			out.print("dnl Enable Jilter\n"
					+ "dnl\n");
			InetAddress ip = jilterNetBind.getIPAddress().getInetAddress();
			if(ip.isUnspecified()) ip = thisAoServer.getPrimaryIPAddress().getInetAddress();
			out
				.print("INPUT_MAIL_FILTER(`jilter',`S=")
				.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
				.print(':')
				.print(jilterNetBind.getPort().getPort()).print('@').print(ip).print(", F=R, T=S:60s;R:60s')\n"
					+ "dnl\n");
		}
		out.print("dnl Only listen to the IP addresses of this logical server\n"
				+ "dnl\n"
				+ "FEATURE(`no_default_msa')dnl\n");
		Set<InetAddress> finishedIPs = new HashSet<>();
		for(NetBind nb : smtpNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MTA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("h");
				else out.print("bh");
				out.print("')dnl\n"); // AO added
			}
		}
		finishedIPs.clear();
		for(NetBind nb : smtpsNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-TLSMSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("hs");
				else out.print("bhs");
				out.print("')dnl\n"); // AO added
			}
		}
		finishedIPs.clear();
		for(NetBind nb : submissionNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("Eh");
				else out.print("Ebh");
				out.print("')dnl\n"); // AO added
			}
		}
		out.print("dnl\n"
				+ "dnl Enable IDENT lookups\n"
				// TO_IDENT set to 10s was causing normally 1 second email to become 30 second email on www.keepandshare.com
				+ "define(`confTO_IDENT',`0s')dnl\n");
		if(thisAoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
			out.print("MODIFY_MAILER_FLAGS(`SMTP',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`ESMTP',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`SMTP8',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`DSMTP',`+R')dnl\n");
		}
		out.print("MAILER(smtp)dnl\n"
				+ "MAILER(procmail)dnl\n"
				+ "LOCAL_CONFIG\n"
				// From http://serverfault.com/questions/700655/sendmail-rejecting-some-connections-with-handshake-failure-ssl-alert-number-40
				+ "O CipherList=HIGH:!ADH\n"
				+ "O DHParameters=/etc/ssl/sendmail/dhparams.pem\n"
				+ "O ServerSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3 +SSL_OP_CIPHER_SERVER_PREFERENCE\n"
				+ "O ClientSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3\n"
				// Add envelop header recipient
				+ "H?m?X-RCPT-To: $u\n"
				+ "Dj").print(thisAoServer.getHostname()).print("\n" // AO added
				+ "\n"
		);
	}

	/**
	 * Builds the config for RedHat ES 4.
	 */
	private static void buildSendmailMcRedHatES4(
		ChainWriter out,
		AOServer thisAoServer,
		List<NetBind> smtpNetBinds,
		List<NetBind> smtpsNetBinds,
		List<NetBind> submissionNetBinds
	) throws IOException, SQLException {
		out.print("divert(-1)dnl\n"
				+ "dnl #\n"
				+ "dnl # This is the sendmail macro config file for m4. If you make changes to\n"
				+ "dnl # /etc/mail/sendmail.mc, you will need to regenerate the\n"
				+ "dnl # /etc/mail/sendmail.cf file by confirming that the sendmail-cf package is\n"
				+ "dnl # installed and then performing a\n"
				+ "dnl #\n"
				+ "dnl #     make -C /etc/mail\n"
				+ "dnl #\n"
				+ "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
				+ "VERSIONID(`AOServ Platform')dnl\n" // AO added
				+ "define(`confDEF_USER_ID',``mail:mail'')dnl\n"
				+ "OSTYPE(`linux')dnl\n"
				+ "undefine(`UUCP_RELAY')dnl\n"
				+ "undefine(`BITNET_RELAY')dnl\n"
				+ "define(`confALIAS_WAIT', `30')dnl\n"
				+ "define(`confTO_CONNECT', `1m')dnl\n"
				+ "define(`confTRY_NULL_MX_LIST',true)dnl\n"
				+ "define(`confDONT_PROBE_INTERFACES',true)dnl\n"
				+ "define(`PROCMAIL_MAILER_PATH',`/usr/bin/procmail')dnl\n"
				+ "define(`ALIAS_FILE', `/etc/aliases')dnl\n"
				+ "define(`STATUS_FILE', `/var/log/mail/statistics')dnl\n"
				+ "define(`UUCP_MAILER_MAX', `2000000')dnl\n"
				+ "define(`confUSERDB_SPEC', `/etc/mail/userdb.db')dnl\n"
				+ "FEATURE(`smrsh',`/usr/sbin/smrsh')dnl\n"
				+ "dnl define delivery mode: interactive, background, or queued\n"
				+ "define(`confDELIVERY_MODE', `background')\n"
				+ "FEATURE(`mailertable',`hash -o /etc/mail/mailertable.db')dnl\n"
				+ "FEATURE(`virtuser_entire_domain')dnl\n"
				+ "FEATURE(`virtusertable',`hash -o /etc/mail/virtusertable.db')dnl\n"
				+ "FEATURE(redirect)dnl\n"
				+ "FEATURE(use_cw_file)dnl\n"
				+ "FEATURE(local_procmail,`',`procmail -t -Y -a $h -d $u')dnl\n"
				+ "FEATURE(`access_db',`hash -T<TMPF> /etc/mail/access.db')dnl\n"
				+ "FEATURE(`delay_checks')dnl\n"
				+ "FEATURE(`blacklist_recipients')dnl\n"
				+ "dnl\n"
				+ "dnl Next lines are for SMTP Authentication\n"
				+ "define(`confAUTH_OPTIONS', `A y')dnl\n"
				+ "TRUST_AUTH_MECH(`EXTERNAL DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
				+ "define(`confAUTH_MECHANISMS', `EXTERNAL GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
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
				+ "define(`confPROCESS_TITLE_PREFIX',`").print(thisAoServer.getHostname()).print("')dnl\n"
				+ "dnl\n");
		// Look for the configured net bind for the jilter
		NetBind jilterNetBind = JilterConfigurationWriter.getJilterNetBind();
		// Only configure when the net bind has been found
		if(jilterNetBind != null) {
			out.print("dnl Enable Jilter\n"
					+ "dnl\n");
			InetAddress ip = jilterNetBind.getIPAddress().getInetAddress();
			if(ip.isUnspecified()) ip = thisAoServer.getPrimaryIPAddress().getInetAddress();
			out
				.print("INPUT_MAIL_FILTER(`jilter',`S=")
				.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
				.print(':')
				.print(jilterNetBind.getPort().getPort()).print('@').print(ip).print(", F=R, T=S:60s;R:60s')\n"
					+ "dnl\n");
		}
		out.print("dnl Only listen to the IP addresses of this logical server\n"
				+ "dnl\n"
				+ "FEATURE(`no_default_msa')dnl\n");
		Set<InetAddress> finishedIPs = new HashSet<>();
		for(NetBind nb : smtpNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MTA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("h");
				else out.print("bh");
				out.print("')dnl\n"); // AO added
			}
		}
		finishedIPs.clear();
		for(NetBind nb : smtpsNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-TLSMSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("hs");
				else out.print("bhs");
				out.print("')dnl\n"); // AO added
			}
		}
		finishedIPs.clear();
		for(NetBind nb : submissionNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("Eh");
				else out.print("Ebh");
				out.print("')dnl\n"); // AO added
			}
		}
		out.print("dnl\n"
				+ "dnl Enable IDENT lookups\n"
				// TO_IDENT set to 10s was causing normally 1 second email to become 30 second email on www.keepandshare.com
				+ "define(`confTO_IDENT',`0s')dnl\n");
		if(thisAoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
			out.print("MODIFY_MAILER_FLAGS(`SMTP',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`ESMTP',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`SMTP8',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`DSMTP',`+R')dnl\n");
		}
		out.print("MAILER(smtp)dnl\n"
				+ "MAILER(procmail)dnl\n"
				+ "LOCAL_CONFIG\n"
				// From http://serverfault.com/questions/700655/sendmail-rejecting-some-connections-with-handshake-failure-ssl-alert-number-40
				+ "O CipherList=HIGH:!ADH\n"
				+ "O DHParameters=/etc/ssl/sendmail/dhparams.pem\n"
				+ "O ServerSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3 +SSL_OP_CIPHER_SERVER_PREFERENCE\n"
				+ "O ClientSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3\n"
				// Add envelop header recipient
				+ "H?m?X-RCPT-To: $u\n"
				+ "Dj").print(thisAoServer.getHostname()).print("\n" // AO added
				+ "\n"
		);
	}

	/**
	 * Builds the config for CentOS 5.
	 */
	private static void buildSendmailMcCentOS5(
		ChainWriter out,
		AOServer thisAoServer,
		List<NetBind> smtpNetBinds,
		List<NetBind> smtpsNetBinds,
		List<NetBind> submissionNetBinds
	) throws IOException, SQLException {
		out.print("divert(-1)dnl\n"
				+ "dnl #\n"
				+ "dnl # Generated by ").print(SendmailCFManager.class.getName()).print("\n"
				+ "dnl #\n"
				+ "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
				+ "VERSIONID(`AOServ Platform')dnl\n" // AO added
				+ "define(`confDEF_USER_ID',``mail:mail'')dnl\n"
				+ "OSTYPE(`linux')dnl\n"
				+ "undefine(`UUCP_RELAY')dnl\n"
				+ "undefine(`BITNET_RELAY')dnl\n"
				+ "define(`confALIAS_WAIT', `30')dnl\n"
				+ "define(`confTO_CONNECT', `1m')dnl\n"
				+ "define(`confTRY_NULL_MX_LIST', `True')dnl\n"
				+ "define(`confDONT_PROBE_INTERFACES', `True')dnl\n"
				+ "define(`PROCMAIL_MAILER_PATH',`/usr/bin/procmail')dnl\n"
				+ "define(`ALIAS_FILE', `/etc/aliases')dnl\n"
				+ "define(`STATUS_FILE', `/var/log/mail/statistics')dnl\n"
				+ "define(`UUCP_MAILER_MAX', `2000000')dnl\n"
				+ "define(`confUSERDB_SPEC', `/etc/mail/userdb.db')dnl\n"
				+ "FEATURE(`smrsh',`/usr/sbin/smrsh')dnl\n"
				+ "dnl define delivery mode: interactive, background, or queued\n"
				+ "define(`confDELIVERY_MODE', `background')\n"
				+ "FEATURE(`mailertable',`hash -o /etc/mail/mailertable.db')dnl\n"
				+ "FEATURE(`virtuser_entire_domain')dnl\n"
				+ "FEATURE(`virtusertable',`hash -o /etc/mail/virtusertable.db')dnl\n"
				+ "FEATURE(redirect)dnl\n"
				+ "FEATURE(use_cw_file)dnl\n"
				+ "FEATURE(local_procmail,`',`procmail -t -Y -a $h -d $u')dnl\n"
				+ "FEATURE(`access_db',`hash -T<TMPF> -o /etc/mail/access.db')dnl\n"
				+ "FEATURE(`delay_checks')dnl\n"
				+ "FEATURE(`blacklist_recipients')dnl\n"
				+ "dnl\n"
				+ "dnl Next lines are for SMTP Authentication\n"
				+ "define(`confAUTH_OPTIONS', `A y')dnl\n"
				+ "TRUST_AUTH_MECH(`EXTERNAL DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
				+ "define(`confAUTH_MECHANISMS', `EXTERNAL GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
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
				+ "dnl Do not advertize sendmail version.\n"
				+ "define(`confSMTP_LOGIN_MSG', `$j Sendmail; $b')dnl\n"
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
				+ "define(`confPROCESS_TITLE_PREFIX',`").print(thisAoServer.getHostname()).print("')dnl\n"
				+ "dnl\n");
		// Look for the configured net bind for the jilter
		NetBind jilterNetBind = JilterConfigurationWriter.getJilterNetBind();
		// Only configure when the net bind has been found
		if(jilterNetBind != null) {
			out.print("dnl Enable Jilter\n"
					+ "dnl\n");
			InetAddress ip = jilterNetBind.getIPAddress().getInetAddress();
			if(ip.isUnspecified()) ip = thisAoServer.getPrimaryIPAddress().getInetAddress();
			out
				.print("INPUT_MAIL_FILTER(`jilter',`S=")
				.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
				.print(':')
				.print(jilterNetBind.getPort().getPort()).print('@').print(ip).print(", F=R, T=S:60s;R:60s')\n"
					+ "dnl\n");
		}
		out.print("dnl Only listen to the IP addresses of this logical server\n"
				+ "dnl\n"
				+ "FEATURE(`no_default_msa')dnl\n");
		Set<InetAddress> finishedIPs = new HashSet<>();
		for(NetBind nb : smtpNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MTA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("h");
				else out.print("bh");
				out.print("')dnl\n"); // AO added
			}
		}
		finishedIPs.clear();
		for(NetBind nb : smtpsNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-TLSMSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("hs");
				else out.print("bhs");
				out.print("')dnl\n"); // AO added
			}
		}
		finishedIPs.clear();
		for(NetBind nb : submissionNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("Eah");
				else out.print("Eabh");
				out.print("')dnl\n"); // AO added
			}
		}
		out.print("dnl\n"
				+ "dnl Enable IDENT lookups\n"
				// TO_IDENT set to 10s was causing normally 1 second email to become 30 second email on www.keepandshare.com
				+ "define(`confTO_IDENT',`0s')dnl\n"
		/*
			We are now blocking using egress filtering with iptables in /etc/opt/aoserv-daemon/route.
			This means no special interaction with the firewalls - no outgoing NAT.
			A local root compromise could still bypass aoserv-jilter and send spam, but this was true before.

		if(thisAoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
			out.print("MODIFY_MAILER_FLAGS(`SMTP',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`ESMTP',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`SMTP8',`+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`DSMTP',`+R')dnl\n");
		}
		 */
				+ "MAILER(smtp)dnl\n"
				+ "MAILER(procmail)dnl\n"
				+ "LOCAL_CONFIG\n"
				// From http://serverfault.com/questions/700655/sendmail-rejecting-some-connections-with-handshake-failure-ssl-alert-number-40
				+ "O CipherList=HIGH:!ADH\n"
				+ "O DHParameters=/etc/ssl/sendmail/dhparams.pem\n"
				+ "O ServerSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3 +SSL_OP_CIPHER_SERVER_PREFERENCE\n"
				+ "O ClientSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3\n"
				// Add envelop header recipient
				+ "H?m?X-RCPT-To: $u\n"
				+ "Dj").print(thisAoServer.getHostname()).print("\n" // AO added
				+ "\n"
		);
	}

	/**
	 * Builds the config for CentOS 7.
	 */
	private static void buildSendmailMcCentOS7(
		ChainWriter out,
		AOServer thisAoServer,
		List<NetBind> smtpNetBinds,
		List<NetBind> smtpsNetBinds,
		List<NetBind> submissionNetBinds
	) throws IOException, SQLException {
		out.print("divert(-1)dnl\n"
				+ "dnl #\n"
				+ "dnl # Generated by ").print(SendmailCFManager.class.getName()).print("\n"
				+ "dnl #\n"
				+ "dnl # This is the sendmail macro config file for m4. If you make changes to\n"
				+ "dnl # /etc/mail/sendmail.mc, you will need to regenerate the\n"
				+ "dnl # /etc/mail/sendmail.cf file by confirming that the sendmail-cf package is\n"
				+ "dnl # installed and then performing a\n"
				+ "dnl #\n"
				+ "dnl #     /etc/mail/make\n"
				+ "dnl #\n"
				+ "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
				+ "VERSIONID(`AOServ Platform')dnl\n" // AO added
				+ "OSTYPE(`linux')dnl\n"
				+ "dnl #\n"
				+ "dnl # Disable unused relays.\n"
				+ "dnl #\n"
				+ "undefine(`UUCP_RELAY')dnl\n"
				+ "undefine(`BITNET_RELAY')dnl\n"
				+ "dnl #\n"
				+ "dnl # Do not advertize sendmail version.\n"
				+ "dnl #\n"
				+ "define(`confSMTP_LOGIN_MSG', `$j Sendmail; $b')dnl\n"
				+ "dnl #\n"
				+ "dnl # default logging level is 9, you might want to set it higher to\n"
				+ "dnl # debug the configuration\n"
				+ "dnl #\n"
				+ "dnl define(`confLOG_LEVEL', `9')dnl\n"
				+ "dnl #\n"
				+ "dnl # Uncomment and edit the following line if your outgoing mail needs to\n"
				+ "dnl # be sent out through an external mail server:\n"
				+ "dnl #\n"
				+ "dnl define(`SMART_HOST', `smtp.your.provider')dnl\n"
				+ "dnl #\n"
				+ "define(`confDEF_USER_ID', ``8:12'')dnl\n"
				+ "dnl define(`confAUTO_REBUILD')dnl\n"
				+ "define(`confTO_CONNECT', `1m')dnl\n"
				+ "define(`confTRY_NULL_MX_LIST', `True')dnl\n"
				+ "define(`confDONT_PROBE_INTERFACES', `True')dnl\n"
				+ "define(`PROCMAIL_MAILER_PATH', `/usr/bin/procmail')dnl\n"
				+ "define(`ALIAS_FILE', `/etc/aliases')dnl\n"
				+ "define(`STATUS_FILE', `/var/log/mail/statistics')dnl\n"
				+ "define(`UUCP_MAILER_MAX', `2000000')dnl\n"
				+ "define(`confUSERDB_SPEC', `/etc/mail/userdb.db')dnl\n"
				//+ "define(`confPRIVACY_FLAGS', `authwarnings,novrfy,noexpn,restrictqrun')dnl\n"
				+ "define(`confPRIVACY_FLAGS', `authwarnings,goaway,novrfy,noexpn,restrictqrun,restrictmailq,restrictexpand')dnl\n" // AO Modified
				+ "define(`confAUTH_OPTIONS', `A y')dnl\n" // AO modified from `A'
				+ "dnl #\n"
				+ "dnl # The following allows relaying if the user authenticates, and disallows\n"
				+ "dnl # plaintext authentication (PLAIN/LOGIN) on non-TLS links\n"
				+ "dnl #\n"
				+ "dnl define(`confAUTH_OPTIONS', `A p')dnl\n"
				+ "dnl # \n"
				+ "dnl # PLAIN is the preferred plaintext authentication method and used by\n"
				+ "dnl # Mozilla Mail and Evolution, though Outlook Express and other MUAs do\n"
				+ "dnl # use LOGIN. Other mechanisms should be used if the connection is not\n"
				+ "dnl # guaranteed secure.\n"
				+ "dnl # Please remember that saslauthd needs to be running for AUTH. \n"
				+ "dnl #\n"
				//+ "dnl TRUST_AUTH_MECH(`EXTERNAL DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
				+ "TRUST_AUTH_MECH(`EXTERNAL LOGIN PLAIN')dnl\n" // AO Enabled and modified since using pam no sasldb
				//+ "dnl define(`confAUTH_MECHANISMS', `EXTERNAL GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
				+ "define(`confAUTH_MECHANISMS', `EXTERNAL LOGIN PLAIN')dnl\n" // AO Enabled and modified since using pam no sasldb
				+ "dnl #\n"
				+ "dnl # Rudimentary information on creating certificates for sendmail TLS:\n"
				+ "dnl #     cd /etc/pki/tls/certs; make sendmail.pem\n"
				+ "dnl # Complete usage:\n"
				+ "dnl #     make -C /etc/pki/tls/certs usage\n"
				+ "dnl #\n"
				+ "define(`confCACERT_PATH', `/etc/pki/tls/certs')dnl\n"
				+ "define(`confCACERT', `/etc/pki/tls/certs/ca-bundle.crt')dnl\n"
				+ "define(`confSERVER_CERT', `/etc/pki/sendmail/sendmail.pem')dnl\n"
				+ "define(`confSERVER_KEY', `/etc/pki/sendmail/sendmail.pem')dnl\n"
				+ "dnl #\n"
				+ "dnl # Do not add the hostname to incorrectly formatted headers\n"
				+ "dnl #\n"
				+ "FEATURE(`nocanonify')dnl\n"
				+ "define(`confBIND_OPTS', `-DNSRCH -DEFNAMES')dnl\n"
				+ "dnl #\n"
				+ "dnl # This allows sendmail to use a keyfile that is shared with OpenLDAP's\n"
				+ "dnl # slapd, which requires the file to be readble by group ldap\n"
				+ "dnl #\n"
				+ "dnl define(`confDONT_BLAME_SENDMAIL', `groupreadablekeyfile')dnl\n"
				+ "dnl #\n"
				+ "dnl # Queue control.\n"
				+ "dnl #\n"
				+ "dnl define(`confTO_QUEUEWARN', `4h')dnl\n"
				+ "dnl define(`confTO_QUEUERETURN', `5d')dnl\n"
				+ "define(`confMAX_QUEUE_CHILDREN', `100')dnl\n"
				+ "define(`confNICE_QUEUE_RUN', `10')dnl\n"
				+ "dnl #\n"
				+ "dnl # Allow relatively high load averages\n"
				+ "dnl #\n"
				+ "define(`confDELAY_LA', `40')dnl\n" // AO Added
				+ "define(`confQUEUE_LA', `50')dnl\n" // AO Enabled and modified from `12'
				+ "define(`confREFUSE_LA', `80')dnl\n" // AO Enabled and modified from `18'
				+ "dnl #\n"
				+ "dnl # Disable IDENT\n"
				+ "dnl #\n"
				+ "define(`confTO_IDENT', `0')dnl\n"
				+ "dnl #\n"
				+ "dnl # If you're operating in a DSCP/RFC-4594 environment with QoS\n"
				+ "dnl define(`confINET_QOS', `AF11')dnl\n"
				+ "FEATURE(`delay_checks')dnl\n" // AO Enabled
				+ "FEATURE(`no_default_msa', `dnl')dnl\n"
				+ "FEATURE(`smrsh', `/usr/sbin/smrsh')dnl\n"
				+ "FEATURE(`mailertable', `hash -o /etc/mail/mailertable.db')dnl\n"
				+ "FEATURE(`virtuser_entire_domain')dnl\n" // AO Added
				+ "FEATURE(`virtusertable', `hash -o /etc/mail/virtusertable.db')dnl\n"
				+ "FEATURE(redirect)dnl\n"
				+ "dnl FEATURE(always_add_domain)dnl\n" // AO Disabled
				+ "FEATURE(use_cw_file)dnl\n"
				+ "dnl FEATURE(use_ct_file)dnl\n" // AO Disabled
				+ "dnl #\n"
				+ "dnl # The following limits the number of processes sendmail can fork to accept \n"
				+ "dnl # incoming messages or process its message queues to 20.) sendmail refuses \n"
				+ "dnl # to accept connections once it has reached its quota of child processes.\n"
				+ "dnl #\n"
				+ "define(`confMAX_DAEMON_CHILDREN', `1000')dnl\n" // AO Enabled and modified from `20'
				+ "dnl #\n"
				+ "dnl # Limits the number of new connections per second. This caps the overhead \n"
				+ "dnl # incurred due to forking new sendmail processes. May be useful against \n"
				+ "dnl # DoS attacks or barrages of spam. (As mentioned below, a per-IP address \n"
				+ "dnl # limit would be useful but is not available as an option at this writing.)\n"
				+ "dnl #\n"
				+ "define(`confBAD_RCPT_THROTTLE', `10')dnl\n" // AO added
				+ "define(`confCONNECTION_RATE_THROTTLE', `100')dnl\n" // AO enabled and modified from `3'
				+ "dnl #\n"
				+ "dnl # Allow large messages for big attachments.\n"
				+ "dnl #\n"
				+ "define(`confMAX_MESSAGE_SIZE', `100000000')dnl\n"
				+ "dnl #\n"
				+ "dnl # Stop accepting mail when disk almost full.\n"
				+ "dnl #\n"
				+ "define(`confMIN_FREE_BLOCKS', `65536')dnl\n"
				//+ "dnl #\n"
				//+ "dnl # Add process title prefix for fail-over state.\n"
				//+ "dnl #\n"
				//+ "define(`confPROCESS_TITLE_PREFIX',`").print(thisAoServer.getHostname()).print("')dnl\n"
				+ "dnl #\n"
				+ "dnl # The -t option will retry delivery if e.g. the user runs over his quota.\n"
				+ "dnl #\n"
				+ "FEATURE(local_procmail, `', `procmail -t -Y -a $h -d $u')dnl\n"
				+ "FEATURE(`access_db', `hash -T<TMPF> -o /etc/mail/access.db')dnl\n"
				+ "FEATURE(`blacklist_recipients')dnl\n"
				+ "EXPOSED_USER(`root')dnl\n");
		// Look for the configured net bind for the jilter
		NetBind jilterNetBind = JilterConfigurationWriter.getJilterNetBind();
		// Only configure when the net bind has been found
		if(jilterNetBind != null) {
			out.print("dnl #\n"
					+ "dnl # Enable AOServ Jilter\n"
					+ "dnl #\n");
			InetAddress ip = jilterNetBind.getIPAddress().getInetAddress();
			if(ip.isUnspecified()) ip = thisAoServer.getPrimaryIPAddress().getInetAddress();
			out
				.print("INPUT_MAIL_FILTER(`jilter', `S=")
				.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
				.print(':')
				.print(jilterNetBind.getPort().getPort()).print('@').print(ip).print(", F=R, T=S:60s;R:60s')\n");
		}
		out.print("dnl #\n"
				+ "dnl # For using Cyrus-IMAPd as POP3/IMAP server through LMTP delivery uncomment\n"
				+ "dnl # the following 2 definitions and activate below in the MAILER section the\n"
				+ "dnl # cyrusv2 mailer.\n"
				+ "dnl #\n"
				+ "dnl define(`confLOCAL_MAILER', `cyrusv2')dnl\n"
				+ "dnl define(`CYRUSV2_MAILER_ARGS', `FILE /var/lib/imap/socket/lmtp')dnl\n"
				+ "dnl #\n"
				+ "dnl # The following causes sendmail to only listen on the IPv4 loopback address\n"
				+ "dnl # 127.0.0.1 and not on any other network devices. Remove the loopback\n"
				+ "dnl # address restriction to accept email from the internet or intranet.\n"
				+ "dnl #\n");
		boolean sendmailEnabled = 
			!smtpNetBinds.isEmpty()
			|| !smtpsNetBinds.isEmpty()
			|| !submissionNetBinds.isEmpty()
		;
		if(sendmailEnabled) out.print("dnl ");
		out.print("DAEMON_OPTIONS(`Port=smtp,Addr=127.0.0.1, Name=MTA')dnl\n");
		Set<InetAddress> finishedIPs = new HashSet<>();
		for(NetBind nb : smtpNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MTA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("h");
				else out.print("bh");
				out.print("')dnl\n"); // AO added
			}
		}
		out.print("dnl #\n"
				+ "dnl # The following causes sendmail to additionally listen to port 587 for\n"
				+ "dnl # mail from MUAs that authenticate. Roaming users who can't reach their\n"
				+ "dnl # preferred sendmail daemon due to port 25 being blocked or redirected find\n"
				+ "dnl # this useful.\n"
				+ "dnl #\n"
				+ "dnl DAEMON_OPTIONS(`Port=submission, Name=MSA, M=Ea')dnl\n");
		finishedIPs.clear();
		for(NetBind nb : submissionNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-MSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("Eah");
				else out.print("Eabh");
				out.print("')dnl\n"); // AO added
			}
		}
		out.print("dnl #\n"
				+ "dnl # The following causes sendmail to additionally listen to port 465, but\n"
				+ "dnl # starting immediately in TLS mode upon connecting. Port 25 or 587 followed\n"
				+ "dnl # by STARTTLS is preferred, but roaming clients using Outlook Express can't\n"
				+ "dnl # do STARTTLS on ports other than 25. Mozilla Mail can ONLY use STARTTLS\n"
				+ "dnl # and doesn't support the deprecated smtps; Evolution <1.1.1 uses smtps\n"
				+ "dnl # when SSL is enabled-- STARTTLS support is available in version 1.1.1.\n"
				+ "dnl #\n"
				+ "dnl # For this to work your OpenSSL certificates must be configured.\n"
				+ "dnl #\n"
				+ "dnl DAEMON_OPTIONS(`Port=smtps, Name=TLSMTA, M=s')dnl\n");
		finishedIPs.clear();
		for(NetBind nb : smtpsNetBinds) {
			IPAddress ia = nb.getIPAddress();
			InetAddress ip = ia.getInetAddress();
			if(finishedIPs.add(ip)) {
				out
					.print("DAEMON_OPTIONS(`Addr=")
					.print(ip.toString())
					.print(", Family=")
					.print(ip.getAddressFamily().name().toLowerCase(Locale.ROOT))
					.print(", Port=")
					.print(nb.getPort().getPort())
					.print(", Name=")
					.print(ip.isUnspecified()?thisAoServer.getHostname():ia.getHostname())
					.print("-TLSMSA, Modifiers=")
				;
				if(ip.isUnspecified()) out.print("sh");
				else out.print("sbh");
				out.print("')dnl\n"); // AO added
			}
		}
		out.print("dnl #\n"
				+ "dnl # The following causes sendmail to additionally listen on the IPv6 loopback\n"
				+ "dnl # device. Remove the loopback address restriction listen to the network.\n"
				+ "dnl #\n"
				+ "dnl DAEMON_OPTIONS(`port=smtp,Addr=::1, Name=MTA-v6, Family=inet6')dnl\n"
				+ "dnl #\n"
				+ "dnl # enable both ipv6 and ipv4 in sendmail:\n"
				+ "dnl #\n"
				+ "dnl DAEMON_OPTIONS(`Name=MTA-v4, Family=inet, Name=MTA-v6, Family=inet6')\n"
				+ "dnl #\n"
				+ "dnl # We strongly recommend not accepting unresolvable domains if you want to\n"
				+ "dnl # protect yourself from spam. However, the laptop and users on computers\n"
				+ "dnl # that do not have 24x7 DNS do need this.\n"
				+ "dnl #\n"
				+ "dnl FEATURE(`accept_unresolvable_domains')dnl\n" // AO Disabled
				+ "dnl #\n"
				+ "dnl FEATURE(`relay_based_on_MX')dnl\n"
				+ "dnl # \n"
				+ "dnl # Also accept email sent to \"localhost.localdomain\" as local email.\n"
				+ "dnl # \n");
		if(sendmailEnabled) out.print("dnl "); // AO Disabled
		out.print("LOCAL_DOMAIN(`localhost.localdomain')dnl\n"
				+ "dnl #\n"
				+ "dnl # The following example makes mail from this host and any additional\n"
				+ "dnl # specified domains appear to be sent from mydomain.com\n"
				+ "dnl #\n"
				+ "dnl MASQUERADE_AS(`mydomain.com')dnl\n"
				+ "dnl #\n"
				+ "dnl # masquerade not just the headers, but the envelope as well\n"
				+ "dnl #\n"
				+ "dnl FEATURE(masquerade_envelope)dnl\n"
				+ "dnl #\n"
				+ "dnl # masquerade not just @mydomainalias.com, but @*.mydomainalias.com as well\n"
				+ "dnl #\n"
				+ "dnl FEATURE(masquerade_entire_domain)dnl\n"
				+ "dnl #\n"
				+ "dnl MASQUERADE_DOMAIN(localhost)dnl\n"
				+ "dnl MASQUERADE_DOMAIN(localhost.localdomain)dnl\n"
				+ "dnl MASQUERADE_DOMAIN(mydomainalias.com)dnl\n"
				+ "dnl MASQUERADE_DOMAIN(mydomain.lan)dnl\n"
		/*
			We are now blocking using egress filtering with firewalld direct.
			This means no special interaction with the firewalls - no outgoing NAT.
			A local root compromise could still bypass aoserv-jilter and send spam, but this was true before.

		if(thisAoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
			out.print("dnl #\n"
					+ "dnl # Establish outgoing connections from reserved ports (0-1023).\n"
					+ "dnl # This is used by firewall rules to prevent regular users from sending email directly.\n"
					+ "dnl #\n"
					+ "dnl # Some mail providers, such as yahoo.com, will not allow email from privileged ports,\n"
					+ "dnl # so this is used in conjunction with outgoing NAT on the routers to make connections\n"
					+ "dnl # appear to come from ports >= 1024.\n"
					+ "dnl #\n"
					+ "MODIFY_MAILER_FLAGS(`SMTP', `+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`ESMTP', `+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`SMTP8', `+R')dnl\n"
					+ "MODIFY_MAILER_FLAGS(`DSMTP', `+R')dnl\n"
					+ "dnl #\n");
		}
		 */
				+ "MAILER(smtp)dnl\n"
				+ "MAILER(procmail)dnl\n"
				+ "dnl MAILER(cyrusv2)dnl\n"
				+ "LOCAL_CONFIG\n"
				// From https://access.redhat.com/articles/1467453 on 2017-07-03
				+ "O CipherList=kEECDH:+kEECDH+SHA:kEDH:+kEDH+SHA:+kEDH+CAMELLIA:kECDH:+kECDH+SHA:kRSA:+kRSA+SHA:+kRSA+CAMELLIA:!aNULL:!eNULL:!SSLv2:!RC4:!MD5:!DES:!EXP:!SEED:!IDEA:!3DES\n"
				+ "O DHParameters=/etc/pki/sendmail/dhparams.pem\n"
				+ "O ServerSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3 +SSL_OP_CIPHER_SERVER_PREFERENCE\n"
				+ "O ClientSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3\n"
				// Add envelop header recipient
				+ "H?m?X-RCPT-To: $u\n"
				+ "Dj").print(thisAoServer.getHostname()).print("\n" // AO added for control $j in failover mode
				+ "\n");
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			// Used on inner processing
			AOServConnector conn = AOServDaemon.getConnector();
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			Server thisServer = thisAoServer.getServer();
			OperatingSystemVersion osv = thisServer.getOperatingSystemVersion();
			int osvId = osv.getPkey();
			ServerFarm serverFarm = thisServer.getServerFarm();
			IPAddress primaryIpAddress = thisAoServer.getPrimaryIPAddress();
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					boolean hasSpecificAddress = false;

					// Get the values used by different files once for internal consistency on dynamic data
					List<NetBind> smtpNetBinds = thisServer.getNetBinds(conn.getProtocols().get(Protocol.SMTP));
					List<NetBind> smtpsNetBinds = thisServer.getNetBinds(conn.getProtocols().get(Protocol.SMTPS));
					List<NetBind> submissionNetBinds = thisServer.getNetBinds(conn.getProtocols().get(Protocol.SUBMISSION));

					final boolean sendmailEnabled =
						!smtpNetBinds.isEmpty()
						|| !smtpsNetBinds.isEmpty()
						|| !submissionNetBinds.isEmpty()
					;
					final boolean sendmailInstalled;
					final boolean sendmailCfInstalled;

					// This is set to true when needed and a single reload will be performed after all config files are updated
					boolean[] needsReload = {false};
					if(sendmailEnabled) {
						// Make sure packages installed
						if(
							osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
							|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
						) {
							// No aoserv-sendmail-config package
							PackageManager.installPackage(
								PackageManager.PackageName.SENDMAIL,
								() -> needsReload[0] = true
							);
							PackageManager.installPackage(
								PackageManager.PackageName.SENDMAIL_CF,
								() -> needsReload[0] = true
							);
							PackageManager.installPackage(
								PackageManager.PackageName.CYRUS_SASL,
								() -> needsReload[0] = true
							);
							PackageManager.installPackage(
								PackageManager.PackageName.CYRUS_SASL_PLAIN,
								() -> needsReload[0] = true
							);
						} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
							// Install aoserv-sendmail-config package if missing
							PackageManager.installPackage(
								PackageManager.PackageName.AOSERV_SENDMAIL_CONFIG,
								() -> needsReload[0] = true
							);
						} else {
							throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
						sendmailInstalled = true;
						sendmailCfInstalled = true;
						// Resolve hasSpecificAddress
						for(NetBind nb : smtpNetBinds) {
							InetAddress ia = nb.getIPAddress().getInetAddress();
							if(!ia.isLoopback() && !ia.isUnspecified()) {
								hasSpecificAddress = true;
								break;
							}
						}
						if(!hasSpecificAddress) {
							for(NetBind nb : smtpsNetBinds) {
								InetAddress ia = nb.getIPAddress().getInetAddress();
								if(!ia.isLoopback() && !ia.isUnspecified()) {
									hasSpecificAddress = true;
									break;
								}
							}
						}
						if(!hasSpecificAddress) {
							for(NetBind nb : submissionNetBinds) {
								InetAddress ia = nb.getIPAddress().getInetAddress();
								if(!ia.isLoopback() && !ia.isUnspecified()) {
									hasSpecificAddress = true;
									break;
								}
							}
						}
					} else {
						sendmailInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL) != null;
						sendmailCfInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL_CF) != null;
					}
					if(sendmailInstalled) {
						// Build the new version of /etc/mail/sendmail.mc in RAM
						boolean sendmailMcUpdated;
						{
							try (ChainWriter out = new ChainWriter(bout)) {
								if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
									buildSendmailMcMandriva2006(out, thisAoServer, smtpNetBinds, smtpsNetBinds, submissionNetBinds);
								} else if(osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64) {
									buildSendmailMcRedHatES4(out, thisAoServer, smtpNetBinds, smtpsNetBinds, submissionNetBinds);
								} else if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
									buildSendmailMcCentOS5(out, thisAoServer, smtpNetBinds, smtpsNetBinds, submissionNetBinds);
								} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
									buildSendmailMcCentOS7(out, thisAoServer, smtpNetBinds, smtpsNetBinds, submissionNetBinds);
								} else {
									throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
								}
							}
							// Write the new file if it is different than the old
							sendmailMcUpdated = DaemonFileUtils.atomicWrite(
								sendmailMc,
								bout.toByteArray(),
								0644,
								UnixFile.ROOT_UID,
								UnixFile.ROOT_GID,
								null,
								restorecon
							);
						}

						// Rebuild the /etc/sendmail.cf file if doesn't exist or modified time is before sendmail.mc
						if(sendmailCfInstalled) {
							Stat sendmailMcStat = sendmailMc.getStat();
							Stat sendmailCfStat = sendmailCf.getStat();
							if(
								sendmailMcUpdated
								|| !sendmailCfStat.exists()
								|| sendmailCfStat.getModifyTime() < sendmailMcStat.getModifyTime()
							) {
								// Build to RAM to compare
								byte[] cfNewBytes = AOServDaemon.execAndCaptureBytes("/usr/bin/m4", "/etc/mail/sendmail.mc");
								if(
									DaemonFileUtils.atomicWrite(
										sendmailCf,
										cfNewBytes,
										0644,
										UnixFile.ROOT_UID,
										UnixFile.ROOT_GID,
										null,
										restorecon
									)
								) {
									needsReload[0] = true;
								} else {
									sendmailCf.utime(sendmailCfStat.getAccessTime(), sendmailMcStat.getModifyTime());
								}
							}
						}

						// Build the new version of /etc/mail/submit.mc in RAM
						boolean submitMcUpdated;
						{
							bout.reset();
							try (ChainWriter out = new ChainWriter(bout)) {
								// Submit will always be on the primary IP address
								if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
									out.print("divert(-1)\n"
											+ "#\n"
											+ "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
											+ "#\n"
											+ "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
											+ "OSTYPE(`linux')dnl\n"
											+ "define(`confCF_VERSION', `Submit')dnl\n"
											+ "FEATURE(`msp', `[").print(primaryIpAddress.getInetAddress().toString()).print("]')dnl\n"
											+ "define(`confRUN_AS_USER',`mail:mail')dnl\n"
											+ "define(`confTRUSTED_USER',`mail')dnl\n");
								} else if(osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64) {
									out.print("divert(-1)\n"
											+ "#\n"
											+ "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
											+ "#\n"
											+ "divert(0)dnl\n"
											+ "include(`/usr/share/sendmail-cf/m4/cf.m4')\n"
											+ "VERSIONID(`linux setup for Red Hat Linux')dnl\n"
											+ "define(`confCF_VERSION', `Submit')dnl\n"
											+ "define(`__OSTYPE__',`')dnl dirty hack to keep proto.m4 from complaining\n"
											+ "define(`_USE_DECNET_SYNTAX_', `1')dnl support DECnet\n"
											+ "define(`confTIME_ZONE', `USE_TZ')dnl\n"
											+ "define(`confDONT_INIT_GROUPS', `True')dnl\n"
											+ "define(`confPID_FILE', `/var/run/sm-client.pid')dnl\n"
											+ "dnl define(`confDIRECT_SUBMISSION_MODIFIERS',`C')\n"
											+ "dnl FEATURE(`use_ct_file')dnl\n"
											+ "FEATURE(`msp', `[").print(primaryIpAddress.getInetAddress().toString()).print("]')dnl\n"
											+ "define(`confPROCESS_TITLE_PREFIX',`").print(thisAoServer.getHostname()).print("')dnl\n");
								} else if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
									out.print("divert(-1)\n"
											+ "#\n"
											+ "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
											+ "#\n"
											+ "divert(0)dnl\n"
											+ "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
											+ "VERSIONID(`linux setup')dnl\n"
											+ "define(`confCF_VERSION', `Submit')dnl\n"
											+ "define(`__OSTYPE__',`')dnl dirty hack to keep proto.m4 from complaining\n"
											+ "define(`_USE_DECNET_SYNTAX_', `1')dnl support DECnet\n"
											+ "define(`confTIME_ZONE', `USE_TZ')dnl\n"
											+ "define(`confDONT_INIT_GROUPS', `True')dnl\n"
											+ "define(`confPID_FILE', `/var/run/sm-client.pid')dnl\n"
											+ "dnl define(`confDIRECT_SUBMISSION_MODIFIERS',`C')dnl\n"
											+ "FEATURE(`use_ct_file')dnl\n"
											+ "FEATURE(`msp', `[").print(primaryIpAddress.getInetAddress().toString()).print("]')dnl\n"
											+ "define(`confPROCESS_TITLE_PREFIX',`").print(thisAoServer.getHostname()).print("')dnl\n");
								} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
									// Find NetBind listing on SMTP on port 25, preferring primaryIpAddress
									InetAddress primaryInetAddress = primaryIpAddress.getInetAddress();
									InetAddress submitAddress = null;
									for(NetBind smtpBind : smtpNetBinds) {
										// Must be on port 25
										if(smtpBind.getPort().getPort() == 25) {
											InetAddress smtpAddress = smtpBind.getIPAddress().getInetAddress();
											if(smtpAddress.isUnspecified()) {
												// Use primary IP if in the same address family
												if(smtpAddress.getAddressFamily() == primaryInetAddress.getAddressFamily()) {
													submitAddress = primaryInetAddress;
													break;
												}
											} else if(smtpAddress.equals(primaryInetAddress)) {
												// Found primary
												submitAddress = smtpAddress;
												break;
											} else if(submitAddress == null) {
												// Found candidate, but keep looking
												submitAddress = smtpAddress;
											}
										}
									}
									if(submitAddress == null && sendmailEnabled) {
										// TODO: Could then try port 587?
										throw new SQLException("Unable to find any SMTP on port 25 for submit.mc");
									}
									out.print("divert(-1)\n"
											+ "#\n"
											+ "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
											+ "#\n"
											+ "# Copyright (c) 2001-2003 Sendmail, Inc. and its suppliers.\n"
											+ "#\tAll rights reserved.\n"
											+ "#\n"
											+ "# By using this file, you agree to the terms and conditions set\n"
											+ "# forth in the LICENSE file which can be found at the top level of\n"
											+ "# the sendmail distribution.\n"
											+ "#\n"
											+ "#\n"
											+ "\n"
											+ "#\n"
											+ "#  This is the prototype file for a set-group-ID sm-msp sendmail that\n"
											+ "#  acts as a initial mail submission program.\n"
											+ "#\n"
											+ "\n"
											+ "divert(0)dnl\n"
											+ "sinclude(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
											+ "VERSIONID(`AOServ Platform')dnl\n" // AO added
											+ "define(`confCF_VERSION', `Submit')dnl\n"
											+ "define(`__OSTYPE__',`')dnl dirty hack to keep proto.m4 from complaining\n"
											+ "define(`_USE_DECNET_SYNTAX_', `1')dnl support DECnet\n"
											+ "define(`confTIME_ZONE', `USE_TZ')dnl\n"
											+ "define(`confDONT_INIT_GROUPS', `True')dnl\n"
											+ "dnl # If you're operating in a DSCP/RFC-4594 environment with QoS\n"
											+ "dnl define(`confINET_QOS', `AF11')dnl\n"
											+ "define(`confPID_FILE', `/run/sm-client.pid')dnl\n"
											+ "dnl define(`confDIRECT_SUBMISSION_MODIFIERS',`C')dnl\n"
											+ "FEATURE(`use_ct_file')dnl\n"
											+ "dnl\n"
											+ "dnl If you use IPv6 only, change [127.0.0.1] to [IPv6:::1]\n"
											+ "FEATURE(`msp', `[").print(submitAddress==null ? "127.0.0.1" : submitAddress.toString()).print("]')dnl\n");
								} else {
									throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
								}
							}
							// Write the new file if it is different than the old
							submitMcUpdated = DaemonFileUtils.atomicWrite(
								submitMc,
								bout.toByteArray(),
								0644,
								UnixFile.ROOT_UID,
								UnixFile.ROOT_GID,
								null,
								restorecon
							);
						}

						// Rebuild the /etc/submit.cf file if doesn't exist or modified time is before submit.mc
						if(sendmailCfInstalled) {
							Stat submitMcStat = submitMc.getStat();
							Stat submitCfStat = submitCf.getStat();
							if(
								submitMcUpdated
								|| !submitCfStat.exists()
								|| submitCfStat.getModifyTime() < submitMcStat.getModifyTime()
							) {
								// Build to RAM to compare
								byte[] cfNewBytes = AOServDaemon.execAndCaptureBytes("/usr/bin/m4", "/etc/mail/submit.mc");
								if(
									DaemonFileUtils.atomicWrite(
										submitCf,
										cfNewBytes,
										0644,
										UnixFile.ROOT_UID,
										UnixFile.ROOT_GID,
										null,
										restorecon
									)
								) {
									needsReload[0] = true;
								} else {
									submitCf.utime(submitCfStat.getAccessTime(), submitMcStat.getModifyTime());
								}
							}
						}
						// SELinux before next steps
						DaemonFileUtils.restorecon(restorecon);
						restorecon.clear();
						if(!sendmailEnabled) {
							// Sendmail installed but disabled
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								// Stop service if running
								if(subsysLockFile.exists()) {
									AOServDaemon.exec("/etc/rc.d/init.d/sendmail", "stop");
									if(subsysLockFile.exists()) throw new IOException(subsysLockFile.getPath() + " still exists after service stop");
								}
								// chkconfig off if needed
								if(sendmailRcFile.getStat().exists()) {
									AOServDaemon.exec("/sbin/chkconfig", "sendmail", "off");
									if(sendmailRcFile.getStat().exists()) throw new IOException(sendmailRcFile.getPath() + " still exists after chkconfig off");
								}
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								AOServDaemon.exec("/usr/bin/systemctl", "stop", "sendmail.service");
								AOServDaemon.exec("/usr/bin/systemctl", "disable", "sendmail.service");
							} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						} else {
							// Sendmail installed and enabled
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								// chkconfig on if needed
								if(!sendmailRcFile.getStat().exists()) {
									AOServDaemon.exec("/sbin/chkconfig", "sendmail", "on");
									if(!sendmailRcFile.getStat().exists()) throw new IOException(sendmailRcFile.getPath() + " still does not exist after chkconfig on");
								}
								// Start service if not running
								if(!subsysLockFile.exists()) {
									AOServDaemon.exec("/etc/rc.d/init.d/sendmail", "start");
									if(!subsysLockFile.exists()) throw new IOException(subsysLockFile.getPath() + " still does not exist after service start");
								} else {
									// Reload if needed
									if(needsReload[0]) {
										AOServDaemon.exec("/etc/rc.d/init.d/sendmail", "reload");
									}
								}
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								try {
									AOServDaemon.exec("/usr/bin/systemctl", "is-enabled", "--quiet", "sendmail.service");
								} catch(IOException e) {
									// Non-zero response indicates not enabled
									AOServDaemon.exec("/usr/bin/systemctl", "enable", "sendmail.service");
								}
								if(needsReload[0]) {
									AOServDaemon.exec("/usr/bin/systemctl", "reload-or-restart", "sendmail.service");
								} else {
									AOServDaemon.exec("/usr/bin/systemctl", "start", "sendmail.service");
								}
								// Install sendmail-after-network-online package on CentOS 7 when needed
								if(hasSpecificAddress) {
									PackageManager.installPackage(PackageManager.PackageName.SENDMAIL_AFTER_NETWORK_ONLINE);
								}
							} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
						// Uninstall sendmail-after-network-online package on CentOS 7 when not needed
						if(
							!hasSpecificAddress
							&& osvId == OperatingSystemVersion.CENTOS_7_X86_64
							&& AOServDaemonConfiguration.isPackageManagerUninstallEnabled()
						) {
							PackageManager.removePackage(PackageManager.PackageName.SENDMAIL_AFTER_NETWORK_ONLINE);
						}
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(SendmailCFManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(SendmailCFManager.class)
				&& sendmailCFManager == null
			) {
				System.out.print("Starting SendmailCFManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					sendmailCFManager = new SendmailCFManager();
					conn.getIpAddresses().addTableListener(sendmailCFManager, 0);
					conn.getNetBinds().addTableListener(sendmailCFManager, 0);
					conn.getAoServers().addTableListener(sendmailCFManager, 0);
					conn.getServerFarms().addTableListener(sendmailCFManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild sendmail.cf";
	}

	/**
	 * Checks if sendmail is expected to be enabled on this server.
	 * Sendmail is enabled when it is configured to listen on a port SMTP, SMTPS, or SUBMISSION.
	 *
	 * @see Protocol#SMTP
	 * @see Protocol#SMTPS
	 * @see Protocol#SUBMISSION
	 */
	public static boolean isSendmailEnabled() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		Server thisServer = thisAoServer.getServer();
		return
			!thisServer.getNetBinds(conn.getProtocols().get(Protocol.SMTP)).isEmpty()
			|| !thisServer.getNetBinds(conn.getProtocols().get(Protocol.SMTPS)).isEmpty()
			|| !thisServer.getNetBinds(conn.getProtocols().get(Protocol.SUBMISSION)).isEmpty()
		;
	}
}
