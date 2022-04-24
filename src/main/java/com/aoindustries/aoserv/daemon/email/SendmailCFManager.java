/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2003-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.email;

import com.aoapps.collections.AoCollections;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.net.DomainName;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.SendmailBind;
import com.aoindustries.aoserv.client.email.SendmailServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.pki.Certificate;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.httpd.HttpdServerManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Builds the sendmail.mc and sendmail.cf files as necessary.
 *
 * TODO: SELinux to support nonstandard ports.
 *
 * @author  AO Industries, Inc.
 */
public final class SendmailCFManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(SendmailCFManager.class.getName());

  private static SendmailCFManager sendmailCFManager;

  /**
   * The pattern matching service sendmail@&lt;name&gt;.service files.
   * Used to clean old instances from {@link HttpdServerManager#MULTI_USER_WANTS_DIRECTORY}.
   */
  private static final Pattern SENDMAIL_NAME_SERVICE_REGEXP = Pattern.compile("^sendmail@.+\\.service$");

  /**
   * The pattern matching service statistics@&lt;name&gt; files.
   * Used to clean old instances from <code>/var/log/mail</code>.
   */
  private static final Pattern STATISTICS_NAME_REGEXP = Pattern.compile("^statistics@.+$");

  /**
   * The pattern matching service mqueue@&lt;name&gt; files.
   * Used to clean old instances from <code>/var/spool</code>.
   */
  private static final Pattern MQUEUE_NAME_REGEXP = Pattern.compile("^mqueue@.+$");

  /**
   * The pattern matching service sendmail@&lt;name&gt;.pid files.
   * Used to clean old instances from <code>/var/run</code>.
   */
  private static final Pattern SENDMAIL_NAME_PID_REGEXP = Pattern.compile("^sendmail@.+\\.pid$");

  /**
   * The pattern matching service sendmail@&lt;name&gt;.cf files.
   * Used to clean old instances from <code>/etc/mail</code>.
   */
  private static final Pattern SENDMAIL_NAME_CF_REGEXP = Pattern.compile("^sendmail@.+\\.cf$");

  /**
   * The pattern matching service sendmail@&lt;name&gt;.mc files.
   * Used to clean old instances from <code>/etc/mail</code>.
   */
  private static final Pattern SENDMAIL_NAME_MC_REGEXP = Pattern.compile("^sendmail@.+\\.mc$");

  /**
   * The directory that Let's Encrypt certificates are copied to.
   * Matches the path in sendmail-copy-certificates
   */
  private static final PosixFile CERTIFICATE_COPY_DIRECTORY = new PosixFile("/etc/pki/sendmail/copy");

  /**
   * The prefix of Let's Encrypt links generated from a copy directory.
   *
   * @see  #CERTIFICATE_COPY_DIRECTORY
   */
  private static final String LETS_ENCRYPT_SYMLINK_PREFIX = "../../../../letsencrypt/live/";

  /**
   * The suffix used for links to source files for sendmail-copy-certificates.
   */
  private static final String SOURCE_SUFFIX = "-source";

  private static final PosixFile
      submitMc = new PosixFile("/etc/mail/submit.mc"),
      submitCf = new PosixFile("/etc/mail/submit.cf")
  ;

  /**
   * Gets the sendmail.mc file to use for the given SendmailServer instance.
   */
  private static PosixFile getSendmailMc(SendmailServer sendmailServer) {
    String systemdName = (sendmailServer == null) ? null : sendmailServer.getSystemdEscapedName();
    if (systemdName == null) {
      return new PosixFile("/etc/mail/sendmail.mc");
    } else {
      return new PosixFile("/etc/mail/sendmail@" + systemdName + ".mc");
    }
  }

  /**
   * Gets the sendmail.cf file to use for the given SendmailServer instance.
   */
  private static PosixFile getSendmailCf(SendmailServer sendmailServer) {
    String systemdName = (sendmailServer == null) ? null : sendmailServer.getSystemdEscapedName();
    if (systemdName == null) {
      return new PosixFile("/etc/mail/sendmail.cf");
    } else {
      return new PosixFile("/etc/mail/sendmail@" + systemdName + ".cf");
    }
  }

  private static final File subsysLockFile = new File("/var/lock/subsys/sendmail");

  private static final PosixFile sendmailRcFile = new PosixFile("/etc/rc.d/rc3.d/S80sendmail");

  private SendmailCFManager() {
    // Do nothing
  }

  /**
   * Builds the config for CentOS 5.
   */
  private static void buildSendmailMcCentOS5(
      ChainWriter out,
      Server thisServer,
      SendmailServer sendmailServer,
      List<SendmailBind> smtpNetBinds,
      List<SendmailBind> smtpsNetBinds,
      List<SendmailBind> submissionNetBinds
  ) throws IOException, SQLException {
    if (sendmailServer != null && sendmailServer.getName() != null) {
      throw new IllegalArgumentException("Only the unnamed default instance is supported on CentOS 5");
    }
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
        + "define(`confAUTH_OPTIONS', `A");
    boolean allowPlaintextAuth = (sendmailServer == null) ? SendmailServer.DEFAULT_ALLOW_PLAINTEXT_AUTH : sendmailServer.getAllowPlaintextAuth();
    if (!allowPlaintextAuth) {
      out.print(" p");
    }
    out.print(" y')dnl\n"
        + "TRUST_AUTH_MECH(`EXTERNAL DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
        + "define(`confAUTH_MECHANISMS', `EXTERNAL GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
        + "dnl\n"
        + "dnl STARTTLS configuration\n"
        + "dnl extract from http://www.sendmail.org/~ca/email/starttls.html\n"
        + "dnl\n");
    String serverCert, serverKey, cacert;
    {
      if (sendmailServer == null) {
        serverCert = "/etc/ssl/sendmail/MYcert.pem";
        serverKey = "/etc/ssl/sendmail/MYkey.pem";
        cacert = "/etc/ssl/sendmail/CAcert.pem";
      } else {
        Certificate certificate = sendmailServer.getServerCertificate();
        if (certificate.getCertbotName() != null) {
          throw new SQLException("Certbot not supported on CentOS 5");
        }
        serverCert = certificate.getCertFile().toString();
        serverKey = certificate.getKeyFile().toString();
        cacert = Objects.toString(certificate.getChainFile(), null);
        if (cacert == null) {
          // Use operating system default
          cacert = ImapManager.DEFAULT_CA_FILE;
        }
      }
    }
    String cacertPath;
    {
      int slashPos = cacert.lastIndexOf('/');
      if (slashPos == -1) {
        throw new SQLException("Unable to find slash (/) in cacert: " + cacert);
      }
      cacertPath = cacert.substring(0, slashPos);
      if (cacertPath.isEmpty()) {
        throw new SQLException("cacertPath is empty");
      }
    }
    out.print("define(`confCACERT_PATH', `").print(cacertPath).print("')dnl\n"
        + "define(`confCACERT', `").print(cacert).print("')dnl\n"
        + "define(`confSERVER_CERT', `").print(serverCert).print("')dnl\n"
        + "define(`confSERVER_KEY', `").print(serverKey).print("')dnl\n");
    String clientCert, clientKey;
    {
      if (sendmailServer == null) {
        clientCert = "/etc/ssl/sendmail/MYcert.pem";
        clientKey = "/etc/ssl/sendmail/MYkey.pem";
      } else {
        Certificate certificate = sendmailServer.getClientCertificate();
        if (certificate.getCertbotName() != null) {
          throw new SQLException("Certbot not supported on CentOS 5");
        }
        String certbotName = certificate.getCertbotName();
        clientCert = certificate.getCertFile().toString();
        clientKey = certificate.getKeyFile().toString();
      }
    }

    out.print("define(`confCLIENT_CERT', `").print(clientCert).print("')dnl\n"
        + "define(`confCLIENT_KEY', `").print(clientKey).print("')dnl\n"
        + "dnl\n"
        + "dnl Allow relatively high load averages\n");
    int queueLA = (sendmailServer == null) ? SendmailServer.DEFAULT_QUEUE_LA : sendmailServer.getQueueLA();
    if (queueLA == -1) {
      out.print("dnl ");
    }
    out.print("define(`confQUEUE_LA', `").print(queueLA == -1 ? 0 : queueLA).print("')dnl\n");
    int refuseLA = (sendmailServer == null) ? SendmailServer.DEFAULT_REFUSE_LA : sendmailServer.getRefuseLA();
    if (refuseLA == -1) {
      out.print("dnl ");
    }
    out.print("define(`confREFUSE_LA', `").print(refuseLA == -1 ? 0 : refuseLA).print("')dnl\n"
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
        + "dnl Additional features added AO Industries on 2005-04-22\n");
    int badRcptThrottle = (sendmailServer == null) ? SendmailServer.DEFAULT_BAD_RCPT_THROTTLE : sendmailServer.getBadRcptThrottle();
    if (badRcptThrottle == -1) {
      out.print("dnl ");
    }
    out.print("define(`confBAD_RCPT_THROTTLE',`").print(badRcptThrottle == -1 ? 0 : badRcptThrottle).print("')dnl\n");
    int connectionRateThrottle = (sendmailServer == null) ? SendmailServer.DEFAULT_CONNECTION_RATE_THROTTLE : sendmailServer.getConnectionRateThrottle();
    if (connectionRateThrottle == -1) {
      out.print("dnl ");
    }
    out.print("define(`confCONNECTION_RATE_THROTTLE',`").print(connectionRateThrottle == -1 ? 0 : connectionRateThrottle).print("')dnl\n");
    int delayLA = (sendmailServer == null) ? SendmailServer.DEFAULT_DELAY_LA : sendmailServer.getDelayLA();
    if (delayLA == -1) {
      out.print("dnl ");
    }
    out.print("define(`confDELAY_LA',`").print(delayLA == -1 ? 0 : delayLA).print("')dnl\n");
    int maxDaemonChildren = (sendmailServer == null) ? SendmailServer.DEFAULT_MAX_DAEMON_CHILDREN : sendmailServer.getMaxDaemonChildren();
    if (maxDaemonChildren == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMAX_DAEMON_CHILDREN',`").print(maxDaemonChildren == -1 ? 0 : maxDaemonChildren).print("')dnl\n");
    int maxMessageSize = (sendmailServer == null) ? SendmailServer.DEFAULT_MAX_MESSAGE_SIZE : sendmailServer.getMaxMessageSize();
    if (maxMessageSize == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMAX_MESSAGE_SIZE',`").print(maxMessageSize == -1 ? 0 : maxMessageSize).print("')dnl\n");
    int maxQueueChildren = (sendmailServer == null) ? SendmailServer.DEFAULT_MAX_QUEUE_CHILDREN : sendmailServer.getMaxQueueChildren();
    if (maxQueueChildren == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMAX_QUEUE_CHILDREN',`").print(maxQueueChildren == -1 ? 0 : maxQueueChildren).print("')dnl\n");
    int minFreeBlocks = (sendmailServer == null) ? SendmailServer.DEFAULT_MIN_FREE_BLOCKS : sendmailServer.getMinFreeBlocks();
    if (minFreeBlocks == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMIN_FREE_BLOCKS',`").print(minFreeBlocks == -1 ? 100 : minFreeBlocks).print("')dnl\n");
    int niceQueueRun = (sendmailServer == null) ? SendmailServer.DEFAULT_NICE_QUEUE_RUN : sendmailServer.getNiceQueueRun();
    if (niceQueueRun == -1) {
      out.print("dnl ");
    }
    out.print("define(`confNICE_QUEUE_RUN',`").print(niceQueueRun == -1 ? 0 : niceQueueRun).print("')dnl\n");
    DomainName hostname = (sendmailServer == null) ? null : sendmailServer.getHostname();
    if (hostname == null) {
      hostname = thisServer.getHostname();
    }
    out.print("define(`confPROCESS_TITLE_PREFIX',`").print(hostname).print("')dnl\n"
        + "dnl\n");
    // Look for the configured net bind for the jilter
    IpAddress primaryIpAddress = thisServer.getPrimaryIPAddress();
    Bind jilterNetBind = JilterConfigurationWriter.getJilterNetBind();
    // Only configure when the net bind has been found
    if (jilterNetBind != null) {
      out.print("dnl Enable Jilter\n"
          + "dnl\n");
      InetAddress ip = jilterNetBind.getIpAddress().getInetAddress();
      if (ip.isUnspecified()) {
        ip = primaryIpAddress.getInetAddress();
      }
      out
          .print("INPUT_MAIL_FILTER(`jilter',`S=")
          .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
          .print(':')
          .print(jilterNetBind.getPort().getPort()).print('@').print(ip).print(", F=R, T=S:60s;R:60s')\n"
          + "dnl\n");
    }
    out.print("dnl Only listen to the IP addresses of this logical server\n"
        + "dnl\n"
        + "FEATURE(`no_default_msa')dnl\n");
    Set<InetAddress> finishedIPs = new HashSet<>();
    for (SendmailBind sb : smtpNetBinds) {
      Bind nb = sb.getNetBind();
      IpAddress ia = nb.getIpAddress();
      InetAddress ip = ia.getInetAddress();
      if (finishedIPs.add(ip)) {
        String bindName = sb.getName();
        if (bindName == null) {
          bindName = (ip.isUnspecified() ? hostname : ia.getHostname()) + "-MTA";
        }
        out
            .print("DAEMON_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print(", Port=")
            .print(nb.getPort().getPort())
            .print(", Name=")
            .print(bindName)
            .print(", Modifiers=")
        ;
        if (ip.isUnspecified()) {
          out.print("h");
        } else {
          out.print("bh");
        }
        out.print("')dnl\n"); // AO added
      }
    }
    finishedIPs.clear();
    for (SendmailBind sb : smtpsNetBinds) {
      Bind nb = sb.getNetBind();
      IpAddress ia = nb.getIpAddress();
      InetAddress ip = ia.getInetAddress();
      if (finishedIPs.add(ip)) {
        String bindName = sb.getName();
        if (bindName == null) {
          bindName = (ip.isUnspecified() ? hostname : ia.getHostname()) + "-TLSMSA";
        }
        out
            .print("DAEMON_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print(", Port=")
            .print(nb.getPort().getPort())
            .print(", Name=")
            .print(bindName)
            .print(", Modifiers=")
        ;
        if (ip.isUnspecified()) {
          out.print("hs");
        } else {
          out.print("bhs");
        }
        out.print("')dnl\n"); // AO added
      }
    }
    finishedIPs.clear();
    for (SendmailBind sb : submissionNetBinds) {
      Bind nb = sb.getNetBind();
      IpAddress ia = nb.getIpAddress();
      InetAddress ip = ia.getInetAddress();
      if (finishedIPs.add(ip)) {
        String bindName = sb.getName();
        if (bindName == null) {
          bindName = (ip.isUnspecified() ? hostname : ia.getHostname()) + "-MSA";
        }
        out
            .print("DAEMON_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print(", Port=")
            .print(nb.getPort().getPort())
            .print(", Name=")
            .print(bindName)
            .print(", Modifiers=")
        ;
        if (ip.isUnspecified()) {
          out.print("Eah");
        } else {
          out.print("Eabh");
        }
        out.print("')dnl\n"); // AO added
      }
    }
    IpAddress clientAddrInet = (sendmailServer == null) ? null : sendmailServer.getClientAddrInet();
    IpAddress clientAddrInet6 = (sendmailServer == null) ? null : sendmailServer.getClientAddrInet6();
    if (clientAddrInet != null || clientAddrInet6 != null) {
      out.print("dnl\n"
          + "dnl Configure outgoing connections:\n");
      if (clientAddrInet != null) {
        InetAddress ip = clientAddrInet.getInetAddress();
        out
            .print("CLIENT_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print("')dnl\n"); // AO added
      }
      if (clientAddrInet6 != null) {
        InetAddress ip = clientAddrInet6.getInetAddress();
        out
            .print("CLIENT_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print("')dnl\n"); // AO added
      }
    }
    out.print("dnl\n"
        + "dnl Enable IDENT lookups\n"
        // TO_IDENT set to 10s was causing normally 1 second email to become 30 second email on www.keepandshare.com
        + "define(`confTO_IDENT',`0s')dnl\n"
//          We are now blocking using egress filtering with iptables in /etc/opt/aoserv-daemon/route.
//          This means no special interaction with the firewalls - no outgoing NAT.
//          A local root compromise could still bypass aoserv-jilter and send spam, but this was true before.
//
//         if (thisAoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
//          out.print("MODIFY_MAILER_FLAGS(`SMTP',`+R')dnl\n"
//              + "MODIFY_MAILER_FLAGS(`ESMTP',`+R')dnl\n"
//              + "MODIFY_MAILER_FLAGS(`SMTP8',`+R')dnl\n"
//              + "MODIFY_MAILER_FLAGS(`DSMTP',`+R')dnl\n");
//        }
        + "MAILER(smtp)dnl\n"
        + "MAILER(procmail)dnl\n"
        + "LOCAL_CONFIG\n"
        // From http://serverfault.com/questions/700655/sendmail-rejecting-some-connections-with-handshake-failure-ssl-alert-number-40
        + "O CipherList=HIGH:!ADH\n"
        + "O DHParameters=/etc/ssl/sendmail/dhparams.pem\n"
        + "O ServerSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3 +SSL_OP_CIPHER_SERVER_PREFERENCE\n"
        + "O ClientSSLOptions=+SSL_OP_NO_SSLv2 +SSL_OP_NO_SSLv3\n"
        // Add envelop header recipient
        + "H?m?X-RCPT-To: $u\n");
    String fqdn = hostname.toString();
    int dotPos = fqdn.indexOf('.');
    if (dotPos == -1) {
      throw new SQLException("No dot (.) in fqdn: " + fqdn);
    }
    out.print("Dw").print(fqdn.substring(0, dotPos)).print("\n"
        + "Dm").print(fqdn.substring(dotPos + 1)).print("\n"
        + "define(`confDOMAIN_NAME', `$w.$m')dnl\n"
        + "\n"
    );
  }

  /**
   * Builds the config for CentOS 7.
   *
   * @param sendmailServer  When null, this is the default server config being built for where sendmail installed but not activated
   */
  private static void buildSendmailMcCentOS7(
      ChainWriter out,
      Server thisServer,
      SendmailServer sendmailServer,
      List<SendmailBind> smtpNetBinds,
      List<SendmailBind> smtpsNetBinds,
      List<SendmailBind> submissionNetBinds
  ) throws IOException, SQLException {
    PosixFile sendmailMc = getSendmailMc(sendmailServer);
    PosixFile sendmailCf = getSendmailCf(sendmailServer);
    String systemdName = (sendmailServer == null) ? null : sendmailServer.getSystemdEscapedName();
    out.print("divert(-1)dnl\n"
        + "dnl #\n"
        + "dnl # Generated by ").print(SendmailCFManager.class.getName()).print("\n"
        + "dnl #\n"
        + "dnl # This is the sendmail macro config file for m4. If you make changes to\n"
        + "dnl # ").print(sendmailMc).print(", you will need to regenerate the\n"
        + "dnl # ").print(sendmailCf).print(" file by confirming that the sendmail-cf package is\n"
        + "dnl # installed and then performing a\n"
        + "dnl #\n"
        + "dnl #     /etc/mail/make");
    if (systemdName != null) {
      out.print(" 'sendmail@").print(systemdName).print(".cf'");
    }
    out.print('\n'
        + "dnl #\n"
        + "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
        + "VERSIONID(`AOServ Platform')dnl\n" // AO added
        + "OSTYPE(`linux')dnl\n");
    if (systemdName != null) {
      // See http://www.softpanorama.org/Mail/Sendmail/running_several_instances_of_sendmail.shtml
      out.print("dnl #\n"
          + "dnl # Multiple sendmail instance support.\n"
          + "dnl #\n"
          + "define(`confPID_FILE',`/var/run/sendmail@").print(systemdName).print(".pid')dnl\n"
          + "define(`QUEUE_DIR',`/var/spool/mqueue@").print(systemdName).print("')dnl\n");
    }
    out.print("dnl #\n"
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
        + "define(`STATUS_FILE', `/var/log/mail/statistics");
    if (systemdName != null) {
      out.print('@').print(systemdName);
    }
    out.print("')dnl\n"
        + "define(`UUCP_MAILER_MAX', `2000000')dnl\n"
        + "define(`confUSERDB_SPEC', `/etc/mail/userdb.db')dnl\n"
        //+ "define(`confPRIVACY_FLAGS', `authwarnings,novrfy,noexpn,restrictqrun')dnl\n"
        + "define(`confPRIVACY_FLAGS', `authwarnings,goaway,novrfy,noexpn,restrictqrun,restrictmailq,restrictexpand')dnl\n" // AO Modified
        + "define(`confAUTH_OPTIONS', `A");
    boolean allowPlaintextAuth = sendmailServer == null ? SendmailServer.DEFAULT_ALLOW_PLAINTEXT_AUTH : sendmailServer.getAllowPlaintextAuth();
    if (!allowPlaintextAuth) {
      out.print(" p");
    }
    out.print(" y')dnl\n" // AO modified from `A'
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
        + "dnl #\n");
    String serverCert, serverKey, cacert;
    {
      if (sendmailServer == null) {
        serverCert = "/etc/pki/sendmail/sendmail.pem";
        serverKey = "/etc/pki/sendmail/sendmail.pem";
        cacert = ImapManager.DEFAULT_CA_FILE;
      } else {
        Certificate certificate = sendmailServer.getServerCertificate();
        String certbotName = certificate.getCertbotName();
        if (certbotName != null) {
          PosixFile dir = new PosixFile(CERTIFICATE_COPY_DIRECTORY, certbotName, true);
          serverCert = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_CERT,  true).getPath();
          serverKey  = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_KEY,   true).getPath();
          cacert     = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_CHAIN, true).getPath();
        } else {
          serverCert = certificate.getCertFile().toString();
          serverKey = certificate.getKeyFile().toString();
          cacert = Objects.toString(certificate.getChainFile(), null);
          if (cacert == null) {
            // Use operating system default
            cacert = ImapManager.DEFAULT_CA_FILE;
          }
        }
      }
    }
    String cacertPath;
    {
      if (cacert.equals(ImapManager.DEFAULT_CA_FILE)) {
        int slashPos = cacert.lastIndexOf('/');
        if (slashPos == -1) {
          throw new SQLException("Unable to find slash (/) in cacert: " + cacert);
        }
        cacertPath = cacert.substring(0, slashPos);
        if (cacertPath.isEmpty()) {
          throw new SQLException("cacertPath is empty");
        }
      } else {
        cacertPath = "/etc/pki/ca-trust-hash/hash";
      }
    }
    out.print("define(`confCACERT_PATH', `").print(cacertPath).print("')dnl\n"
        + "define(`confCACERT', `").print(cacert).print("')dnl\n"
        + "define(`confSERVER_CERT', `").print(serverCert).print("')dnl\n"
        + "define(`confSERVER_KEY', `").print(serverKey).print("')dnl\n");
    String clientCert, clientKey;
    {
      if (sendmailServer == null) {
        clientCert = "/etc/pki/sendmail/sendmail.pem";
        clientKey = "/etc/pki/sendmail/sendmail.pem";
      } else {
        Certificate certificate = sendmailServer.getClientCertificate();
        String certbotName = certificate.getCertbotName();
        if (certbotName != null) {
          PosixFile dir = new PosixFile(CERTIFICATE_COPY_DIRECTORY, certbotName, true);
          clientCert = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_CERT,  true).getPath();
          clientKey  = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_KEY,   true).getPath();
        } else {
          clientCert = certificate.getCertFile().toString();
          clientKey = certificate.getKeyFile().toString();
        }
      }
    }
    out.print("define(`confCLIENT_CERT', `").print(clientCert).print("')dnl\n"
        + "define(`confCLIENT_KEY', `").print(clientKey).print("')dnl\n"
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
        + "dnl define(`confTO_QUEUERETURN', `5d')dnl\n");
    int maxQueueChildren = sendmailServer == null ? SendmailServer.DEFAULT_MAX_QUEUE_CHILDREN : sendmailServer.getMaxQueueChildren();
    if (maxQueueChildren == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMAX_QUEUE_CHILDREN', `").print(maxQueueChildren == -1 ? 0 : maxQueueChildren).print("')dnl\n");
    int niceQueueRun = sendmailServer == null ? SendmailServer.DEFAULT_NICE_QUEUE_RUN : sendmailServer.getNiceQueueRun();
    if (niceQueueRun == -1) {
      out.print("dnl ");
    }
    out.print("define(`confNICE_QUEUE_RUN', `").print(niceQueueRun == -1 ? 0 : niceQueueRun).print("')dnl\n"
        + "dnl #\n"
        + "dnl # Allow relatively high load averages\n"
        + "dnl #\n");
    int delayLA = sendmailServer == null ? SendmailServer.DEFAULT_DELAY_LA : sendmailServer.getDelayLA();
    if (delayLA == -1) {
      out.print("dnl ");
    }
    out.print("define(`confDELAY_LA', `").print(delayLA == -1 ? 0 : delayLA).print("')dnl\n"); // AO Added
    int queueLA = sendmailServer == null ? SendmailServer.DEFAULT_DELAY_LA : sendmailServer.getQueueLA();
    if (queueLA == -1) {
      out.print("dnl ");
    }
    out.print("define(`confQUEUE_LA', `").print(queueLA == -1 ? 0 : queueLA).print("')dnl\n"); // AO Enabled and modified from `12'
    int refuseLA = sendmailServer == null ? SendmailServer.DEFAULT_REFUSE_LA : sendmailServer.getRefuseLA();
    if (refuseLA == -1) {
      out.print("dnl ");
    }
    out.print("define(`confREFUSE_LA', `").print(refuseLA == -1 ? 0 : refuseLA).print("')dnl\n" // AO Enabled and modified from `18'
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
        + "dnl #\n");
    int maxDaemonChildren = sendmailServer == null ? SendmailServer.DEFAULT_MAX_DAEMON_CHILDREN : sendmailServer.getMaxDaemonChildren();
    if (maxDaemonChildren == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMAX_DAEMON_CHILDREN', `").print(maxDaemonChildren == -1 ? 0 : maxDaemonChildren).print("')dnl\n" // AO Enabled and modified from `20'
        + "dnl #\n"
        + "dnl # Limits the number of new connections per second. This caps the overhead \n"
        + "dnl # incurred due to forking new sendmail processes. May be useful against \n"
        + "dnl # DoS attacks or barrages of spam. (As mentioned below, a per-IP address \n"
        + "dnl # limit would be useful but is not available as an option at this writing.)\n"
        + "dnl #\n");
    int badRcptThrottle = sendmailServer == null ? SendmailServer.DEFAULT_BAD_RCPT_THROTTLE : sendmailServer.getBadRcptThrottle();
    if (badRcptThrottle == -1) {
      out.print("dnl ");
    }
    out.print("define(`confBAD_RCPT_THROTTLE', `").print(badRcptThrottle == -1 ? 0 : badRcptThrottle).print("')dnl\n"); // AO added
    int connectionRateThrottle = sendmailServer == null ? SendmailServer.DEFAULT_CONNECTION_RATE_THROTTLE : sendmailServer.getConnectionRateThrottle();
    if (connectionRateThrottle == -1) {
      out.print("dnl ");
    }
    out.print("define(`confCONNECTION_RATE_THROTTLE', `").print(connectionRateThrottle == -1 ? 0 : connectionRateThrottle).print("')dnl\n" // AO enabled and modified from `3'
        + "dnl #\n"
        + "dnl # Allow large messages for big attachments.\n"
        + "dnl #\n");
    int maxMessageSize = sendmailServer == null ? SendmailServer.DEFAULT_MAX_MESSAGE_SIZE : sendmailServer.getMaxMessageSize();
    if (maxMessageSize == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMAX_MESSAGE_SIZE', `").print(maxMessageSize == -1 ? 0 : maxMessageSize).print("')dnl\n"
        + "dnl #\n"
        + "dnl # Stop accepting mail when disk almost full.\n"
        + "dnl #\n");
    int minFreeBlocks = sendmailServer == null ? SendmailServer.DEFAULT_MIN_FREE_BLOCKS : sendmailServer.getMinFreeBlocks();
    if (minFreeBlocks == -1) {
      out.print("dnl ");
    }
    out.print("define(`confMIN_FREE_BLOCKS', `").print(minFreeBlocks == -1 ? 100 : minFreeBlocks).print("')dnl\n"
        + "dnl #\n"
        + "dnl # Add process title prefix for multi-instance support.\n"
        + "dnl #\n");
    if (systemdName == null) {
      out.print("dnl ");
    }
    out.print("define(`confPROCESS_TITLE_PREFIX',`").print((systemdName == null) ? "" : systemdName).print("')dnl\n" // AO added
        + "dnl #\n"
        + "dnl # The -t option will retry delivery if e.g. the user runs over his quota.\n"
        + "dnl #\n"
        + "FEATURE(local_procmail, `', `procmail -t -Y -a $h -d $u')dnl\n"
        + "FEATURE(`access_db', `hash -T<TMPF> -o /etc/mail/access.db')dnl\n"
        + "FEATURE(`blacklist_recipients')dnl\n"
        + "EXPOSED_USER(`root')dnl\n");
    // Look for the configured net bind for the jilter
    IpAddress primaryIpAddress = thisServer.getPrimaryIPAddress();
    Bind jilterNetBind = JilterConfigurationWriter.getJilterNetBind();
    // Only configure when the net bind has been found
    if (jilterNetBind != null) {
      out.print("dnl #\n"
          + "dnl # Enable AOServ Jilter\n"
          + "dnl #\n");
      InetAddress ip = jilterNetBind.getIpAddress().getInetAddress();
      if (ip.isUnspecified()) {
        ip = primaryIpAddress.getInetAddress();
      }
      out
          .print("INPUT_MAIL_FILTER(`jilter', `S=")
          .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
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
    if (sendmailServer != null) {
      out.print("dnl ");
    }
    out.print("DAEMON_OPTIONS(`Port=smtp,Addr=127.0.0.1, Name=MTA')dnl\n");
    DomainName hostname = sendmailServer == null ? null : sendmailServer.getHostname();
    if (hostname == null) {
      hostname = thisServer.getHostname();
    }
    Set<InetAddress> finishedIPs = new HashSet<>();
    for (SendmailBind sb : smtpNetBinds) {
      Bind nb = sb.getNetBind();
      IpAddress ia = nb.getIpAddress();
      InetAddress ip = ia.getInetAddress();
      if (finishedIPs.add(ip)) {
        String bindName = sb.getName();
        if (bindName == null) {
          bindName = (ip.isUnspecified() ? hostname : ia.getHostname()) + "-MTA";
        }
        out
            .print("DAEMON_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print(", Port=")
            .print(nb.getPort().getPort())
            .print(", Name=")
            .print(bindName);
        if (!ip.isUnspecified()) {
          out.print(", Modifiers=b");
        }
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
    for (SendmailBind sb : submissionNetBinds) {
      Bind nb = sb.getNetBind();
      IpAddress ia = nb.getIpAddress();
      InetAddress ip = ia.getInetAddress();
      if (finishedIPs.add(ip)) {
        String bindName = sb.getName();
        if (bindName == null) {
          bindName = (ip.isUnspecified() ? hostname : ia.getHostname()) + "-MSA";
        }
        out
            .print("DAEMON_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print(", Port=")
            .print(nb.getPort().getPort())
            .print(", Name=")
            .print(bindName)
            .print(", Modifiers=Ea");
        if (!ip.isUnspecified()) {
          out.print('b');
        }
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
    for (SendmailBind sb : smtpsNetBinds) {
      Bind nb = sb.getNetBind();
      IpAddress ia = nb.getIpAddress();
      InetAddress ip = ia.getInetAddress();
      if (finishedIPs.add(ip)) {
        String bindName = sb.getName();
        if (bindName == null) {
          bindName = (ip.isUnspecified() ? hostname : ia.getHostname()) + "-TLSMSA";
        }
        out
            .print("DAEMON_OPTIONS(`Addr=")
            .print(ip.toString())
            .print(", Family=")
            .print(ip.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print(", Port=")
            .print(nb.getPort().getPort())
            .print(", Name=")
            .print(bindName)
            .print(", Modifiers=s");
        if (!ip.isUnspecified()) {
          out.print('b');
        }
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
        + "dnl DAEMON_OPTIONS(`Name=MTA-v4, Family=inet, Name=MTA-v6, Family=inet6')\n");
    InetAddress clientAddrInet;
    {
      if (sendmailServer == null) {
        clientAddrInet = null;
      } else {
        IpAddress clientIP = sendmailServer.getClientAddrInet();
        if (clientIP != null) {
          clientAddrInet = clientIP.getInetAddress();
        } else {
          // Automatic client inet address, based on port SMTP, Submission, then SMTPS
          InetAddress primaryAddress = primaryIpAddress.getInetAddress();
          if (!primaryAddress.getProtocolFamily().equals(StandardProtocolFamily.INET)) {
            primaryAddress = null;
          }
          clientAddrInet = findSmtpAddress(StandardProtocolFamily.INET, primaryAddress, smtpNetBinds, null);
          if (clientAddrInet == null) {
            findSmtpAddress(StandardProtocolFamily.INET, primaryAddress, submissionNetBinds, null);
          }
          if (clientAddrInet == null) {
            findSmtpAddress(StandardProtocolFamily.INET, primaryAddress, smtpsNetBinds, null);
          }
          // Don't specify client when matches primary IP on this family
          if (clientAddrInet != null && clientAddrInet.equals(primaryAddress)) {
            clientAddrInet = null;
          }
        }
      }
    }
    InetAddress clientAddrInet6;
    {
      if (sendmailServer == null) {
        clientAddrInet6 = null;
      } else {
        IpAddress clientIP = sendmailServer.getClientAddrInet6();
        if (clientIP != null) {
          clientAddrInet6 = clientIP.getInetAddress();
        } else {
          // Automatic client inet6 address, based on port SMTP, Submission, then SMTPS
          InetAddress primaryAddress = primaryIpAddress.getInetAddress();
          if (!primaryAddress.getProtocolFamily().equals(StandardProtocolFamily.INET6)) {
            primaryAddress = null;
          }
          clientAddrInet6 = findSmtpAddress(StandardProtocolFamily.INET6, primaryAddress, smtpNetBinds, null);
          if (clientAddrInet6 == null) {
            findSmtpAddress(StandardProtocolFamily.INET6, primaryAddress, submissionNetBinds, null);
          }
          if (clientAddrInet6 == null) {
            findSmtpAddress(StandardProtocolFamily.INET6, primaryAddress, smtpsNetBinds, null);
          }
          // Don't specify client when matches primary IP on this family
          if (clientAddrInet6 != null && clientAddrInet6.equals(primaryAddress)) {
            clientAddrInet6 = null;
          }
        }
      }
    }
    if (clientAddrInet != null || clientAddrInet6 != null) {
      out.print("dnl #\n"
          + "dnl # Configure outgoing connections:\n"
          + "dnl #\n");
      if (clientAddrInet != null) {
        out
            .print("CLIENT_OPTIONS(`Addr=")
            .print(clientAddrInet.toString())
            .print(", Family=")
            .print(clientAddrInet.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print("')dnl\n"); // AO added
      }
      if (clientAddrInet6 != null) {
        out
            .print("CLIENT_OPTIONS(`Addr=")
            .print(clientAddrInet6.toString())
            .print(", Family=")
            .print(clientAddrInet6.getProtocolFamily().name().toLowerCase(Locale.ROOT))
            .print("')dnl\n"); // AO added
      }
    }
    out.print("dnl #\n"
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
    if (sendmailServer != null) {
      // AO Disabled
      out.print("dnl ");
    }
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
//          We are now blocking using egress filtering with firewalld direct.
//          This means no special interaction with the firewalls - no outgoing NAT.
//          A local root compromise could still bypass aoserv-jilter and send spam, but this was true before.
//
//         if (thisAoServer.getServer().getServerFarm().useRestrictedSmtpPort()) {
//          out.print("dnl #\n"
//              + "dnl # Establish outgoing connections from reserved ports (0-1023).\n"
//              + "dnl # This is used by firewall rules to prevent regular users from sending email directly.\n"
//              + "dnl #\n"
//              + "dnl # Some mail providers, such as yahoo.com, will not allow email from privileged ports,\n"
//              + "dnl # so this is used in conjunction with outgoing NAT on the routers to make connections\n"
//              + "dnl # appear to come from ports >= 1024.\n"
//              + "dnl #\n"
//              + "MODIFY_MAILER_FLAGS(`SMTP', `+R')dnl\n"
//              + "MODIFY_MAILER_FLAGS(`ESMTP', `+R')dnl\n"
//              + "MODIFY_MAILER_FLAGS(`SMTP8', `+R')dnl\n"
//              + "MODIFY_MAILER_FLAGS(`DSMTP', `+R')dnl\n"
//              + "dnl #\n");
//        }
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
        + "H?m?X-RCPT-To: $u\n");
    String fqdn = hostname.toString();
    int dotPos = fqdn.indexOf('.');
    if (dotPos == -1) {
      throw new SQLException("No dot (.) in fqdn: " + fqdn);
    }
    out.print("Dw").print(fqdn.substring(0, dotPos)).print("\n"
        + "Dm").print(fqdn.substring(dotPos + 1)).print("\n"
        + "define(`confDOMAIN_NAME', `$w.$m')dnl\n" // AO added for control $j for multi-instance support
        + "\n");
  }

  /**
   * Gets an IP address that is listening on one of the provided ports.
   * Uses the primary IP, if found, or the first IP when primary not found.
   *
   * @param  family          the optional address family to search, or null for any family
   * @param  primaryAddress  the optional primary address or null for no primary on the given family.  Must match family when family is not null.
   * @param  smtpBinds       the set of binds to search
   * @param  requiredPort    the optional required port number
   *
   * @return  The IP or {@code null} if no matches.
   */
  private static InetAddress findSmtpAddress(ProtocolFamily family, InetAddress primaryAddress, List<SendmailBind> smtpBinds, Integer requiredPort) throws IOException, SQLException {
    if (primaryAddress != null) {
      if (family != null && !primaryAddress.getProtocolFamily().equals(family)) {
        throw new IllegalArgumentException("Primary IP is not in family \"" + family + "\": " + primaryAddress);
      }
    } else {
      primaryAddress = null;
    }
    InetAddress foundAddress = null;
    for (SendmailBind smtpBind : smtpBinds) {
      Bind smtpNetBind = smtpBind.getNetBind();
      if (requiredPort == null || smtpNetBind.getPort().getPort() == requiredPort) {
        InetAddress smtpAddress = smtpNetBind.getIpAddress().getInetAddress();
        if (family == null || smtpAddress.getProtocolFamily().equals(family)) {
          if (smtpAddress.isUnspecified()) {
            // Use primary IP
            if (primaryAddress != null) {
              foundAddress = primaryAddress;
              break;
            }
          } else if (smtpAddress.equals(primaryAddress)) {
            // Found primary
            foundAddress = smtpAddress;
            break;
          } else if (foundAddress == null) {
            // Remember first found candidate, but keep looking in case find primary
            foundAddress = smtpAddress;
          }
        }
      }
    }
    return foundAddress;
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      // Used on inner processing
      // AOServConnector conn = AOServDaemon.getConnector();
      Server thisServer = AOServDaemon.getThisServer();
      Host thisHost = thisServer.getHost();
      OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
      int osvId = osv.getPkey();
      IpAddress primaryIpAddress = thisServer.getPrimaryIPAddress();
      ByteArrayOutputStream bout = new ByteArrayOutputStream();

      synchronized (rebuildLock) {
        List<SendmailServer> sendmailServers = thisServer.getSendmailServers();
        // Find the default sendmail instance, if any.
        SendmailServer defaultServer = null;
        // Find all named secondary instances.
        Set<SendmailServer> namedServers = new LinkedHashSet<>();
        for (SendmailServer ss : sendmailServers) {
          if (ss.getName() == null) {
            if (defaultServer != null) {
              throw new AssertionError("Duplicate default sendmail instances");
            }
            defaultServer = ss;
          } else {
            if (!namedServers.add(ss)) {
              throw new AssertionError("Duplicate named sendmail instance: " + ss.getName());
            }
          }
        }

        // Get the values used by different files once for internal consistency on dynamic data
        Map<SendmailServer, List<SendmailBind>> smtpBinds;
        Map<SendmailServer, List<SendmailBind>> smtpsBinds;
        Map<SendmailServer, List<SendmailBind>> submissionBinds;
        Set<String> certbotNames;
        if (defaultServer == null) {
          // Named instances may only exist when there is a default instance
          if (!namedServers.isEmpty()) {
            throw new SQLException("Named sendmail servers may not exist without the default unnamed instance: " + namedServers);
          }
          smtpBinds = null;
          smtpsBinds = null;
          submissionBinds = null;
          certbotNames = Collections.emptySet();
        } else {
          int size = sendmailServers.size();
          smtpBinds = AoCollections.newHashMap(size);
          smtpsBinds = AoCollections.newHashMap(size);
          submissionBinds = AoCollections.newHashMap(size);
          certbotNames = AoCollections.newHashSet(size);
          for (SendmailServer ss : sendmailServers) {
            List<SendmailBind> smtpList = new ArrayList<>();
            List<SendmailBind> smtpsList = new ArrayList<>();
            List<SendmailBind> submissionList = new ArrayList<>();
            List<SendmailBind> sbs = ss.getSendmailBinds();
            if (sbs.isEmpty()) {
              throw new SQLException("SendmailServer does not have any binds: " + ss);
            }
            for (SendmailBind sb : sbs) {
              String protocol = sb.getNetBind().getAppProtocol().getProtocol();
              if (AppProtocol.SMTP.equals(protocol)) {
                smtpList.add(sb);
              } else if (AppProtocol.SMTPS.equals(protocol)) {
                smtpsList.add(sb);
              } else if (AppProtocol.SUBMISSION.equals(protocol)) {
                submissionList.add(sb);
              } else {
                throw new AssertionError("Unexpected protocol for SendmailBind #" + sb.getPkey() + ": " + protocol);
              }
            }
            assert !smtpList.isEmpty() || !smtpsList.isEmpty() || !submissionList.isEmpty();
            smtpBinds.put(ss, smtpList);
            smtpsBinds.put(ss, smtpsList);
            submissionBinds.put(ss, submissionList);
            String certbotName = ss.getServerCertificate().getCertbotName();
            if (certbotName != null) {
              certbotNames.add(certbotName);
            }
            certbotName = ss.getClientCertificate().getCertbotName();
            if (certbotName != null) {
              certbotNames.add(certbotName);
            }
          }
        }

        // This is set to true when needed and a single reload will be performed after all config files are updated
        boolean[] needsReload = {false};
        final boolean sendmailInstalled;
        final boolean sendmailCfInstalled;
        // Make sure packages installed
        if (defaultServer != null) {
          if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
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
            if (!namedServers.isEmpty()) {
              throw new SQLException("Only the unnamed default instance is supported on CentOS 5");
            }
          } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
            // Install aoserv-sendmail-config package if missing
            PackageManager.installPackage(
                PackageManager.PackageName.AOSERV_SENDMAIL_CONFIG,
                () -> needsReload[0] = true
            );
            // Install sendmail-n package as-needed
            if (!namedServers.isEmpty()) {
              PackageManager.installPackage(
                  PackageManager.PackageName.SENDMAIL_N,
                  () -> needsReload[0] = true
              );
            }
          } else {
            throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
          }
          sendmailInstalled = true;
          sendmailCfInstalled = true;
        } else {
          sendmailInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL) != null;
          sendmailCfInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL_CF) != null;
        }

        // Install ca-trust-hash as-needed
        boolean caTrustHashSupported;
        boolean caTrustHashNeeded;
        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          caTrustHashSupported = false;
          caTrustHashNeeded = false;
        } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
          caTrustHashSupported = true;
          caTrustHashNeeded = false;
          for (SendmailServer ss : sendmailServers) {
            Certificate serverCert = ss.getServerCertificate();
            if (serverCert != null) {
              if (serverCert.getCertbotName() != null) {
                caTrustHashNeeded = true;
                break;
              }
              PosixPath chain = serverCert.getChainFile();
              if (chain != null && !ImapManager.DEFAULT_CA_FILE.equals(chain.toString())) {
                caTrustHashNeeded = true;
                break;
              }
            }
          }
        } else {
          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }
        if (caTrustHashNeeded) {
          if (!caTrustHashSupported) {
            throw new AssertionError(PackageManager.PackageName.CA_TRUST_HASH + " needed but not supported");
          }
          PackageManager.installPackage(PackageManager.PackageName.CA_TRUST_HASH);
        }

        Set<PosixFile> restorecon = new LinkedHashSet<>();
        try {
          boolean hasSpecificAddress = false;

          if (defaultServer != null) {
            // Resolve hasSpecificAddress
            assert smtpBinds != null;
            for (SendmailBind sb : smtpBinds.get(defaultServer)) {
              InetAddress ia = sb.getNetBind().getIpAddress().getInetAddress();
              if (!ia.isLoopback() && !ia.isUnspecified()) {
                hasSpecificAddress = true;
                break;
              }
            }
            if (!hasSpecificAddress) {
              assert smtpsBinds != null;
              for (SendmailBind sb : smtpsBinds.get(defaultServer)) {
                InetAddress ia = sb.getNetBind().getIpAddress().getInetAddress();
                if (!ia.isLoopback() && !ia.isUnspecified()) {
                  hasSpecificAddress = true;
                  break;
                }
              }
            }
            if (!hasSpecificAddress) {
              assert submissionBinds != null;
              for (SendmailBind sb : submissionBinds.get(defaultServer)) {
                InetAddress ia = sb.getNetBind().getIpAddress().getInetAddress();
                if (!ia.isLoopback() && !ia.isUnspecified()) {
                  hasSpecificAddress = true;
                  break;
                }
              }
            }
          }
          if (sendmailInstalled) {
            if (!certbotNames.isEmpty()) {
              if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                PackageManager.installPackage(PackageManager.PackageName.SENDMAIL_COPY_CERTIFICATES);
              } else {
                throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
              }
              boolean needCopy = false;
              // Create any missing directories or links
              for (String name : certbotNames) {
                PosixFile dir = new PosixFile(CERTIFICATE_COPY_DIRECTORY, name, true);
                if (!dir.getStat().exists()) {
                  dir.mkdir();
                  needCopy = true;
                }
                PosixFile keySource = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_KEY + SOURCE_SUFFIX, true);
                if (!keySource.getStat().exists()) {
                  keySource.symLink(LETS_ENCRYPT_SYMLINK_PREFIX + name + ImapManager.LETS_ENCRYPT_KEY);
                  needCopy = true;
                }
                PosixFile certSource = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_CERT + SOURCE_SUFFIX, true);
                if (!certSource.getStat().exists()) {
                  certSource.symLink(LETS_ENCRYPT_SYMLINK_PREFIX + name + ImapManager.LETS_ENCRYPT_CERT);
                  needCopy = true;
                }
                PosixFile chainSource = new PosixFile(dir, ImapManager.CERTIFICATE_COPY_CHAIN + SOURCE_SUFFIX, true);
                if (!chainSource.getStat().exists()) {
                  chainSource.symLink(LETS_ENCRYPT_SYMLINK_PREFIX + name + ImapManager.LETS_ENCRYPT_CHAIN);
                  needCopy = true;
                }
              }
              if (needCopy) {
                AOServDaemon.exec("/etc/pki/sendmail/copy/copy-certificates");
              }
            }
            // Iterate through all servers, and a "null" iteration when there are no servers
            for (SendmailServer sendmailServer : sendmailServers.isEmpty()
              ? new SendmailServer[]{null}
              : sendmailServers.toArray(new SendmailServer[sendmailServers.size()])
            ) {
              // Build the new version of /etc/mail/sendmail[@*].mc in RAM
              PosixFile sendmailMc = getSendmailMc(sendmailServer);
              boolean sendmailMcUpdated;
              {
                bout.reset();
                try (ChainWriter out = new ChainWriter(bout)) {
                  if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    buildSendmailMcCentOS5(
                        out,
                        thisServer,
                        sendmailServer,
                        (smtpBinds       == null) ? Collections.emptyList() : smtpBinds.get(sendmailServer),
                        (smtpsBinds      == null) ? Collections.emptyList() : smtpsBinds.get(sendmailServer),
                        (submissionBinds == null) ? Collections.emptyList() : submissionBinds.get(sendmailServer)
                    );
                  } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                    buildSendmailMcCentOS7(
                        out,
                        thisServer,
                        sendmailServer,
                        (smtpBinds       == null) ? Collections.emptyList() : smtpBinds.get(sendmailServer),
                        (smtpsBinds      == null) ? Collections.emptyList() : smtpsBinds.get(sendmailServer),
                        (submissionBinds == null) ? Collections.emptyList() : submissionBinds.get(sendmailServer)
                    );
                  } else {
                    throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                  }
                }
                // Write the new file if it is different than the old
                sendmailMcUpdated = DaemonFileUtils.atomicWrite(
                    sendmailMc,
                    bout.toByteArray(),
                    0644,
                    PosixFile.ROOT_UID,
                    PosixFile.ROOT_GID,
                    null,
                    restorecon
                );
              }

              // Rebuild the /etc/sendmail.cf file if doesn't exist or modified time is before sendmail.mc
              if (sendmailCfInstalled) {
                PosixFile sendmailCf = getSendmailCf(sendmailServer);
                Stat sendmailMcStat = sendmailMc.getStat();
                Stat sendmailCfStat = sendmailCf.getStat();
                if (
                    sendmailMcUpdated
                        || !sendmailCfStat.exists()
                        || sendmailCfStat.getModifyTime() < sendmailMcStat.getModifyTime()
                ) {
                  // Build to RAM to compare
                  byte[] cfNewBytes = AOServDaemon.execAndCaptureBytes("/usr/bin/m4", sendmailMc.getPath());
                  if (
                      DaemonFileUtils.atomicWrite(
                          sendmailCf,
                          cfNewBytes,
                          0644,
                          PosixFile.ROOT_UID,
                          PosixFile.ROOT_GID,
                          null,
                          restorecon
                      )
                  ) {
                    needsReload[0] = true;
                  } else {
                    // No change, just update modified time
                    sendmailCf.utime(sendmailCfStat.getAccessTime(), sendmailMcStat.getModifyTime());
                  }
                }
              }
            }

            // Build the new version of /etc/mail/submit.mc in RAM
            boolean submitMcUpdated;
            {
              bout.reset();
              try (ChainWriter out = new ChainWriter(bout)) {
                if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                  // Submit will always be on the primary IP address
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
                      + "define(`confPROCESS_TITLE_PREFIX',`").print(thisServer.getHostname()).print("')dnl\n");
                } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                  InetAddress submitAddress;
                  if (defaultServer == null) {
                    submitAddress = null;
                  } else {
                    // Find Bind listing on SMTP on port 25, preferring primaryIpAddress
                    // TODO: Prefer 127.0.0.1 over primary?
                    final int MSP_PORT = 25;
                    InetAddress primaryAddress = primaryIpAddress.getInetAddress();
                    assert smtpBinds != null;
                    submitAddress = findSmtpAddress(
                        primaryAddress.getProtocolFamily(),
                        primaryAddress,
                        smtpBinds.get(defaultServer),
                        MSP_PORT
                    );
                    if (submitAddress == null) {
                      // TODO: Could look for smtp on ports other than 25?
                      // TODO: Could then try port 587?  Possibly not since it requires authentication?
                      // TODO: Could then try port 465?  SSL requirements a problem?
                      throw new SQLException("Unable to find any SMTP on port " + MSP_PORT + " for submit.mc");
                    }
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
                      + "FEATURE(`msp', `[").print((submitAddress == null) ? "127.0.0.1" : submitAddress.toString()).print("]')dnl\n");
                } else {
                  throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                }
              }
              // Write the new file if it is different than the old
              submitMcUpdated = DaemonFileUtils.atomicWrite(
                  submitMc,
                  bout.toByteArray(),
                  0644,
                  PosixFile.ROOT_UID,
                  PosixFile.ROOT_GID,
                  null,
                  restorecon
              );
            }

            // Rebuild the /etc/submit.cf file if doesn't exist or modified time is before submit.mc
            if (sendmailCfInstalled) {
              Stat submitMcStat = submitMc.getStat();
              Stat submitCfStat = submitCf.getStat();
              if (
                  submitMcUpdated
                      || !submitCfStat.exists()
                      || submitCfStat.getModifyTime() < submitMcStat.getModifyTime()
              ) {
                // Build to RAM to compare
                byte[] cfNewBytes = AOServDaemon.execAndCaptureBytes("/usr/bin/m4", submitMc.getPath());
                if (
                    DaemonFileUtils.atomicWrite(
                        submitCf,
                        cfNewBytes,
                        0644,
                        PosixFile.ROOT_UID,
                        PosixFile.ROOT_GID,
                        null,
                        restorecon
                    )
                ) {
                  needsReload[0] = true;
                } else {
                  // No change, just update modified time
                  submitCf.utime(submitCfStat.getAccessTime(), submitMcStat.getModifyTime());
                }
              }
            }

            // SELinux before next steps
            DaemonFileUtils.restorecon(restorecon);
            restorecon.clear();

            if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
              if (defaultServer == null) {
                // Sendmail installed but disabled
                // Stop service if running
                if (subsysLockFile.exists()) {
                  AOServDaemon.exec("/etc/rc.d/init.d/sendmail", "stop");
                  if (subsysLockFile.exists()) {
                    throw new IOException(subsysLockFile.getPath() + " still exists after service stop");
                  }
                }
                // chkconfig off if needed
                if (sendmailRcFile.getStat().exists()) {
                  AOServDaemon.exec("/sbin/chkconfig", "sendmail", "off");
                  if (sendmailRcFile.getStat().exists()) {
                    throw new IOException(sendmailRcFile.getPath() + " still exists after chkconfig off");
                  }
                }
              } else {
                // Sendmail installed and enabled
                // chkconfig on if needed
                if (!sendmailRcFile.getStat().exists()) {
                  AOServDaemon.exec("/sbin/chkconfig", "sendmail", "on");
                  if (!sendmailRcFile.getStat().exists()) {
                    throw new IOException(sendmailRcFile.getPath() + " still does not exist after chkconfig on");
                  }
                }
                // Start service if not running
                if (!subsysLockFile.exists()) {
                  AOServDaemon.exec("/etc/rc.d/init.d/sendmail", "start");
                  if (!subsysLockFile.exists()) {
                    throw new IOException(subsysLockFile.getPath() + " still does not exist after service start");
                  }
                } else {
                  // Reload if needed
                  if (needsReload[0]) {
                    AOServDaemon.exec("/etc/rc.d/init.d/sendmail", "reload");
                  }
                }
              }
            } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
              // Enable instances
              Set<String> dontDeleteFilenames = AoCollections.newHashSet(sendmailServers.size());
              for (SendmailServer ss : sendmailServers) {
                String escapedName = ss.getSystemdEscapedName();
                String filename = (escapedName == null) ? "sendmail.service" : ("sendmail@" + escapedName + ".service");
                dontDeleteFilenames.add(filename);
                PosixFile link = new PosixFile(HttpdServerManager.MULTI_USER_WANTS_DIRECTORY, filename);
                if (!link.getStat().exists()) {
                  // Make start at boot
                  AOServDaemon.exec(
                      "/usr/bin/systemctl",
                      "enable",
                      filename
                  );
                  if (!link.getStat().exists()) {
                    throw new AssertionError("Link does not exist after systemctl enable: " + link);
                  }
                  // Make reload
                  needsReload[0] = true;
                }
              }

              // Stop and disable instances that should no longer exist
              String[] list = new File(HttpdServerManager.MULTI_USER_WANTS_DIRECTORY).list();
              if (list != null) {
                for (String filename : list) {
                  if (
                      !dontDeleteFilenames.contains(filename)
                          && (
                          "sendmail.service".equals(filename)
                              || SENDMAIL_NAME_SERVICE_REGEXP.matcher(filename).matches()
                      )
                  ) {
                    AOServDaemon.exec(
                        "/usr/bin/systemctl",
                        "stop",
                        filename
                    );
                    AOServDaemon.exec(
                        "/usr/bin/systemctl",
                        "disable",
                        filename
                    );
                    PosixFile link = new PosixFile(HttpdServerManager.MULTI_USER_WANTS_DIRECTORY, filename);
                    if (link.getStat().exists()) {
                      throw new AssertionError("Link exists after systemctl disable: " + link);
                    }
                  }
                }
              }
              if (defaultServer != null) {
                // Sendmail installed and enabled
                if (needsReload[0]) {
                  // Stop all named instances, restart default, then start all named instances during reload, in case IPs/ports moved between instances
                  if (!namedServers.isEmpty()) {
                    String[] command = new String[2 + namedServers.size()];
                    int i = 0;
                    command[i++] = "/usr/bin/systemctl";
                    command[i++] = "stop";
                    for (SendmailServer namedServer : namedServers) {
                      command[i++] = "sendmail@" + namedServer.getSystemdEscapedName() + ".service";
                    }
                    assert i == command.length;
                    AOServDaemon.exec(command);
                  }
                  AOServDaemon.exec("/usr/bin/systemctl", "restart", "sendmail.service");
                  if (!namedServers.isEmpty()) {
                    String[] command = new String[2 + namedServers.size()];
                    int i = 0;
                    command[i++] = "/usr/bin/systemctl";
                    command[i++] = "start";
                    for (SendmailServer namedServer : namedServers) {
                      command[i++] = "sendmail@" + namedServer.getSystemdEscapedName() + ".service";
                    }
                    assert i == command.length;
                    AOServDaemon.exec(command);
                  }
                } else {
                  // Call "start" on all, just in case the service has failed or build process was previously interrupted
                  String[] command = new String[3 + namedServers.size()];
                  int i = 0;
                  command[i++] = "/usr/bin/systemctl";
                  command[i++] = "start";
                  command[i++] = "sendmail.service";
                  for (SendmailServer namedServer : namedServers) {
                    command[i++] = "sendmail@" + namedServer.getSystemdEscapedName() + ".service";
                  }
                  assert i == command.length;
                  AOServDaemon.exec(command);
                }
                // Install sendmail-after-network-online package on CentOS 7 when needed
                if (hasSpecificAddress) {
                  PackageManager.installPackage(PackageManager.PackageName.SENDMAIL_AFTER_NETWORK_ONLINE);
                }
              }
              // Backup and delete secondary instances that no longer exist before any restarts
              int size = sendmailServers.size();
              Set<String> expectedSendmailCf = AoCollections.newLinkedHashSet(size);
              Set<String> expectedSendmailMc = AoCollections.newLinkedHashSet(size);
              Set<String> expectedSendmailPid = AoCollections.newLinkedHashSet(size);
              Set<String> expectedStatistics = AoCollections.newLinkedHashSet(size);
              Set<String> expectedMqueue = AoCollections.newLinkedHashSet(size);
              for (SendmailServer namedServer : namedServers) {
                String systemdName = namedServer.getSystemdEscapedName();
                expectedSendmailCf.add("sendmail@" + systemdName + ".cf");
                expectedSendmailMc.add("sendmail@" + systemdName + ".mc");
                expectedSendmailPid.add("sendmail@" + systemdName + ".pid");
                expectedStatistics.add("statistics@" + systemdName);
                expectedMqueue.add("mqueue@" + systemdName);
              }
              List<File> deleteFileList = new ArrayList<>();
              // /etc/mail/sendmail@*.cf and /etc/mail/sendmail@*.mc
              File etcMail = new File("/etc/mail");
              list = etcMail.list();
              if (list != null) {
                for (String filename : list) {
                  if (
                      !expectedSendmailCf.contains(filename)
                          && !expectedSendmailMc.contains(filename)
                          && (
                          SENDMAIL_NAME_CF_REGEXP.matcher(filename).matches()
                              || SENDMAIL_NAME_MC_REGEXP.matcher(filename).matches()
                      )
                  ) {
                    deleteFileList.add(new File(etcMail, filename));
                  }
                }
              }
              // /run/sendmail@*.pid (Unexpected should not exist - exception if does)
              File run = new File("/run");
              list = run.list();
              if (list != null) {
                for (String filename : list) {
                  if (
                      !expectedSendmailPid.contains(filename)
                          && SENDMAIL_NAME_PID_REGEXP.matcher(filename).matches()
                  ) {
                    File pidFile = new File(run, filename);
                    throw new IOException("Unexpected sendmail PID file exists: " + pidFile);
                  }
                }
              }
              // /var/log/mail/statistics@*
              File varLogMail = new File("/var/log/mail");
              list = varLogMail.list();
              if (list != null) {
                for (String filename : list) {
                  if (
                      !expectedStatistics.contains(filename)
                          && STATISTICS_NAME_REGEXP.matcher(filename).matches()
                  ) {
                    deleteFileList.add(new File(varLogMail, filename));
                  }
                }
              }
              // /var/spool/mqueue@*
              File varSpool = new File("/var/spool");
              list = varSpool.list();
              if (list != null) {
                for (String filename : list) {
                  if (
                      !expectedMqueue.contains(filename)
                          && MQUEUE_NAME_REGEXP.matcher(filename).matches()
                  ) {
                    deleteFileList.add(new File(varSpool, filename));
                  }
                }
              }
              BackupManager.backupAndDeleteFiles(deleteFileList);
            } else {
              throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
            }
            if (
                osvId == OperatingSystemVersion.CENTOS_7_X86_64
                    && AOServDaemonConfiguration.isPackageManagerUninstallEnabled()
            ) {
              // Uninstall sendmail-after-network-online package on CentOS 7 when not needed
              if (!hasSpecificAddress) {
                PackageManager.removePackage(PackageManager.PackageName.SENDMAIL_AFTER_NETWORK_ONLINE);
              }
              // Uninstall sendmail-n package on CentOS 7 when not needed
              if (namedServers.isEmpty()) {
                PackageManager.removePackage(PackageManager.PackageName.SENDMAIL_N);
              }
            }
          } else {
            assert certbotNames.isEmpty();
          }
          // Cleanup certificate copies
          Stat copyDirStat = CERTIFICATE_COPY_DIRECTORY.getStat();
          if (copyDirStat.exists() && copyDirStat.isDirectory()) {
            // Delete any extra directories
            String[] list = CERTIFICATE_COPY_DIRECTORY.list();
            if (list != null) {
              List<File> deleteFileList = new ArrayList<>();
              for (String filename : list) {
                if (!certbotNames.contains(filename)) {
                  PosixFile uf = new PosixFile(CERTIFICATE_COPY_DIRECTORY, filename, true);
                  if (uf.getStat().isDirectory()) {
                    deleteFileList.add(uf.getFile());
                  }
                }
              }
              BackupManager.backupAndDeleteFiles(deleteFileList);
            }
          }
          // Remove package if not needed
          if (
              certbotNames.isEmpty()
                  && AOServDaemonConfiguration.isPackageManagerUninstallEnabled()
          ) {
            PackageManager.removePackage(PackageManager.PackageName.SENDMAIL_COPY_CERTIFICATES);
          }
        } finally {
          DaemonFileUtils.restorecon(restorecon);
        }
        // Remove ca-trust-hash package if not needed
        if (
            caTrustHashSupported
                && !caTrustHashNeeded
                && AOServDaemonConfiguration.isPackageManagerUninstallEnabled()
        ) {
          PackageManager.removePackage(PackageManager.PackageName.CA_TRUST_HASH);
        }
      }
      return true;
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    Server thisServer = AOServDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
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
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
        ) {
          AOServConnector conn = AOServDaemon.getConnector();
          sendmailCFManager = new SendmailCFManager();
          conn.getLinux().getServer().addTableListener(sendmailCFManager, 0);
          conn.getNet().getIpAddress().addTableListener(sendmailCFManager, 0);
          conn.getNet().getBind().addTableListener(sendmailCFManager, 0);
          conn.getEmail().getSendmailBind().addTableListener(sendmailCFManager, 0);
          conn.getEmail().getSendmailServer().addTableListener(sendmailCFManager, 0);
          conn.getNet().getHost().addTableListener(sendmailCFManager, 0);
          conn.getPki().getCertificate().addTableListener(sendmailCFManager, 0);
          //conn.getServerFarms().addTableListener(sendmailCFManager, 0);
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
   * <p>
   * This is used to know when to enable saslauthd (See {@link SaslauthdManager}.
   * </p>
   *
   * @see AppProtocol#SMTP
   * @see AppProtocol#SMTPS
   * @see AppProtocol#SUBMISSION
   */
  public static boolean isSendmailEnabled() throws IOException, SQLException {
    return !AOServDaemon.getThisServer().getSendmailServers().isEmpty();
  }
}
