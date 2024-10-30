/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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
import com.aoapps.hodgepodge.io.FilesystemIteratorRule;
import com.aoapps.hodgepodge.util.Tuple3;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.Strings;
import com.aoapps.lang.io.FileUtils;
import com.aoapps.lang.validation.ValidationException;
import com.aoapps.net.DomainName;
import com.aoapps.net.InetAddress;
import com.aoapps.net.Port;
import com.aoapps.sql.SQLUtility;
import com.aoapps.tempfiles.TempFile;
import com.aoapps.tempfiles.TempFileContext;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.backup.FileReplication;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.CyrusImapdBind;
import com.aoindustries.aoserv.client.email.CyrusImapdServer;
import com.aoindustries.aoserv.client.email.SpamAssassinMode;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.password.PasswordGenerator;
import com.aoindustries.aoserv.client.pki.Certificate;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.net.fail2ban.Fail2banManager;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.ACL;
import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.Rights;
import com.sun.mail.imap.protocol.IMAPResponse;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Header;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.ReadOnlyFolderException;
import javax.mail.Session;
import javax.mail.StoreClosedException;

/**
 * Any IMAP/Cyrus-specific features are here.
 *
 * <p>TODO: Once conversion done:
 *     0) Look for any /home/?/???/MoveToCyrus folders (www2.kc.aoindustries.com:smurphy is one)
 *     1) Set WUIMAP_CONVERSION_ENABLED to false
 *     2) Make sure all gone in /home/?/???/Mail and /home/?/???/.mailboxlist
 *     - Then after a while -
 *     3) rm -rf /opt/imap-2007d
 *     4) rm -rf /var/opt/imap-2007d</p>
 *
 * <p>TODO: Future
 *     Control the synchronous mode for ext2/ext3 automatically?
 *         file:///home/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
 *         cd /var/imap
 *         chattr +S user quota user/* quota/*
 *         chattr +S /var/spool/imap /var/spool/imap/*
 *         chattr +S /var/spool/mqueue
 *     sieve to replace procmail and allow more directly delivery
 *         sieveusehomedir
 *         sieveshell:
 *             sieveshell --authname=cyrus.test@suspendo.aoindustries.com 192.168.1.12
 *             /bin/su -s /bin/bash -c "/usr/bin/sieveshell 192.168.1.12" cyrus.test@suspendo.aoindustries.com
 *         procmail migration script here: http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/MboxCyrusMigration
 *     Run chk_cyrus from NOC?
 *     Backups:
 *           stop master, snapshot, start master
 *           Or, without snapshots, do ctl_mboxlist -d
 *               http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/Backup
 *           Also, don't back-up Junk folders?
 *     Add smmapd support
 *     Consider https://wikipedia.org/wiki/Application_Configuration_Access_Protocol or https://wikipedia.org/wiki/Application_Configuration_Access_Protocol
 *     Look for any "junk" flags for Cyrus folders - if exists can train off this instead of requiring move to/from Junk</p>
 *
 * <p>TODO: SELinux port management for non-standard ports
 * TODO:   pop_port_t for 110, 143, 993, 995 (and on custom ports)
 * TODO:   sieve_port_t for Sieve on 4190 (and on custom ports)</p>
 *
 * <p>TODO: allow lmtp-only config to support receiving-only server (without any POP3/IMAP)
 * TODO:   This might be a server configured with Sieve port only.</p>
 *
 * <p>TODO: Auto-backup directories in /var/lib/imap and /var/spool/imap before removing inbox.</p>
 *
 * @author  AO Industries, Inc.
 */
// TODO: ROCKY_9_X86_64
public final class ImapManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(ImapManager.class.getName());

  public static final boolean WUIMAP_CONVERSION_ENABLED = false;
  private static final int WUIMAP_CONVERSION_CONCURRENCY = 20;

  public static final File mailSpool = new File("/var/spool/mail");

  private static final File imapSpool = new File("/var/spool/imap");
  private static final File imapVirtDomainSpool = new File(imapSpool, "domain");
  private static final File subsysLockFile = new File("/var/lock/subsys/cyrus-imapd");

  private static final PosixFile cyrusRcFile = new PosixFile("/etc/rc.d/rc3.d/S65cyrus-imapd");
  private static final PosixFile cyrusConfFile = new PosixFile("/etc/cyrus.conf");
  private static final PosixFile imapdConfFile = new PosixFile("/etc/imapd.conf");

  private static final PosixFile wuBackupDirectory = new PosixFile("/var/opt/imap-2007d/backup");

  /**
   * These directories may be in the imapSpool and will be ignored.
   */
  private static final Set<String> imapSpoolIgnoreDirectories = new HashSet<>();

  static {
    imapSpoolIgnoreDirectories.add("domain");
    imapSpoolIgnoreDirectories.add("stage.");
  }

  private static final int IMAP_PREFORK_MAX = 5;
  private static final int IMAP_PREFORK_MIN = 1;
  private static final int IMAPS_PREFORK_MAX = 5;
  private static final int IMAPS_PREFORK_MIN = 1;
  private static final int POP3_PREFORK_MAX = 3;
  private static final int POP3_PREFORK_MIN = 1;
  private static final int POP3S_PREFORK_MAX = 3;
  private static final int POP3S_PREFORK_MIN = 1;

  private static ImapManager imapManager;

  /**
   * The CA file used when none specified.
   */
  static final String DEFAULT_CA_FILE = "/etc/pki/tls/certs/ca-bundle.crt";

  /**
   * The directory that Let's Encrypt certificates are copied to.
   * Matches the path in cyrus-imapd-copy-certificates
   */
  private static final PosixFile CERTIFICATE_COPY_DIRECTORY = new PosixFile("/etc/pki/cyrus-imapd/copy");

  /**
   * The filenames used for copies of certificates.
   */
  static final String
      CERTIFICATE_COPY_KEY = "key.pem",
      CERTIFICATE_COPY_CERT = "cert.pem",
      CERTIFICATE_COPY_CHAIN = "chain.pem";

  /**
   * The prefix of Let's Encrypt links generated from a copy directory.
   *
   * @see  #CERTIFICATE_COPY_DIRECTORY
   */
  private static final String LETS_ENCRYPT_SYMLINK_PREFIX = "../../../../letsencrypt/live/";

  /**
   * The filenames used by Let's Encrypt certificates.
   */
  static final String
      LETS_ENCRYPT_KEY = "/privkey.pem",
      LETS_ENCRYPT_CERT = "/" + CERTIFICATE_COPY_CERT,
      LETS_ENCRYPT_CHAIN = "/" + CERTIFICATE_COPY_CHAIN;

  /**
   * The suffix used for links to source files for cyrus-imapd-copy-certificates.
   */
  private static final String SOURCE_SUFFIX = "-source";

  private ImapManager() {
    // Do nothing
  }

  private static final Map<Tuple3<InetAddress, Port, Boolean>, Session> _sessions = new HashMap<>();

  /**
   * Gets a cached Session.
   */
  private static Session getSession(Tuple3<InetAddress, Port, Boolean> imapServer) {
    synchronized (_sessions) {
      Session session = _sessions.get(imapServer);
      if (session == null) {
        // Create and cache new session
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", imapServer.getElement1().toString());
        props.put("mail.imap.port", Integer.toString(imapServer.getElement2().getPort()));
        //props.put("mail.transport.protocol", "smtp");
        //props.put("mail.smtp.auth", "true");
        //props.put("mail.from", "cyrus@" + AoservDaemon.getThisAOServer().getHostname()); // Sendmail server name if used again
        if (imapServer.getElement3()) {
          props.put("mail.imap.starttls.enable", "true");
          props.put("mail.imap.starttls.required", "true");
        }
        session = Session.getInstance(props, null);
        // session.setDebug(true);
        _sessions.put(imapServer, session);
      }
      return session;
    }
  }

  /**
   * Gets the IP address that should be used for admin access to the server.
   * It will first try to use the Primary IP address on the machine.  If that
   * doesn't have an IMAP server, then it will search all IP
   * addresses and use the first one with an IMAP server.
   *
   * @return  The (IP address, port, starttls) or <code>null</code> if not an IMAP server.
   */
  private static Tuple3<InetAddress, Port, Boolean> getImapServer() throws IOException, SQLException {
    Server thisServer = AoservDaemon.getThisServer();
    CyrusImapdServer cyrusServer = thisServer.getCyrusImapdServer();
    if (cyrusServer == null) {
      return null;
    }
    // Look for primary IP match
    InetAddress primaryIp = thisServer.getPrimaryIpAddress().getInetAddress();
    Tuple3<InetAddress, Port, Boolean> firstImap = null;
    for (CyrusImapdBind cib : cyrusServer.getCyrusImapdBinds()) {
      Bind nb = cib.getNetBind();
      if (nb.getAppProtocol().getProtocol().equals(AppProtocol.IMAP2)) {
        InetAddress ip = nb.getIpAddress().getInetAddress();
        boolean tls;
          {
            Boolean allowPlaintextAuth = cib.getAllowPlaintextAuth();
            if (allowPlaintextAuth == null) {
              allowPlaintextAuth = cyrusServer.getAllowPlaintextAuth();
            }
            tls = !allowPlaintextAuth;
          }
        if (ip.equals(primaryIp)) {
          return new Tuple3<>(primaryIp, nb.getPort(), tls);
        }
        if (firstImap == null) {
          firstImap = new Tuple3<>(ip, nb.getPort(), tls);
        }
      }
    }
    return firstImap;
  }

  private static final Object _adminStoreLock = new Object();
  private static Session _adminSession;
  private static IMAPStore _adminStore;

  /**
   * Gets the IMAPStore for admin control or <code>null</code> if not an IMAP server.
   */
  private static IMAPStore getAdminStore() throws IOException, SQLException, MessagingException {
    // Get things that may failed externally before allocating session and store
    Tuple3<InetAddress, Port, Boolean> imapServer = getImapServer();
    if (imapServer == null) {
      return null;
    }
    String user = User.CYRUS + "@default";
    String password = AoservDaemonConfiguration.getCyrusPassword();
    Session session = getSession(imapServer);
    synchronized (_adminStoreLock) {
      if (_adminSession != session || _adminStore == null) {
        // Create and cache new store here
        IMAPStore newStore = (IMAPStore) session.getStore();
        newStore.connect(user, password);
        _adminSession = session;
        _adminStore = newStore;
      }
      return _adminStore;
    }
  }

  /**
   * Closes IMAPStore.
   */
  private static void closeAdminStore() {
    synchronized (_adminStoreLock) {
      if (_adminStore != null) {
        try {
          _adminStore.close();
        } catch (MessagingException err) {
          logger.log(Level.SEVERE, null, err);
        }
        _adminSession = null;
        _adminStore = null;
      }
    }
  }

  /**
   * Gets access to the old IMAPStore for wu-imapd.
   */
  private static IMAPStore getOldUserStore(
      PrintWriter logOut,
      User.Name username,
      String[] tempPassword,
      PosixFile passwordBackup
  ) throws IOException, SQLException, MessagingException {
    if (!WUIMAP_CONVERSION_ENABLED) {
      throw new AssertionError();
    }
    Port wuPort;
    try {
      wuPort = Port.valueOf(8143, com.aoapps.net.Protocol.TCP);
    } catch (ValidationException e) {
      throw new AssertionError("This hard-coded port must be valid", e);
    }
    return getUserStore(
        logOut,
        new Tuple3<>(
            AoservDaemon.getThisServer().getPrimaryIpAddress().getInetAddress(),
            wuPort,
            false
        ),
        username,
        username.toString(),
        tempPassword,
        passwordBackup
    );
  }

  /**
   * Gets access to the new IMAPStore for cyrus.
   */
  private static IMAPStore getNewUserStore(
      PrintWriter logOut,
      User.Name username,
      String[] tempPassword,
      PosixFile passwordBackup
  ) throws IOException, SQLException, MessagingException {
    if (!WUIMAP_CONVERSION_ENABLED) {
      throw new AssertionError();
    }
    Tuple3<InetAddress, Port, Boolean> imapServer = getImapServer();
    if (imapServer == null) {
      throw new IOException("Not an IMAP server");
    }
    String usernameStr = username.toString();
    return getUserStore(
        logOut,
        imapServer,
        username,
        usernameStr.indexOf('@') == -1 ? (usernameStr + "@default") : usernameStr,
        tempPassword,
        passwordBackup
    );
  }

  /**
   * Gets the IMAPStore for the provided user to the given IpAddress and port.
   */
  private static IMAPStore getUserStore(
      PrintWriter logOut,
      Tuple3<InetAddress, Port, Boolean> imapServer,
      User.Name username,
      String imapUsername,
      String[] tempPassword,
      PosixFile passwordBackup
  ) throws IOException, SQLException, MessagingException {
    if (!WUIMAP_CONVERSION_ENABLED) {
      throw new AssertionError();
    }
    // Reset the user password if needed
    String password = tempPassword[0];
    if (password == null) {
      // Backup the password
      if (!passwordBackup.getStat().exists()) {
        log(logOut, Level.FINE, username, "Backing-up password");
        String encryptedPassword = LinuxAccountManager.getEncryptedPassword(username).getElement1();
        try (
            TempFileContext tempFileContext = new TempFileContext(passwordBackup.getFile().getParentFile());
            TempFile tempFile = tempFileContext.createTempFile(passwordBackup.getFile().getName())
            ) {
          try (PrintWriter out = new PrintWriter(new FileOutputStream(tempFile.getFile()))) {
            out.println(encryptedPassword);
          }
          FileUtils.rename(tempFile.getFile(), passwordBackup.getFile());
        }
      }
      // Change the password to a random value
      password = PasswordGenerator.generatePassword();
      log(logOut, Level.FINE, username, "Setting password to " + password);
      LinuxAccountManager.setPassword(username, password, false);
      tempPassword[0] = password;
    }

    // Create the session
    Session session = getSession(imapServer);

    // Create new store
    IMAPStore store = (IMAPStore) session.getStore();
    store.connect(imapUsername, password);
    return store;
  }

  private static String generateServiceName(String first, String base, int number) {
    if (number == 1) {
      return first;
    }
    return base + number;
  }

  /**
   * Determines if the Cyrus configuration has any secondary service names.
   * This will trigger the installation of the {@link PackageManager.PackageName#FAIL2BAN_FILTER_CYRUS_IMAP_MORE_SERVICES}
   * package.
   *
   * @see  Fail2banManager
   */
  public static boolean hasSecondaryService() throws IOException, SQLException {
    CyrusImapdServer cyrusServer = AoservDaemon.getThisServer().getCyrusImapdServer();
    if (cyrusServer != null) {
      Set<AppProtocol> foundProtocols = new HashSet<>();
      for (CyrusImapdBind cib : cyrusServer.getCyrusImapdBinds()) {
        if (!foundProtocols.add(cib.getNetBind().getAppProtocol())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Gets the Cyrus protocol for the given protocl and address family.
   */
  private static String getCyrusProtocol(com.aoapps.net.Protocol protocol, ProtocolFamily family) {
    if (protocol == com.aoapps.net.Protocol.TCP) {
      if (family.equals(StandardProtocolFamily.INET)) {
        return "tcp4";
      } else if (family.equals(StandardProtocolFamily.INET6)) {
        return "tcp6";
      } else {
        throw new IllegalArgumentException("Unexpected family: " + family);
      }
    } else if (protocol == com.aoapps.net.Protocol.UDP) {
      if (family.equals(StandardProtocolFamily.INET)) {
        return "udp4";
      } else if (family.equals(StandardProtocolFamily.INET6)) {
        return "udp6";
      } else {
        throw new IllegalArgumentException("Unexpected family: " + family);
      }
    } else {
      throw new IllegalArgumentException("Unexpected protocol: " + protocol);
    }
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    boolean isFine = logger.isLoggable(Level.FINE);
    try {
      Server thisServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (
          osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
              || osvId == OperatingSystemVersion.CENTOS_7_X86_64
      ) {
        // Used inside synchronized block
        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        synchronized (rebuildLock) {
          CyrusImapdServer cyrusServer = thisServer.getCyrusImapdServer();

          List<CyrusImapdBind> imapBinds;
          List<CyrusImapdBind> imapsBinds;
          List<CyrusImapdBind> pop3Binds;
          List<CyrusImapdBind> pop3sBinds;
          Bind sieveBind;
          Set<String> certbotNames;
          if (cyrusServer == null) {
            imapBinds = Collections.emptyList();
            imapsBinds = Collections.emptyList();
            pop3Binds = Collections.emptyList();
            pop3sBinds = Collections.emptyList();
            sieveBind = null;
            certbotNames = Collections.emptySet();
          } else {
            imapBinds = new ArrayList<>();
            imapsBinds = new ArrayList<>();
            pop3Binds = new ArrayList<>();
            pop3sBinds = new ArrayList<>();
            certbotNames = new HashSet<>();
            for (CyrusImapdBind cib : cyrusServer.getCyrusImapdBinds()) {
              String protocol = cib.getNetBind().getAppProtocol().getProtocol();
              if (AppProtocol.IMAP2.equals(protocol)) {
                imapBinds.add(cib);
              } else if (AppProtocol.SIMAP.equals(protocol)) {
                imapsBinds.add(cib);
              } else if (AppProtocol.POP3.equals(protocol)) {
                pop3Binds.add(cib);
              } else if (AppProtocol.SPOP3.equals(protocol)) {
                pop3sBinds.add(cib);
              } else {
                throw new AssertionError("Unexpected protocol for CyrusImapdBind #" + cib.getPkey() + ": " + protocol);
              }
              Certificate cibCertificate = cib.getCertificate();
              if (cibCertificate != null) {
                String certbotName = cibCertificate.getCertbotName();
                if (certbotName != null) {
                  certbotNames.add(certbotName);
                }
              }
            }
            sieveBind = cyrusServer.getSieveNetBind();
            String certbotName = cyrusServer.getCertificate().getCertbotName();
            if (certbotName != null) {
              certbotNames.add(certbotName);
            }
          }

          boolean[] needsReload = {false};
          final boolean cyrusImapdInstalled;
          // Install package if required
          if (cyrusServer != null) {
            if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
              PackageManager.installPackage(
                  PackageManager.PackageName.CYRUS_IMAPD,
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
            } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
              PackageManager.installPackage(
                  PackageManager.PackageName.AOSERV_IMAPD_CONFIG,
                  () -> needsReload[0] = true
              );
            } else {
              throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
            }
            cyrusImapdInstalled = true;
          } else {
            cyrusImapdInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.CYRUS_IMAPD) != null;
          }

          Set<PosixFile> restorecon = new LinkedHashSet<>();
          try {
            if (!certbotNames.isEmpty()) {
              if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                PackageManager.installPackage(PackageManager.PackageName.CYRUS_IMAPD_COPY_CERTIFICATES);
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
                PosixFile keySource = new PosixFile(dir, CERTIFICATE_COPY_KEY + SOURCE_SUFFIX, true);
                if (!keySource.getStat().exists()) {
                  keySource.symLink(LETS_ENCRYPT_SYMLINK_PREFIX + name + LETS_ENCRYPT_KEY);
                  needCopy = true;
                }
                PosixFile certSource = new PosixFile(dir, CERTIFICATE_COPY_CERT + SOURCE_SUFFIX, true);
                if (!certSource.getStat().exists()) {
                  certSource.symLink(LETS_ENCRYPT_SYMLINK_PREFIX + name + LETS_ENCRYPT_CERT);
                  needCopy = true;
                }
                PosixFile chainSource = new PosixFile(dir, CERTIFICATE_COPY_CHAIN + SOURCE_SUFFIX, true);
                if (!chainSource.getStat().exists()) {
                  chainSource.symLink(LETS_ENCRYPT_SYMLINK_PREFIX + name + LETS_ENCRYPT_CHAIN);
                  needCopy = true;
                }
              }
              if (needCopy) {
                AoservDaemon.exec("/etc/pki/cyrus-imapd/copy/copy-certificates");
              }
            }

            boolean hasSpecificAddress = false;

            // If there are no IMAP(S)/POP3(S) binds, do not run cyrus-imapd
            if (imapBinds.isEmpty() && imapsBinds.isEmpty() && pop3Binds.isEmpty() && pop3sBinds.isEmpty()) {
              // Should not have any sieve binds
              if (sieveBind != null) {
                throw new SQLException("Should not have sieve without any imap, imaps, pop3, or pop3s");
              }

              if (cyrusImapdInstalled) {
                if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                  // Stop service if running
                  if (subsysLockFile.exists()) {
                    if (isFine) {
                      logger.fine("Stopping cyrus-imapd service");
                    }
                    AoservDaemon.exec("/etc/rc.d/init.d/cyrus-imapd", "stop");
                    if (subsysLockFile.exists()) {
                      throw new IOException(subsysLockFile.getPath() + " still exists after service stop");
                    }
                  }

                  // chkconfig off if needed
                  if (cyrusRcFile.getStat().exists()) {
                    if (isFine) {
                      logger.fine("Disabling cyrus-imapd service");
                    }
                    AoservDaemon.exec("/sbin/chkconfig", "cyrus-imapd", "off");
                    if (cyrusRcFile.getStat().exists()) {
                      throw new IOException(cyrusRcFile.getPath() + " still exists after chkconfig off");
                    }
                  }
                } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                  AoservDaemon.exec("/usr/bin/systemctl", "stop", "cyrus-imapd.service", "cyrus-imapd-set-deliver-permissions.service");
                  AoservDaemon.exec("/usr/bin/systemctl", "disable", "cyrus-imapd.service", "cyrus-imapd-set-deliver-permissions.service");
                } else {
                  throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                }

                // Delete config files if exist
                if (imapdConfFile.getStat().exists()) {
                  if (isFine) {
                    logger.log(Level.FINE, "Deleting unnecessary config file: {0}", imapdConfFile.getPath());
                  }
                  imapdConfFile.delete();
                }
                if (cyrusConfFile.getStat().exists()) {
                  if (isFine) {
                    logger.log(Level.FINE, "Deleting unnecessary config file: {0}", cyrusConfFile.getPath());
                  }
                  cyrusConfFile.delete();
                }
              }
            } else {
              assert cyrusServer != null;

                // Required IMAP at least once on any default port
                {
                  AoservConnector conn = AoservDaemon.getConnector();
                  AppProtocol imapProtocol = conn.getNet().getAppProtocol().get(AppProtocol.IMAP2);
                  if (imapProtocol == null) {
                    throw new SQLException("AppProtocol not found: " + AppProtocol.IMAP2);
                  }
                  Port defaultImapPort = imapProtocol.getPort();
                  boolean foundOnDefault = false;
                  for (CyrusImapdBind cib : imapBinds) {
                    if (cib.getNetBind().getPort() == defaultImapPort) {
                      foundOnDefault = true;
                      break;
                    }
                  }
                  if (!foundOnDefault) {
                    throw new SQLException("imap is required on a default port with any of imap, imaps, pop3, and pop3s");
                  }
                }

              // All services that support TLS or SSL will be added here
              Map<String, CyrusImapdBind> tlsServices = new LinkedHashMap<>();

                // Update /etc/cyrus.conf
                {
                  bout.reset();
                  try (ChainWriter out = new ChainWriter(bout)) {
                    out.print("#\n"
                        + "# Automatically generated by ").print(ImapManager.class.getName()).print("\n"
                        + "#\n"
                        + "START {\n"
                        + "  # do not delete this entry!\n"
                        + "  recover\tcmd=\"ctl_cyrusdb -r\"\n"
                        + "\n"
                        + "  # this is only necessary if using idled for IMAP IDLE\n"
                        + "  idled\t\tcmd=\"idled\"\n"
                        + "}\n"
                        + "\n"
                        + "# UNIX sockets start with a slash and are put into /var/lib/imap/sockets\n"
                        + "SERVICES {\n"
                        + "  # add or remove based on preferences\n");
                    // imap
                    {
                      out.print("#  imap\t\tcmd=\"imapd\" listen=\"imap\" prefork=5\n");
                      if (!imapBinds.isEmpty()) {
                        int prefork = Math.max(
                            IMAP_PREFORK_MAX / imapBinds.size(),
                            IMAP_PREFORK_MIN
                        );
                        int counter = 1;
                        for (CyrusImapdBind cib : imapBinds) {
                          Bind imapBind = cib.getNetBind();
                          Port port = imapBind.getPort();
                          if (port.getProtocol() != com.aoapps.net.Protocol.TCP) {
                            throw new SQLException("imap requires TCP protocol");
                          }
                          String serviceName = generateServiceName("imap", "imapd", counter++);
                          out.print("  ").print(serviceName);
                          if (serviceName.length() < 6) {
                            out.print('\t');
                          }
                          InetAddress ia = imapBind.getIpAddress().getInetAddress();
                          out
                              .print("\tcmd=\"imapd\" listen=\"[")
                              .print(ia.toString())
                              .print("]:")
                              .print(port.getPort())
                              .print("\" proto=\"")
                              .print(getCyrusProtocol(port.getProtocol(), ia.getProtocolFamily()))
                              .print("\" prefork=")
                              .print(prefork)
                              .print('\n');
                          tlsServices.put(serviceName, cib);
                          if (!ia.isLoopback() && !ia.isUnspecified()) {
                            hasSpecificAddress = true;
                          }
                        }
                      }
                    }
                    // imaps
                    {
                      out.print("#  imaps\t\tcmd=\"imapd -s\" listen=\"imaps\" prefork=1\n");
                      if (!imapsBinds.isEmpty()) {
                        int prefork = Math.max(
                            IMAPS_PREFORK_MAX / imapsBinds.size(),
                            IMAPS_PREFORK_MIN
                        );
                        int counter = 1;
                        for (CyrusImapdBind cib : imapsBinds) {
                          Bind imapsBind = cib.getNetBind();
                          Port port = imapsBind.getPort();
                          if (port.getProtocol() != com.aoapps.net.Protocol.TCP) {
                            throw new SQLException("imaps requires TCP protocol");
                          }
                          String serviceName = generateServiceName("imaps", "imaps", counter++);
                          out.print("  ").print(serviceName);
                          if (serviceName.length() < 6) {
                            out.print('\t');
                          }
                          InetAddress ia = imapsBind.getIpAddress().getInetAddress();
                          out
                              .print("\tcmd=\"imapd -s\" listen=\"[")
                              .print(ia.toString())
                              .print("]:").print(port.getPort())
                              .print("\" proto=\"")
                              .print(getCyrusProtocol(port.getProtocol(), ia.getProtocolFamily()))
                              .print("\" prefork=")
                              .print(prefork)
                              .print('\n');
                          tlsServices.put(serviceName, cib);
                          if (!ia.isLoopback() && !ia.isUnspecified()) {
                            hasSpecificAddress = true;
                          }
                        }
                      }
                    }
                    // pop3
                    {
                      out.print("#  pop3\t\tcmd=\"pop3d\" listen=\"pop3\" prefork=3\n");
                      if (!pop3Binds.isEmpty()) {
                        int prefork = Math.max(
                            POP3_PREFORK_MAX / pop3Binds.size(),
                            POP3_PREFORK_MIN
                        );
                        int counter = 1;
                        for (CyrusImapdBind cib : pop3Binds) {
                          Bind pop3Bind = cib.getNetBind();
                          Port port = pop3Bind.getPort();
                          if (port.getProtocol() != com.aoapps.net.Protocol.TCP) {
                            throw new SQLException("pop3 requires TCP protocol");
                          }
                          String serviceName = generateServiceName("pop3", "pop3d", counter++);
                          out.print("  ").print(serviceName);
                          if (serviceName.length() < 6) {
                            out.print('\t');
                          }
                          InetAddress ia = pop3Bind.getIpAddress().getInetAddress();
                          out
                              .print("\tcmd=\"pop3d\" listen=\"[")
                              .print(ia.toString())
                              .print("]:")
                              .print(port.getPort())
                              .print("\" proto=\"")
                              .print(getCyrusProtocol(port.getProtocol(), ia.getProtocolFamily()))
                              .print("\" prefork=")
                              .print(prefork)
                              .print('\n');
                          tlsServices.put(serviceName, cib);
                          if (!ia.isLoopback() && !ia.isUnspecified()) {
                            hasSpecificAddress = true;
                          }
                        }
                      }
                    }
                    // pop3s
                    {
                      out.print("#  pop3s\t\tcmd=\"pop3d -s\" listen=\"pop3s\" prefork=1\n");
                      if (!pop3sBinds.isEmpty()) {
                        int prefork = Math.max(
                            POP3S_PREFORK_MAX / pop3sBinds.size(),
                            POP3S_PREFORK_MIN
                        );
                        int counter = 1;
                        for (CyrusImapdBind cib : pop3sBinds) {
                          Bind pop3sBind = cib.getNetBind();
                          Port port = pop3sBind.getPort();
                          if (port.getProtocol() != com.aoapps.net.Protocol.TCP) {
                            throw new SQLException("pop3s requires TCP protocol");
                          }
                          String serviceName = generateServiceName("pop3s", "pop3s", counter++);
                          out.print("  ").print(serviceName);
                          if (serviceName.length() < 6) {
                            out.print('\t');
                          }
                          InetAddress ia = pop3sBind.getIpAddress().getInetAddress();
                          out
                              .print("\tcmd=\"pop3d -s\" listen=\"[")
                              .print(ia.toString())
                              .print("]:")
                              .print(port.getPort())
                              .print("\" proto=\"")
                              .print(getCyrusProtocol(port.getProtocol(), ia.getProtocolFamily()))
                              .print("\" prefork=")
                              .print(prefork)
                              .print('\n');
                          tlsServices.put(serviceName, cib);
                          if (!ia.isLoopback() && !ia.isUnspecified()) {
                            hasSpecificAddress = true;
                          }
                        }
                      }
                    }
                    // sieve
                    {
                      out.print("#  sieve\t\tcmd=\"timsieved\" listen=\"sieve\" prefork=0\n");
                      if (sieveBind != null) {
                        Port port = sieveBind.getPort();
                        if (port.getProtocol() != com.aoapps.net.Protocol.TCP) {
                          throw new SQLException("sieve requires TCP protocol");
                        }
                        String serviceName = "sieve";
                        out.print("  ").print(serviceName);
                        if (serviceName.length() < 6) {
                          out.print('\t');
                        }
                        InetAddress ia = sieveBind.getIpAddress().getInetAddress();
                        out
                            .print("\tcmd=\"timsieved\" listen=\"[")
                            .print(ia.toString())
                            .print("]:")
                            .print(port.getPort())
                            .print("\" proto=\"")
                            .print(getCyrusProtocol(port.getProtocol(), ia.getProtocolFamily()))
                            .print("\" prefork=0\n"
                            );
                        if (!ia.isLoopback() && !ia.isUnspecified()) {
                          hasSpecificAddress = true;
                        }
                      }
                    }
                    out.print("\n"
                        + "  # these are only necessary if receiving/exporting usenet via NNTP\n"
                        + "#  nntp\t\tcmd=\"nntpd\" listen=\"nntp\" prefork=3\n"
                        + "#  nntps\t\tcmd=\"nntpd -s\" listen=\"nntps\" prefork=1\n"
                        + "\n"
                        + "  # at least one LMTP is required for delivery\n"
                        + "#  lmtp\t\tcmd=\"lmtpd\" listen=\"lmtp\" prefork=0\n"
                        + "  lmtpunix\tcmd=\"lmtpd\" listen=\"/var/lib/imap/socket/lmtp\" prefork=1\n"
                        + "\n"
                        + "  # this is only necessary if using notifications\n"
                        + "#  notify\tcmd=\"notifyd\" listen=\"/var/lib/imap/socket/notify\" proto=\"udp\" prefork=1\n"
                        + "}\n"
                        + "\n"
                        + "EVENTS {\n"
                        + "  # this is required\n"
                        + "  checkpoint\tcmd=\"ctl_cyrusdb -c\" period=30\n"
                        + "\n"
                        + "  # this is only necessary if using duplicate delivery suppression,\n"
                        + "  # Sieve or NNTP\n");
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      if (!Float.isNaN(cyrusServer.getDeleteDuration())) {
                        throw new IllegalStateException("delete-duration not supported on " + osv);
                      }
                      int expireDays;
                      {
                        CyrusImapdServer.TimeUnit expireUnit = cyrusServer.getExpireDurationUnit();
                        if (expireUnit == null) {
                          expireUnit = CyrusImapdServer.TimeUnit.DAYS;
                        }
                        expireDays = expireUnit.getDays(cyrusServer.getExpireDuration());
                      }
                      out.print("  delprune\tcmd=\"cyr_expire -E ").print(expireDays);
                      float expungeDuration = cyrusServer.getExpungeDuration();
                      if (!Float.isNaN(expungeDuration)) {
                        int expungeDays;
                        {
                          CyrusImapdServer.TimeUnit expungeUnit = cyrusServer.getExpungeDurationUnit();
                          if (expungeUnit == null) {
                            expungeUnit = CyrusImapdServer.TimeUnit.DAYS;
                          }
                          expungeDays = expungeUnit.getDays(expungeDuration);
                        }
                        out.print(" -X ").print(expungeDays);
                      }
                      out.print("\" at=0400\n");
                    } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                      out.print("  delprune\tcmd=\"cyr_expire");
                      float deleteDuration = cyrusServer.getDeleteDuration();
                      if (!Float.isNaN(deleteDuration)) {
                        out.print(" -D ");
                        int deleteInt = Math.round(deleteDuration);
                        if (deleteDuration == deleteInt) {
                          out.print(deleteInt);
                        } else {
                          out.print(deleteDuration);
                        }
                        CyrusImapdServer.TimeUnit deleteUnit = cyrusServer.getDeleteDurationUnit();
                        if (deleteUnit != null) {
                          out.print(deleteUnit.getSuffix());
                        }
                      }

                      float expireDuration = cyrusServer.getExpireDuration();
                      out.print(" -E ");
                      int expireInt = Math.round(expireDuration);
                      if (expireDuration == expireInt) {
                        out.print(expireInt);
                      } else {
                        out.print(expireDuration);
                      }
                      CyrusImapdServer.TimeUnit expireUnit = cyrusServer.getExpireDurationUnit();
                      if (expireUnit != null) {
                        out.print(expireUnit.getSuffix());
                      }

                      float expungeDuration = cyrusServer.getExpungeDuration();
                      if (!Float.isNaN(expungeDuration)) {
                        out.print(" -X ");
                        int expungeInt = Math.round(expungeDuration);
                        if (expungeDuration == expungeInt) {
                          out.print(expungeInt);
                        } else {
                          out.print(expungeDuration);
                        }
                        CyrusImapdServer.TimeUnit expungeUnit = cyrusServer.getExpungeDurationUnit();
                        if (expungeUnit != null) {
                          out.print(expungeUnit.getSuffix());
                        }
                      }
                      out.print("\" at=0400\n");
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    out.print("\n"
                        + "  # this is only necessary if caching TLS sessions\n"
                        + "  tlsprune\tcmd=\"tls_prune\" at=0400\n"
                        + "}\n");
                  }
                  // Only write when changed
                  if (
                      DaemonFileUtils.atomicWrite(
                          cyrusConfFile,
                          bout.toByteArray(),
                          0644,
                          PosixFile.ROOT_UID,
                          PosixFile.ROOT_GID,
                          null,
                          restorecon
                      )
                  ) {
                    needsReload[0] = true;
                  }
                }

                // Update /etc/imapd.conf
                {
                  bout.reset();
                  try (ChainWriter out = new ChainWriter(bout)) {
                    out.print("#\n"
                        + "# Automatically generated by ").print(ImapManager.class.getName()).print("\n"
                        + "#\n"
                        + "configdirectory: /var/lib/imap\n"
                        + "\n"
                        + "# Default partition\n"
                        + "defaultpartition: default\n"
                        + "partition-default: /var/spool/imap\n"
                        + "\n"
                        + "# Authentication\n"
                        + "username_tolower: no\n" // This is to be consistent with authenticated SMTP, which always has case-sensitive usernames.
                        + "unix_group_enable: no\n"
                        + "sasl_pwcheck_method: saslauthd\n");
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      out.print("sasl_mech_list: PLAIN\n");
                    } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                      out.print("sasl_mech_list: PLAIN LOGIN\n");
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    out.print("sasl_minimum_layer: 0\n");
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      out.print("allowplaintext: yes\n"
                          + "allowplainwithouttls: ").print(cyrusServer.getAllowPlaintextAuth() ? "yes" : "no").print('\n');
                    } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                      out.print("allowplaintext: ").print(cyrusServer.getAllowPlaintextAuth() ? "yes" : "no").print('\n'); // Was "no"
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    String certFile;
                    String keyFile;
                    String chainFile;
                    {
                      Certificate certificate = cyrusServer.getCertificate();
                      String certbotName = certificate.getCertbotName();
                      if (certbotName != null) {
                        PosixFile dir = new PosixFile(CERTIFICATE_COPY_DIRECTORY, certbotName, true);
                        certFile  = new PosixFile(dir, CERTIFICATE_COPY_CERT,  true).getPath();
                        keyFile   = new PosixFile(dir, CERTIFICATE_COPY_KEY,   true).getPath();
                        chainFile = new PosixFile(dir, CERTIFICATE_COPY_CHAIN, true).getPath();
                      } else {
                        certFile = certificate.getCertFile().toString();
                        keyFile = certificate.getKeyFile().toString();
                        chainFile = Objects.toString(certificate.getChainFile(), null);
                        if (chainFile == null) {
                          // Use operating system default
                          chainFile = DEFAULT_CA_FILE;
                        }
                      }
                    }
                    out.print("\n"
                        + "# SSL/TLS\n"
                        + "tls_cert_file: ").print(certFile).print("\n"
                        + "tls_key_file: ").print(keyFile).print("\n"
                        + "tls_ca_file: ").print(chainFile).print("\n");
                    // service-specific certificates
                    //     file:///home/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
                    //     value of "disabled='disabled'" if the certificate file doesn't exist (or use server default)
                    //     openssl req -new -x509 -nodes -out cyrus-imapd.pem -keyout cyrus-imapd.pem -days 3650
                    for (Map.Entry<String, CyrusImapdBind> entry : tlsServices.entrySet()) {
                      String serviceName = entry.getKey();
                      CyrusImapdBind cib = entry.getValue();
                      Bind netBind = cib.getNetBind();
                      InetAddress ipAddress = netBind.getIpAddress().getInetAddress();
                      int port = netBind.getPort().getPort();
                      String protocol;
                      String appProtocol = netBind.getAppProtocol().getProtocol();
                      switch (appProtocol) {
                        case AppProtocol.IMAP2:
                          protocol = "imap";
                          break;
                        case AppProtocol.SIMAP:
                          protocol = "imaps";
                          break;
                        case AppProtocol.POP3:
                          protocol = "pop3";
                          break;
                        case AppProtocol.SPOP3:
                          protocol = "pop3s";
                          break;
                        default:
                          throw new SQLException("Unexpected Protocol: " + appProtocol);
                      }

                      Certificate cibCertificate = cib.getCertificate();
                      if (cibCertificate != null) {
                        String cibCertFile;
                        String cibKeyFile;
                        String cibChainFile;
                        {
                          String cibCertbotName = cibCertificate.getCertbotName();
                          if (cibCertbotName != null) {
                            PosixFile dir = new PosixFile(CERTIFICATE_COPY_DIRECTORY, cibCertbotName, true);
                            cibCertFile  = new PosixFile(dir, CERTIFICATE_COPY_CERT,  true).getPath();
                            cibKeyFile   = new PosixFile(dir, CERTIFICATE_COPY_KEY,   true).getPath();
                            cibChainFile = new PosixFile(dir, CERTIFICATE_COPY_CHAIN, true).getPath();
                          } else {
                            cibCertFile = cibCertificate.getCertFile().toString();
                            cibKeyFile = cibCertificate.getKeyFile().toString();
                            cibChainFile = Objects.toString(cibCertificate.getChainFile(), null);
                            if (cibChainFile == null) {
                              // Use operating system default
                              cibChainFile = DEFAULT_CA_FILE;
                            }
                          }
                        }
                        out.print(serviceName).print("_tls_cert_file: ").print(cibCertFile).print('\n');
                        out.print(serviceName).print("_tls_key_file: ").print(cibKeyFile).print('\n');
                        out.print(serviceName).print("_tls_ca_file: ").print(cibChainFile).print('\n');
                      }
                      DomainName servername = cib.getServername();
                      if (servername != null) {
                        out.print(serviceName).print("_servername: ").print(servername).print('\n');
                      }
                      Boolean allowPlaintextAuth = cib.getAllowPlaintextAuth();
                      if (allowPlaintextAuth != null) {
                        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                          out.print(serviceName).print("_allowplainwithouttls: ").print(allowPlaintextAuth ? "yes" : "no").print('\n');
                        } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                          out.print(serviceName).print("_allowplaintext: ").print(allowPlaintextAuth ? "yes" : "no").print('\n');
                        } else {
                          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                        }
                      }
                    }
                    out.print("\n"
                        + "# Performance\n");

                    float deleteDuration = cyrusServer.getDeleteDuration();
                    if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                      out.print("delete_mode: ").print(Float.isNaN(deleteDuration) ? "immediate" : "delayed").print('\n');
                    } else if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      if (!Float.isNaN(deleteDuration)) {
                        throw new SQLException("Delayed delete is not supported on " + osv);
                      }
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }

                    float expungeDuration = cyrusServer.getExpungeDuration();
                    if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                      out.print("expunge_mode: ").print(Float.isNaN(expungeDuration) ? "default" : "delayed").print('\n');
                      if (!Float.isNaN(expungeDuration)) {
                        int expungeDays;
                        {
                          CyrusImapdServer.TimeUnit expungeUnit = cyrusServer.getExpungeDurationUnit();
                          if (expungeUnit == null) {
                            expungeUnit = CyrusImapdServer.TimeUnit.DAYS;
                          }
                          expungeDays = expungeUnit.getDays(expungeDuration);
                        }
                        if (expungeDays < 7) {
                          expungeDays = 7;
                        }
                        out.print("expunge_days: ").print(expungeDays).print('\n');
                      }
                    } else if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      out.print("expunge_mode: ").print(Float.isNaN(expungeDuration) ? "immediate" : "delayed").print('\n');
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }

                    out.print("hashimapspool: true\n"
                        + "\n"
                        + "# Outlook compatibility\n"
                        + "flushseenstate: yes\n"
                        + "\n"
                        + "# WU IMAPD compatibility\n"
                        + "altnamespace: yes\n"
                        + "unixhierarchysep: yes\n"
                        + "virtdomains: userid\n"
                        + "defaultdomain: default\n" // was "mail" for CentOS 7
                        + "\n"
                        + "# Security\n"
                        + "imapidresponse: no\n"
                        + "admins: cyrus\n"
                        + "\n"
                        + "# Allows users to read for sa-learn after hard linking to user readable directory\n"
                        + "umask: 022\n"
                        + "\n"
                        + "# Proper hostname in chroot fail-over state\n"
                        + "servername: ");
                    DomainName servername = cyrusServer.getServername();
                    if (servername == null) {
                      servername = thisServer.getHostname();
                    }
                    out.print(servername).print("\n"
                        + "\n"
                        + "# Sieve\n"
                        + "sievedir: /var/lib/imap/sieve\n"
                        + "autosievefolders: Junk\n"
                        + "sendmail: /usr/sbin/sendmail\n");
                  }
                  // Only write when changed
                  if (
                      DaemonFileUtils.atomicWrite(
                          imapdConfFile,
                          bout.toByteArray(),
                          0644,
                          PosixFile.ROOT_UID,
                          PosixFile.ROOT_GID,
                          null,
                          restorecon
                      )
                  ) {
                    needsReload[0] = true;
                  }
                }

              // SELinux before reload
              DaemonFileUtils.restorecon(restorecon);
              restorecon.clear();

              if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                // chkconfig on if needed
                if (!cyrusRcFile.getStat().exists()) {
                  if (isFine) {
                    logger.fine("Enabling cyrus-imapd service");
                  }
                  AoservDaemon.exec("/sbin/chkconfig", "cyrus-imapd", "on");
                  if (!cyrusRcFile.getStat().exists()) {
                    throw new IOException(cyrusRcFile.getPath() + " still does not exist after chkconfig on");
                  }
                }

                // Start service if not running
                if (!subsysLockFile.exists()) {
                  if (isFine) {
                    logger.fine("Starting cyrus-imapd service");
                  }
                  AoservDaemon.exec("/etc/rc.d/init.d/cyrus-imapd", "start");
                  if (!subsysLockFile.exists()) {
                    throw new IOException(subsysLockFile.getPath() + " still does not exist after service start");
                  }
                } else {
                  if (needsReload[0]) {
                    if (isFine) {
                      logger.fine("Reloading cyrus-imapd service");
                    }
                    AoservDaemon.exec("/etc/rc.d/init.d/cyrus-imapd", "reload");
                  }
                }
              } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                AoservDaemon.exec("/usr/bin/systemctl", "enable", "cyrus-imapd-set-deliver-permissions.service", "cyrus-imapd.service");
                if (needsReload[0]) {
                  AoservDaemon.exec("/usr/bin/systemctl", "reload-or-restart", "cyrus-imapd-set-deliver-permissions.service", "cyrus-imapd.service");
                } else {
                  AoservDaemon.exec("/usr/bin/systemctl", "start", "cyrus-imapd-set-deliver-permissions.service", "cyrus-imapd.service");
                }
                // Install cyrus-imapd-after-network-online package on CentOS 7 when needed
                if (hasSpecificAddress) {
                  PackageManager.installPackage(PackageManager.PackageName.CYRUS_IMAPD_AFTER_NETWORK_ONLINE);
                }
              } else {
                throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
              }

              rebuildUsers();

            }
            // Uninstall cyrus-imapd-after-network-online package on CentOS 7 when not needed
            if (
                !hasSpecificAddress
                    && osvId == OperatingSystemVersion.CENTOS_7_X86_64
                    && AoservDaemonConfiguration.isPackageManagerUninstallEnabled()
            ) {
              PackageManager.removePackage(PackageManager.PackageName.CYRUS_IMAPD_AFTER_NETWORK_ONLINE);
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
                    && AoservDaemonConfiguration.isPackageManagerUninstallEnabled()
            ) {
              PackageManager.removePackage(PackageManager.PackageName.CYRUS_IMAPD_COPY_CERTIFICATES);
            }
          } finally {
            DaemonFileUtils.restorecon(restorecon);
          }
        }
      }
      return true;
    } catch (StoreClosedException err) {
      if ("* BYE idle for too long".equals(err.getMessage())) {
        logger.log(Level.INFO, null, err);
      } else {
        logger.log(Level.SEVERE, null, err);
      }
      return false;
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  /**
   * Gets a folder name for the provided user, domain, and folder.
   *
   * @param  user    the username (without any @ sign)
   * @param  domain  null if no domain
   * @param  folder  the folder or null for INBOX
   */
  private static String getFolderName(String user, String domain, String folder) {
    StringBuilder sb = new StringBuilder();
    sb.append("user/").append(user);
    if (folder.length() > 0) {
      sb.append('/').append(folder);
    }
    if (!"default".equals(domain)) {
      sb.append('@').append(domain);
    }
    return sb.toString();
  }

  /**
   * Rebuild the ACL on a folder, both creating missing and removing extra rights.
   *
   * <p>http://www.faqs.org/rfcs/rfc2086.html</p>
   *
   * @param  folder  the IMAPFolder to verify the ACL on
   * @param  user    the username (without any @ sign)
   * @param  domain  "default" if no domain
   * @param  rights  the rights, one character to right
   */
  private static void rebuildAcl(IMAPFolder folder, String user, String domain, Rights rights) throws MessagingException {
    boolean isDebug = logger.isLoggable(Level.FINE);
    // Determine the username
    String username = "default".equals(domain) ? user : (user + '@' + domain);
    if (isDebug) {
      logger.fine(folder.getFullName() + ": Getting ACL: " + username);
    }

    ACL userAcl = null;
    for (ACL acl : folder.getACL()) {
      if (acl.getName().equals(username)) {
        userAcl = acl;
        break;
      }
    }
    if (userAcl == null) {
      ACL newAcl = new ACL(username, new Rights(rights));
      if (isDebug) {
        logger.fine(folder.getFullName() + ": Adding new ACL: " + rights.toString());
      }
      folder.addACL(newAcl);
    } else {
      // Verify rights
      Rights aclRights = userAcl.getRights();

      // Look for missing
      if (!aclRights.contains(rights)) {
        // Build the set of rights that are missing
        Rights missingRights = new Rights();
        for (Rights.Right right : rights.getRights()) {
          if (!aclRights.contains(right)) {
            missingRights.add(right);
          }
        }
        userAcl.setRights(missingRights);
        if (isDebug) {
          logger.fine(folder.getFullName() + ": Adding rights to ACL: " + userAcl.toString());
        }
        folder.addRights(userAcl);
      }
      if (!rights.contains(aclRights)) {
        // Build the set of rights that are extra
        Rights extraRights = new Rights();
        for (Rights.Right right : aclRights.getRights()) {
          if (!rights.contains(right)) {
            extraRights.add(right);
          }
        }
        userAcl.setRights(extraRights);
        if (isDebug) {
          logger.fine(folder.getFullName() + ": Removing rights from ACL: " + userAcl.toString());
        }
        folder.removeRights(userAcl);
      }
    }
  }

  /**
   * Gets the Cyrus user part of a username.
   *
   * @see  #getDomain
   */
  private static String getUser(User.Name username) {
    String usernameStr = username.toString();
    int atPos = usernameStr.lastIndexOf('@');
    return (atPos == -1) ? usernameStr : usernameStr.substring(0, atPos);
  }

  /**
   * Gets the Cyrus domain part of a username or <code>null</code> for no domain.
   *
   * @see  #getUser
   */
  private static String getDomain(User.Name username) {
    String usernameStr = username.toString();
    int atPos = usernameStr.lastIndexOf('@');
    return (atPos == -1) ? "default" : usernameStr.substring(atPos + 1);
  }

  /**
   * Adds user directories to the provided domain->user map.
   * The directories should be in the format ?/user/*
   * The ? should be equal to the first letter of *
   */
  @SuppressWarnings("AssignmentToForLoopParameter")
  private static void addUserDirectories(File directory, Set<String> ignoreList, String domain, Map<String, Set<String>> allUsers) throws IOException {
    String[] hashFilenames = directory.list();
    if (hashFilenames != null) {
      boolean isTrace = logger.isLoggable(Level.FINER);
      Arrays.sort(hashFilenames);
      for (String hashFilename : hashFilenames) {
        if (ignoreList == null || !ignoreList.contains(hashFilename)) {
          // hashFilename should only be one character
          File hashDir = new File(directory, hashFilename);
          if (hashFilename.length() != 1) {
            throw new IOException("hashFilename should only be on character: " + hashDir.getPath());
          }
          // hashDir should only contain a "user" directory
          String[] hashSubFilenames = hashDir.list();
          if (hashSubFilenames != null && hashSubFilenames.length > 0) {
            if (hashSubFilenames.length != 1) {
              throw new IOException("hashSubFilenames should only contain one directory: " + hashDir);
            }
            String hashSubFilename = hashSubFilenames[0];
            File userDir = new File(hashDir, hashSubFilename);
            if (!"user".equals(hashSubFilename)) {
              throw new IOException("hashSubFilenames should only contain a \"user\" directory: " + userDir);
            }
            String[] userSubFilenames = userDir.list();
            if (userSubFilenames != null && userSubFilenames.length > 0) {
              Arrays.sort(userSubFilenames);
              // Add the domain if needed
              Set<String> domainUsers = allUsers.get(domain);
              if (domainUsers == null) {
                if (isTrace) {
                  logger.finer("addUserDirectories: domain: " + domain);
                }
                allUsers.put(domain, domainUsers = new HashSet<>());
              }
              // Add the users
              for (String user : userSubFilenames) {
                if (!user.startsWith(hashFilename)) {
                  throw new IOException("user directory should start with " + hashFilename + ": " + userDir.getPath() + "/" + user);
                }
                user = user.replace('^', '.');
                if (isTrace) {
                  logger.finer("addUserDirectories: user: " + user);
                }
                if (!domainUsers.add(user)) {
                  throw new IOException("user already in domain: " + userDir.getPath() + "/" + user);
                }
              }
            }
          }
        }
      }
    }
  }

  private static void convertImapDirectory(
      final PrintWriter logOut,
      final User.Name username,
      final int junkRetention,
      final int trashRetention,
      final PosixFile directory,
      final PosixFile backupDirectory,
      final String folderPath,
      final String[] tempPassword,
      final PosixFile passwordBackup
  ) throws IOException, SQLException, MessagingException {
    // Careful not a symbolic link
    if (!directory.getStat().isDirectory()) {
      throw new IOException("Not a directory: " + directory.getPath());
    }

    // Create backup directory
    if (!backupDirectory.getStat().exists()) {
      log(logOut, Level.FINE, username, "Creating backup directory: " + backupDirectory.getPath());
      backupDirectory.mkdir(false, 0700);
    }

    // Convert each of the children
    String[] list = directory.list();
    if (list != null) {
      Arrays.sort(list);
      for (String childName : list) {
        PosixFile childUf = new PosixFile(directory, childName, false);
        long mode = childUf.getStat().getRawMode();
        boolean isDirectory = PosixFile.isDirectory(mode);
        boolean isFile = PosixFile.isRegularFile(mode);
        if (isDirectory && isFile) {
          throw new IOException("Both directory and regular file: " + childUf.getPath());
        }
        if (!isDirectory && !isFile) {
          throw new IOException("Neither directory nor regular file: " + childUf.getPath());
        }
        String folderName = folderPath.length() == 0 ? childName : (folderPath + '/' + childName);

        // Get New Store
        try (IMAPStore newStore = getNewUserStore(logOut, username, tempPassword, passwordBackup)) {
          // Get New Folder
          IMAPFolder newFolder = (IMAPFolder) newStore.getFolder(folderName);
          try {
            // Create the new folder if doesn't exist
            if (!newFolder.exists()) {
              log(logOut, Level.FINE, username, "Creating mailbox: " + folderName);
              if (!newFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                throw new MessagingException("Unable to create folder: " + folderName);
              }
            }

            // Subscribe to new folder if not yet subscribed
            if (!newFolder.isSubscribed()) {
              log(logOut, Level.FINE, username, "Subscribing to mailbox: " + folderName);
              newFolder.setSubscribed(true);
            }
          } finally {
            if (newFolder.isOpen()) {
              newFolder.close(false);
            }
          }
        }

        // Recurse
        PosixFile childBackupUf = new PosixFile(backupDirectory, childName, false);
        if (isDirectory && !isFile) {
          convertImapDirectory(logOut, username, junkRetention, trashRetention, childUf, childBackupUf, folderName, tempPassword, passwordBackup);
        } else if (isFile && !isDirectory) {
          convertImapFile(logOut, username, junkRetention, trashRetention, childUf, childBackupUf, folderName, tempPassword, passwordBackup);
        } else {
          throw new AssertionError("This should already have been caught by the isDirectory and isFile checks above");
        }
      }
    }

    // Directory should be empty, delete it or error if not empty
    list = directory.list();
    if (list != null && list.length > 0) {
      log(logOut, Level.WARNING, username, "Unable to delete non-empty directory \"" + directory.getPath() + "\": Contains " + list.length + " items");
    } else {
      log(logOut, Level.FINE, username, "Deleting empty directory: " + directory.getPath());
      directory.delete();
    }
  }

  /**
   * This should really be a toString on the Flags.Flag class.
   */
  private static String getFlagName(Flags.Flag flag) throws MessagingException {
    if (flag == Flags.Flag.ANSWERED) {
      return "ANSWERED";
    }
    if (flag == Flags.Flag.DELETED) {
      return "DELETED";
    }
    if (flag == Flags.Flag.DRAFT) {
      return "DRAFT";
    }
    if (flag == Flags.Flag.FLAGGED) {
      return "FLAGGED";
    }
    if (flag == Flags.Flag.RECENT) {
      return "RECENT";
    }
    if (flag == Flags.Flag.SEEN) {
      return "SEEN";
    }
    if (flag == Flags.Flag.USER) {
      return "USER";
    }
    throw new MessagingException("Unexpected flag: " + flag);
  }

  private static final Flags.Flag[] systemFlags = {
      Flags.Flag.ANSWERED,
      Flags.Flag.DELETED,
      Flags.Flag.DRAFT,
      Flags.Flag.FLAGGED,
      Flags.Flag.RECENT,
      Flags.Flag.SEEN,
      Flags.Flag.USER
  };

  private static final Object appendCounterLock = new Object();
  private static long appendCounterStart = -1;
  private static long appendCounter;

  private static void incAppendCounter() {
    synchronized (appendCounterLock) {
      long currentTime = System.currentTimeMillis();
      if (appendCounterStart == -1) {
        appendCounterStart = currentTime;
        appendCounter = 0;
      } else {
        appendCounter++;
        long span = currentTime - appendCounterStart;
        if (span < 0) {
          logger.warning("incAppendCounter: span < 0: System time reset?");
          appendCounterStart = currentTime;
          appendCounter = 0;
        } else if (span >= 60000) {
          long milliMessagesPerSecond = appendCounter * 1000000 / span;
          if (logger.isLoggable(Level.INFO)) {
            logger.info("Copied " + SQLUtility.formatDecimal3(milliMessagesPerSecond) + " messages per second");
          }
          appendCounterStart = currentTime;
          appendCounter = 0;
        }
      }
    }
  }

  /**
   * Flags.equals is returning false when the objects are equal.  Perhaps there
   * is a problem with one Flags having null user flags while the other
   * has an empty array.  Don't really care - this is the fix.
   */
  private static boolean equals(Flags flags1, Flags flags2) {
    Flags.Flag[] systemFlags1 = flags1.getSystemFlags();
    Flags.Flag[] systemFlags2 = flags2.getSystemFlags();
    if (systemFlags1.length != systemFlags2.length) {
      return false;
    }
    for (Flags.Flag flag : systemFlags1) {
      if (!flags2.contains(flag)) {
        return false;
      }
    }

    String[] userFlags1 = flags1.getUserFlags();
    String[] userFlags2 = flags2.getUserFlags();
    if (userFlags1.length != userFlags2.length) {
      return false;
    }
    for (String flag : userFlags1) {
      if (!flags2.contains(flag)) {
        return false;
      }
    }

    return true;
  }

  private static void convertImapFile(
      final PrintWriter logOut,
      final User.Name username,
      final int junkRetention,
      final int trashRetention,
      final PosixFile file,
      final PosixFile backupFile,
      final String folderName,
      final String[] tempPassword,
      final PosixFile passwordBackup
  ) throws IOException, SQLException, MessagingException {
    // Careful not a symolic link
    if (!file.getStat().isRegularFile()) {
      throw new IOException("Not a regular file: " + file.getPath());
    }

    // Backup file
    if (!backupFile.getStat().exists()) {
      log(logOut, Level.FINE, username, "Backing-up \"" + folderName + "\" to \"" + backupFile.getPath() + "\"");
      try (
          TempFileContext tempFileContext = new TempFileContext(backupFile.getFile().getParentFile());
          TempFile tempFile = tempFileContext.createTempFile(backupFile.getFile().getName())
          ) {
        PosixFile tempPosixFile = new PosixFile(tempFile.getFile());
        file.copyTo(tempPosixFile, true);
        tempPosixFile.chown(PosixFile.ROOT_UID, PosixFile.ROOT_GID).setMode(0600).renameTo(backupFile);
      }
    }

    // Delete the file if it is not a mailbox or is empty
    String filename = file.getFile().getName();
    if (
        filename.startsWith(".")
            && (
            filename.endsWith(".index.ids")
                || filename.endsWith(".index")
                || filename.endsWith(".index.sorted")
        )
    ) {
      log(logOut, Level.FINE, username, "Deleting non-mailbox file: " + file.getPath());
      file.delete();
    } else if (file.getStat().getSize() == 0) {
      log(logOut, Level.FINE, username, "Deleting empty mailbox file: " + file.getPath());
      file.delete();
    } else {
      // Get Old Store
      boolean deleteOldFolder;
      try (IMAPStore oldStore = getOldUserStore(logOut, username, tempPassword, passwordBackup)) {
        // Get Old Folder
        IMAPFolder oldFolder = (IMAPFolder) oldStore.getFolder(folderName);
        try {
          if (!oldFolder.exists()) {
            throw new MessagingException(username + ": Old folder doesn't exist: " + folderName);
          }
          oldFolder.open(Folder.READ_WRITE);
          // Get New Store
          try (IMAPStore newStore = getNewUserStore(logOut, username, tempPassword, passwordBackup)) {
            // Get New Folder
            IMAPFolder newFolder = (IMAPFolder) newStore.getFolder(folderName);
            try {
              // Should already exist
              if (!newFolder.exists()) {
                throw new MessagingException(username + ": New folder doesn't exist: " + folderName);
              }
              newFolder.open(Folder.READ_WRITE);

              // Subscribe to new folder if not yet subscribed
              if (!newFolder.isSubscribed()) {
                log(logOut, Level.FINE, username, "Subscribing to mailbox: " + folderName);
                newFolder.setSubscribed(true);
              }

              Message[] oldMessages = oldFolder.getMessages();
              for (int c = 0, len = oldMessages.length; c < len; c++) {
                Message oldMessage = oldMessages[c];
                // Make sure not already finished this message
                if (oldMessage.isSet(Flags.Flag.DELETED)) {
                  log(logOut, Level.FINER, username, "\"" + folderName + "\": Skipping deleted message " + (c + 1) + " of " + len
                      + " (" + Strings.getApproximateSize(oldMessage.getSize()) + ")");
                } else {
                  long messageAge = (System.currentTimeMillis() - oldMessage.getReceivedDate().getTime()) / (24L * 60 * 60 * 1000);
                  if (
                      junkRetention != -1
                          && "Junk".equals(folderName)
                          && messageAge > junkRetention
                  ) {
                    log(logOut, Level.FINER, username, "\"" + folderName + "\": Deleting old junk message (" + messageAge
                        + ">" + junkRetention + " days) " + (c + 1) + " of " + len + " (" + Strings.getApproximateSize(oldMessage.getSize()) + ")");
                    oldMessage.setFlag(Flags.Flag.DELETED, true);
                  } else if (
                      trashRetention != -1
                          && "Trash".equals(folderName)
                          && messageAge > trashRetention
                  ) {
                    log(logOut, Level.FINER, username, "\"" + folderName + "\": Deleting old trash message (" + messageAge
                        + ">" + trashRetention + " days) " + (c + 1) + " of " + len + " (" + Strings.getApproximateSize(oldMessage.getSize()) + ")");
                    oldMessage.setFlag(Flags.Flag.DELETED, true);
                  } else {
                    log(logOut, Level.FINER, username, "\"" + folderName + "\": Copying message " + (c + 1) + " of " + len
                        + " (" + Strings.getApproximateSize(oldMessage.getSize()) + ")");
                    try {
                      final Flags oldFlags = oldMessage.getFlags();

                      // Copy to newFolder
                      incAppendCounter();
                      AppendUID[] newUids = newFolder.appendUIDMessages(new Message[]{oldMessage});
                      if (newUids.length != 1) {
                        throw new MessagingException("newUids.length != 1: " + newUids.length);
                      }
                      AppendUID newUid = newUids[0];
                      if (newUid == null) {
                        throw new MessagingException("newUid is null");
                      }

                      // Make sure the flags match
                      long newUidNum = newUid.uid;
                      Message newMessage = newFolder.getMessageByUID(newUidNum);
                      if (newMessage == null) {
                        throw new MessagingException(username + ": \"" + folderName + "\": Unable to find new message by UID: " + newUidNum);
                      }
                      Flags newFlags = newMessage.getFlags();

                      // Remove the recent flag if added by append
                      Flags effectiveOldFlags = new Flags(oldFlags);
                      Flags effectiveNewFlags = new Flags(newFlags);
                      for (Flags.Flag flag : systemFlags) {
                        if (oldFlags.contains(flag)) {
                          if (!newFlags.contains(flag)) {
                            // New should have
                          }
                        } else {
                          if (newFlags.contains(flag)) {
                            // New should not have
                            if (
                                // This is OK to ignore since it was added by append
                                flag == Flags.Flag.RECENT
                            ) {
                              // This failed: newMessage.setFlag(flag, false);
                              effectiveNewFlags.remove(flag);
                            } else if (
                                // This was set by append but needs to be unset
                                flag == Flags.Flag.SEEN
                            ) {
                              newMessage.setFlag(flag, false);
                              newFlags = newMessage.getFlags();
                              effectiveNewFlags.remove(flag);
                            }
                          }
                        }
                      }
                      for (String flag : oldFlags.getUserFlags()) {
                        if (!effectiveNewFlags.contains(flag)) {
                          // Ignore "$NotJunk" on oldFlags
                          //if (flag.equals("$NotJunk")) {
                          //    effectiveOldFlags.remove(flag);
                          //} else {
                          // Add the user flag
                          effectiveNewFlags.add(flag);
                          newMessage.setFlags(new Flags(flag), true);
                          newFlags = newMessage.getFlags();
                          //}
                        }
                      }

                      if (!equals(effectiveOldFlags, effectiveNewFlags)) {
                        for (Flags.Flag flag : effectiveOldFlags.getSystemFlags()) {
                          log(logOut, Level.SEVERE, username, "\"" + folderName + "\": effectiveOldFlags: system: \"" + getFlagName(flag) + '"');
                        }
                        for (String flag : effectiveOldFlags.getUserFlags()) {
                          log(logOut, Level.SEVERE, username, "\"" + folderName + "\": effectiveOldFlags: user: \"" + flag + '"');
                        }
                        for (Flags.Flag flag : effectiveNewFlags.getSystemFlags()) {
                          log(logOut, Level.SEVERE, username, "\"" + folderName + "\": effectiveNewFlags: system: \"" + getFlagName(flag) + '"');
                        }
                        for (String flag : effectiveNewFlags.getUserFlags()) {
                          log(logOut, Level.SEVERE, username, "\"" + folderName + "\": effectiveNewFlags: user: \"" + flag + '"');
                        }
                        throw new MessagingException(username + ": \"" + folderName + "\": effectiveOldFlags != effectiveNewFlags: " + effectiveOldFlags + " != " + effectiveNewFlags);
                      }

                      // Flag as deleted on the old folder
                      oldMessage.setFlag(Flags.Flag.DELETED, true);
                    } catch (MessagingException err) {
                      String message = err.getMessage();
                      if (message != null && message.endsWith(" NO Message contains invalid header")) {
                        log(logOut, Level.WARNING, username, "\"" + folderName + "\": Not able to copy message: " + message);
                        Enumeration<?> headers = oldMessage.getAllHeaders();
                        while (headers.hasMoreElements()) {
                          Header header = (Header) headers.nextElement();
                          log(logOut, Level.WARNING, username, "\"" + folderName + "\": \"" + header.getName() + "\" = \"" + header.getValue() + "\"");
                        }
                      } else {
                        throw err;
                      }
                    }
                  }
                }
              }
            } finally {
              if (newFolder.isOpen()) {
                newFolder.close(false);
              }
            }
          }
          // Confirm that all messages in the old folder have delete flag
          int notDeletedCount = 0;
          Message[] oldMessages = oldFolder.getMessages();
          for (Message oldMessage : oldMessages) {
            if (!oldMessage.isSet(Flags.Flag.DELETED)) {
              notDeletedCount++;
            }
          }
          if (notDeletedCount > 0) {
            log(logOut, Level.WARNING, username, "Unable to delete mailbox \"" + folderName + "\": " + notDeletedCount + " of " + oldMessages.length + " old messages not flagged as deleted");
            deleteOldFolder = false;
          } else {
            deleteOldFolder = true;
          }
        } finally {
          // Make sure closed
          if (oldFolder.isOpen()) {
            oldFolder.close(true);
          }
        }
        // Delete old folder if completely empty, error otherwise
        if (deleteOldFolder && !"INBOX".equals(folderName)) {
          log(logOut, Level.FINE, username, "Deleting mailbox: " + folderName);
          if (!oldFolder.delete(false)) {
            throw new IOException(username + ": Unable to delete mailbox: " + folderName);
          }
        }
      }
      if (deleteOldFolder && file.getStat().exists()) {
        // If INBOX, need to remove file
        if ("INBOX".equals(folderName)) {
          log(logOut, Level.FINE, username, "Deleting mailbox file: " + file.getPath());
          file.delete();
        } else {
          // Confirm file should is gone
          throw new IOException(username + ": File still exists: " + file.getPath());
        }
      }
    }
  }

  /**
   * Logs a message as trace on commons-logging and on the per-user log.
   */
  private static void log(PrintWriter userLogOut, Level level, User.Name username, String message) {
    if (logger.isLoggable(level)) {
      logger.log(level, username + " - " + message);
    }
    synchronized (userLogOut) {
      userLogOut.println("[" + level + "] " + System.currentTimeMillis() + " - " + message);
      userLogOut.flush();
    }
  }

  private static void rebuildUsers() throws IOException, SQLException, MessagingException {
    try {
      // Connect to the store (will be null when not an IMAP server)
      final boolean isDebug = logger.isLoggable(Level.FINE);
      final boolean isTrace = logger.isLoggable(Level.FINER);
      IMAPStore store = getAdminStore();
      if (store == null) {
        throw new SQLException("Not an IMAP server");
      }
      // Verify all email users - only users who have a home under /home/ are considered
      List<UserServer> lsas = AoservDaemon.getThisServer().getLinuxServerAccounts();
      Set<String> validEmailUsernames = AoCollections.newHashSet(lsas.size());
      // Conversions are done concurrently
      Map<UserServer, Future<Object>> convertors = WUIMAP_CONVERSION_ENABLED ? AoCollections.newHashMap(lsas.size()) : null;
      ExecutorService executorService = WUIMAP_CONVERSION_ENABLED ? Executors.newFixedThreadPool(WUIMAP_CONVERSION_CONCURRENCY) : null;
      try {
        for (final UserServer lsa : lsas) {
          User la = lsa.getLinuxAccount();
          final PosixPath homePath = lsa.getHome();
          if (la.getType().isEmail() && homePath.toString().startsWith("/home/")) {
            // Split into user and domain
            final User.Name laUsername = la.getUsername_id();
            String user = getUser(laUsername);
            String domain = getDomain(laUsername);
            validEmailUsernames.add(laUsername.toString());

            // INBOX
            String inboxFolderName = getFolderName(user, domain, "");
            IMAPFolder inboxFolder = (IMAPFolder) store.getFolder(inboxFolderName);
            try {
              if (!inboxFolder.exists()) {
                if (isDebug) {
                  logger.fine("Creating mailbox: " + inboxFolderName);
                }
                if (!inboxFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                  throw new MessagingException("Unable to create folder: " + inboxFolder.getFullName());
                }
              }
              rebuildAcl(inboxFolder, User.CYRUS.toString(), "default", new Rights("ackrx"));
              rebuildAcl(inboxFolder, user, domain, new Rights("acdeiklprstwx"));
            } finally {
              if (inboxFolder.isOpen()) {
                inboxFolder.close(false);
              }
              inboxFolder = null;
            }

            // Trash
            String trashFolderName = getFolderName(user, domain, "Trash");
            IMAPFolder trashFolder = (IMAPFolder) store.getFolder(trashFolderName);
            try {
              if (!trashFolder.exists()) {
                if (isDebug) {
                  logger.fine("Creating mailbox: " + trashFolderName);
                }
                if (!trashFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                  throw new MessagingException("Unable to create folder: " + trashFolder.getFullName());
                }
              }
              rebuildAcl(trashFolder, User.CYRUS.toString(), "default", new Rights("ackrx"));
              rebuildAcl(trashFolder, user, domain, new Rights("acdeiklprstwx"));

              // Set/update expire annotation
              String existingValue = getAnnotation(trashFolder, "/vendor/cmu/cyrus-imapd/expire", "value.shared");
              int trashRetention = lsa.getTrashEmailRetention();
              String expectedValue = trashRetention == -1 ? null : Integer.toString(trashRetention);
              if (!Objects.equals(existingValue, expectedValue)) {
                if (isDebug) {
                  logger.fine("Setting mailbox expiration: " + trashFolderName + ": " + expectedValue);
                }
                setAnnotation(trashFolder, "/vendor/cmu/cyrus-imapd/expire", expectedValue, "text/plain");
              }
            } finally {
              if (trashFolder.isOpen()) {
                trashFolder.close(false);
              }
              trashFolder = null;
            }

            // Junk
            String junkFolderName = getFolderName(user, domain, "Junk");
            IMAPFolder junkFolder = (IMAPFolder) store.getFolder(junkFolderName);
            try {
              if (lsa.getEmailSpamAssassinIntegrationMode().getName().equals(SpamAssassinMode.IMAP)) {
                // Junk folder required for IMAP mode
                if (!junkFolder.exists()) {
                  if (isDebug) {
                    logger.fine("Creating mailbox: " + junkFolderName);
                  }
                  if (!junkFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                    throw new MessagingException("Unable to create folder: " + junkFolder.getFullName());
                  }
                }
              }
              if (junkFolder.exists()) {
                rebuildAcl(junkFolder, User.CYRUS.toString(), "default", new Rights("ackrx"));
                rebuildAcl(junkFolder, user, domain, new Rights("acdeiklprstwx"));

                // Set/update expire annotation
                String existingValue = getAnnotation(junkFolder, "/vendor/cmu/cyrus-imapd/expire", "value.shared");
                int junkRetention = lsa.getJunkEmailRetention();
                String expectedValue = junkRetention == -1 ? null : Integer.toString(junkRetention);
                if (!Objects.equals(existingValue, expectedValue)) {
                  if (isDebug) {
                    logger.fine("Setting mailbox expiration: " + junkFolderName + ": " + expectedValue);
                  }
                  setAnnotation(junkFolder, "/vendor/cmu/cyrus-imapd/expire", expectedValue, "text/plain");
                }
              }
            } finally {
              if (junkFolder.isOpen()) {
                junkFolder.close(false);
              }
              junkFolder = null;
            }

            if (WUIMAP_CONVERSION_ENABLED) {
              assert convertors != null;
              assert executorService != null;
              convertors.put(
                  lsa,
                  executorService.submit(() -> {
                    // Create the backup directory
                    if (!wuBackupDirectory.getStat().exists()) {
                      if (isDebug) {
                        logger.fine("Creating directory: " + wuBackupDirectory.getPath());
                      }
                      wuBackupDirectory.mkdir(true, 0700);
                    }
                    PosixFile userBackupDirectory = new PosixFile(wuBackupDirectory, laUsername.toString(), false);
                    if (!userBackupDirectory.getStat().exists()) {
                      if (isDebug) {
                        logger.fine(laUsername + ": Creating backup directory: " + userBackupDirectory.getPath());
                      }
                      userBackupDirectory.mkdir(false, 0700);
                    }

                    // Per-user logs
                    PosixFile logFile = new PosixFile(userBackupDirectory, "log", false);
                    if (isTrace) {
                      logger.finer(laUsername + ": Using logfile: " + logFile.getPath());
                    }
                    try (PrintWriter logOut = new PrintWriter(new FileOutputStream(logFile.getFile(), true))) {
                      if (logFile.getStat().getMode() != 0600) {
                        logFile.setMode(0600);
                      }
                      // Password backup is delayed until immediately before the password is reset.
                      // This avoids unnecessary password resets.
                      PosixFile passwordBackup = new PosixFile(userBackupDirectory, "passwd", false);

                      // Backup the mailboxlist
                      PosixFile homeDir = new PosixFile(homePath.toString());
                      PosixFile mailBoxListFile = new PosixFile(homeDir, ".mailboxlist", false);
                      Stat mailBoxListFileStat = mailBoxListFile.getStat();
                      if (mailBoxListFileStat.exists()) {
                        if (!mailBoxListFileStat.isRegularFile()) {
                          throw new IOException("Not a regular file: " + mailBoxListFile.getPath());
                        }
                        PosixFile mailBoxListBackup = new PosixFile(userBackupDirectory, "mailboxlist", false);
                        if (!mailBoxListBackup.getStat().exists()) {
                          log(logOut, Level.FINE, laUsername, "Backing-up mailboxlist");
                          try (
                              TempFileContext tempFileContext = new TempFileContext(mailBoxListBackup.getFile().getParentFile());
                              TempFile tempFile = tempFileContext.createTempFile(mailBoxListBackup.getFile().getName())
                              ) {
                            PosixFile tempPosixFile = new PosixFile(tempFile.getFile());
                            mailBoxListFile.copyTo(tempPosixFile, true);
                            tempPosixFile.chown(PosixFile.ROOT_UID, PosixFile.ROOT_GID).setMode(0600).renameTo(mailBoxListBackup);
                          }
                        }
                      }

                      // The password will be reset to a random value upon first use, subsequent
                      // accesses will use the same password.
                      String[] tempPassword = new String[1];
                      int junkRetention = lsa.getJunkEmailRetention();
                      int trashRetention = lsa.getTrashEmailRetention();
                      // Convert old INBOX
                      PosixFile inboxFile = new PosixFile(mailSpool, laUsername.toString());
                      Stat inboxFileStat = inboxFile.getStat();
                      if (inboxFileStat.exists()) {
                        if (!inboxFileStat.isRegularFile()) {
                          throw new IOException("Not a regular file: " + inboxFile.getPath());
                        }
                        convertImapFile(logOut, laUsername, junkRetention, trashRetention, inboxFile, new PosixFile(userBackupDirectory, "INBOX", false), "INBOX", tempPassword, passwordBackup);
                      }

                      // Convert old folders from UW software
                      if (
                          !"/home/a/acccorpapp".equals(homeDir.getPath())
                              && !"/home/acccorpapp".equals(homeDir.getPath())
                      ) {
                        PosixFile mailDir = new PosixFile(homeDir, "Mail", false);
                        Stat mailDirStat = mailDir.getStat();
                        if (mailDirStat.exists()) {
                          if (!mailDirStat.isDirectory()) {
                            throw new IOException("Not a directory: " + mailDir.getPath());
                          }
                          convertImapDirectory(logOut, laUsername, junkRetention, trashRetention, mailDir, new PosixFile(userBackupDirectory, "Mail", false), "", tempPassword, passwordBackup);
                        }
                      }

                      // Remove the mailboxlist file
                      if (mailBoxListFile.getStat().exists()) {
                        mailBoxListFile.delete();
                      }

                      // Restore passwd, if needed
                      if (passwordBackup.getStat().exists()) {
                        String currentEncryptedPassword = LinuxAccountManager.getEncryptedPassword(laUsername).getElement1();
                        String savedEncryptedPassword;
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(passwordBackup.getFile())))) {
                          savedEncryptedPassword = in.readLine();
                        }
                        if (savedEncryptedPassword == null) {
                          throw new IOException("Unable to load saved password");
                        }
                        if (!savedEncryptedPassword.equals(currentEncryptedPassword)) {
                          log(logOut, Level.FINE, laUsername, "Restoring password");
                          LinuxAccountManager.setEncryptedPassword(laUsername, savedEncryptedPassword, null);
                          PosixFile passwordBackupOld = new PosixFile(userBackupDirectory, "passwd.old", false);
                          passwordBackup.renameTo(passwordBackupOld);
                        } else {
                          passwordBackup.delete();
                        }
                      }
                    }
                    return null;
                  })
              );
            }
          }
        }
        if (WUIMAP_CONVERSION_ENABLED) {
          assert convertors != null;
          List<UserServer> deleteMe = new ArrayList<>();
          while (!convertors.isEmpty() && !Thread.currentThread().isInterrupted()) {
            deleteMe.clear();
            for (Map.Entry<UserServer, Future<Object>> entry : convertors.entrySet()) {
              UserServer lsa = entry.getKey();
              Future<Object> future = entry.getValue();
              // Wait for completion
              try {
                future.get(1, TimeUnit.SECONDS);
                deleteMe.add(lsa);
              } catch (InterruptedException err) {
                logger.log(Level.WARNING, "lsa = " + lsa, err);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
                break;
              } catch (ExecutionException err) {
                String extraInfo;
                Throwable cause = err.getCause();
                if (cause instanceof ReadOnlyFolderException) {
                  ReadOnlyFolderException rofe = (ReadOnlyFolderException) cause;
                  extraInfo = "lsa = " + lsa + ", folder = " + rofe.getFolder().getFullName();
                } else {
                  extraInfo = "lsa = " + lsa;
                }
                logger.log(Level.SEVERE, extraInfo, err);
                deleteMe.add(lsa);
              } catch (TimeoutException err) {
                // This is OK, will just retry on next loop
              }
            }
            if (!Thread.currentThread().isInterrupted()) {
              for (UserServer lsa : deleteMe) {
                convertors.remove(lsa);
              }
            }
          }
        }
      } finally {
        if (WUIMAP_CONVERSION_ENABLED) {
          assert executorService != null;
          executorService.shutdown();
        }
      }

      // Get the list of domains and users from the filesystem
      // (including the default).
      Map<String, Set<String>> allUsers = new HashMap<>();

      // The default users are in /var/spool/imap/?/user/*
      addUserDirectories(imapSpool, imapSpoolIgnoreDirectories, "default", allUsers);

      // The virtdomains are in /var/spool/imap/domain/?/*
      String[] hashDirs = imapVirtDomainSpool.list();
      if (hashDirs != null) {
        Arrays.sort(hashDirs);
        for (String hashDirName : hashDirs) {
          File hashDir = new File(imapVirtDomainSpool, hashDirName);
          String[] domainDirs = hashDir.list();
          if (domainDirs != null) {
            Arrays.sort(domainDirs);
            for (String domain : domainDirs) {
              addUserDirectories(new File(hashDir, domain), null, domain, allUsers);
            }
          }
        }
      }

      for (String domain : allUsers.keySet()) {
        for (String user : allUsers.get(domain)) {
          String lsaUsername;
          if ("default".equals(domain)) {
            lsaUsername = user;
          } else {
            lsaUsername = user + '@' + domain;
          }
          if (!validEmailUsernames.contains(lsaUsername)) {
            String cyrusFolder = getFolderName(user, domain, "");

            // Make sure the user folder exists
            IMAPFolder userFolder = (IMAPFolder) store.getFolder(cyrusFolder);
            try {
              if (!userFolder.exists()) {
                throw new MessagingException("Folder doesn't exist: " + cyrusFolder);
              }
              // TODO: Backup mailbox to /var/opt/aoserv-daemon/oldaccounts
              rebuildAcl(userFolder, User.CYRUS.toString(), "default", new Rights("acdkrx")); // Adds the d permission
              if (isDebug) {
                logger.fine("Deleting mailbox: " + cyrusFolder);
              }
              if (!userFolder.delete(true)) {
                throw new IOException("Unable to delete mailbox: " + cyrusFolder);
              }
            } finally {
              if (userFolder.isOpen()) {
                userFolder.close(false);
              }
            }
          }
        }
      }
    } catch (Error | RuntimeException | IOException | SQLException | MessagingException err) {
      closeAdminStore();
      throw err;
    }
  }

  /**
   * Remove after testing, or move to JUnit tests.
   */
  /*public static void main(String[] args) {
    for (int c=0;c<10;c++) benchmark();
    try {
      rebuildUsers();

      String[] folders = {"INBOX", "Junk", "No Existie"};
      long[] sizes = getImapFolderSizes("cyrus.test2", folders);
      for (int c=0;c<folders.length;c++) {
        System.out.println(folders[c]+": "+sizes[c]);
      }

      for (UserServer lsa : AoservDaemon.getThisServer().getLinuxServerAccounts()) {
        User la = lsa.getLinuxAccount();
        if (la.getType().isEmail() && lsa.getHome().startsWith("/home/")) {
          String username = la.getUsername().getUsername();
          System.out.println(username+": "+getInboxSize(username));
          DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.US);
          System.out.println(username+": "+dateFormat.format(new Date(getInboxModified(username))));
        }
      }
    } catch (Exception err) {
      ErrorPrinter.printStackTraces(err);
    }
  }*/

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
          // Nothing is done for these operating systems
          osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
              // Check config after OS check so config entry not needed
              && AoservDaemonConfiguration.isManagerEnabled(ImapManager.class)
              && imapManager == null
      ) {
        System.out.print("Starting ImapManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          imapManager = new ImapManager();
          conn.getLinux().getServer().addTableListener(imapManager, 0);
          conn.getEmail().getCyrusImapdBind().addTableListener(imapManager, 0);
          conn.getEmail().getCyrusImapdServer().addTableListener(imapManager, 0);
          conn.getNet().getIpAddress().addTableListener(imapManager, 0);
          conn.getLinux().getUser().addTableListener(imapManager, 0);
          conn.getLinux().getUserServer().addTableListener(imapManager, 0);
          conn.getNet().getBind().addTableListener(imapManager, 0);
          conn.getNet().getHost().addTableListener(imapManager, 0);
          conn.getPki().getCertificate().addTableListener(imapManager, 0);
          PackageManager.addPackageListener(imapManager);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild IMAP and Cyrus configurations";
  }

  public static long[] getImapFolderSizes(User.Name username, String[] folderNames) throws IOException, SQLException, MessagingException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    UserServer lsa = thisServer.getLinuxServerAccount(username);
    if (lsa == null) {
      throw new SQLException("Unable to find UserServer: " + username + " on " + thisServer);
    }
    long[] sizes = new long[folderNames.length];
    if (
        osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
    ) {
      String user = getUser(username);
      String domain = getDomain(username);
      for (int c = 0; c < folderNames.length; c++) {
        String folderName = folderNames[c];
        if (folderName.contains("..")) {
          sizes[c] = -1;
        } else {
          boolean isInbox = "INBOX".equals(folderName);
          sizes[c] = getCyrusFolderSize(user, isInbox ? "" : folderName, domain, !isInbox);
        }
      }
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
    return sizes;
  }

  static class Annotation {
    private final String mailboxName;
    private final String entry;
    private final Map<String, String> attributes;

    Annotation(String mailboxName, String entry, Map<String, String> attributes) {
      this.mailboxName = mailboxName;
      this.entry = entry;
      this.attributes = attributes;
    }

    String getMailboxName() {
      return mailboxName;
    }

    String getEntry() {
      return entry;
    }

    String getAttribute(String attributeSpecifier) {
      return attributes.get(attributeSpecifier);
    }
  }

  /**
   * Gets all of the annotations for the provided folder, entry, and attribute.
   *
   * <p>This uses ANNOTATEMORE
   *     Current: http://vman.de/cyrus/draft-daboo-imap-annotatemore-07.html
   *     Newer:   http://vman.de/cyrus/draft-daboo-imap-annotatemore-10.html</p>
   *
   * <pre>ad GETANNOTATION user/cyrus.test/Junk@suspendo.aoindustries.com "/vendor/cmu/cyrus-imapd/size" "value.shared"
   * ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/size" ("value.shared" "33650")
   * ad OK Completed</pre>
   *
   * <p>http://java.sun.com/products/javamail/javadocs/com/sun/mail/imap/IMAPFolder.html#doCommand(com.sun.mail.imap.IMAPFolder.ProtocolCommand)</p>
   *
   * <p>https://glassfish.dev.java.net/javaee5/mail/
   * javamail@sun.com</p>
   */
  @SuppressWarnings({"unchecked"})
  private static List<Annotation> getAnnotations(IMAPFolder folder, final String entry, final String attribute) throws MessagingException {
    final String mailboxName = folder.getFullName();
    List<Annotation> annotations = (List) folder.doCommand(p -> {
      // Issue command
      Argument args = new Argument();
      args.writeString(mailboxName);
      args.writeNString(entry);
      args.writeNString(attribute);
      Response[] r = p.command("GETANNOTATION", args);
      Response response = r[r.length - 1];
      // Grab response
      List<Annotation> annotations1 = new ArrayList<>(r.length - 1);
      if (response.isOK()) {
        // command succesful
        for (int i = 0, len = r.length; i < len; i++) {
          if (r[i] instanceof IMAPResponse) {
            IMAPResponse ir = (IMAPResponse) r[i];
            if (ir.keyEquals("ANNOTATION")) {
              String mailboxName1 = ir.readAtomString();
              String entry1 = ir.readAtomString();
              String[] list = ir.readStringList();
              // Must be even number of elements in list
              if ((list.length & 1) != 0) {
                throw new ProtocolException("Uneven number of elements in attribute list: " + list.length);
              }
              Map<String, String> attributes = AoCollections.newHashMap(list.length >> 1);
              for (int j = 0; j < list.length; j += 2) {
                attributes.put(list[j], list[j + 1]);
              }
              annotations1.add(new Annotation(mailboxName1, entry1, attributes));
              // Mark as handled
              r[i] = null;
            }
          }
        }
      } else {
        throw new ProtocolException("Response is not OK: " + response);
      }
      // dispatch remaining untagged responses
      p.notifyResponseHandlers(r);
      p.handleResult(response);
      return annotations1;
    });
    return annotations;
  }

  /**
   * Gets a single, specific annotation (specific in mailbox-name, entry-specifier, and attribute-specifier).
   *
   * @return  the value if found or <code>null</code> if unavailable
   */
  private static String getAnnotation(IMAPFolder folder, String entry, String attribute) throws MessagingException {
    String folderName = folder.getFullName();
    List<Annotation> annotations = getAnnotations(folder, entry, attribute);
    for (Annotation annotation : annotations) {
      if (
          annotation.getMailboxName().equals(folderName)
              && annotation.getEntry().equals(entry)
      ) {
        // Look for the "value.shared" attribute
        String value = annotation.getAttribute(attribute);
        if (value != null) {
          return value;
        }
      }
    }
    // Not found
    return null;
  }

  /**
   * Sets a single annotation.
   *
   * @param  expectedValue  if <code>null</code>, annotation will be removed
   */
  private static void setAnnotation(IMAPFolder folder, final String entry, final String value, final String contentType) throws MessagingException {
    final String mailboxName = folder.getFullName();
    final String newValue;
    final String newContentType;
    if (value == null) {
      newValue = "NIL";
      newContentType = "NIL";
    } else {
      newValue = value;
      newContentType = contentType;
    }
    folder.doCommand(p -> {
      // Issue command
      Argument list = new Argument();
      list.writeNString("value.shared");
      list.writeNString(newValue);
      list.writeNString("content-type.shared");
      list.writeNString(newContentType);

      Argument args = new Argument();
      args.writeString(mailboxName);
      args.writeNString(entry);
      args.writeArgument(list);

      Response[] r = p.command("SETANNOTATION", args);
      Response response = r[r.length - 1];

      // Grab response
      if (!response.isOK()) {
        throw new ProtocolException("Response is not OK: " + response);
      }

      // dispatch remaining untagged responses
      p.notifyResponseHandlers(r);
      p.handleResult(response);

      return null;
    });
  }

  private static long getCyrusFolderSize(User.Name username, String folder, boolean notFoundOk) throws IOException, SQLException, MessagingException {
    return getCyrusFolderSize(getUser(username), folder, getDomain(username), notFoundOk);
  }

  /**
   * @param notFoundOk if <code>true</code> will return <code>0</code> if annotation not found, MessagingException otherwise
   */
  @SuppressWarnings("SleepWhileInLoop")
  private static long getCyrusFolderSize(String user, String folder, String domain, boolean notFoundOk) throws IOException, SQLException, MessagingException {
    try {
      // Connect to the store (will be null when not an IMAP server)
      IMAPStore store = getAdminStore();
      if (store == null) {
        if (!notFoundOk) {
          throw new MessagingException("Not an IMAP server");
        }
        return 0;
      }
      String folderName = getFolderName(user, domain, folder);
      int attempt = 1;
      for (; attempt <= 10; attempt++) {
        try {
          IMAPFolder mailbox = (IMAPFolder) store.getFolder(folderName);
          try {
            String value = getAnnotation(mailbox, "/vendor/cmu/cyrus-imapd/size", "value.shared");
            if (value != null) {
              return Long.parseLong(value);
            }
            if (!notFoundOk) {
              throw new MessagingException(folderName + ": \"/vendor/cmu/cyrus-imapd/size\" \"value.shared\" annotation not found");
            }
            return 0;
          } finally {
            if (mailbox.isOpen()) {
              mailbox.close(false);
            }
          }
        } catch (MessagingException messagingException) {
          String message = messagingException.getMessage();
          if (message == null || !message.contains("* BYE idle for too long")) {
            throw messagingException;
          }
          logger.log(Level.SEVERE, "attempt=" + attempt, messagingException);
          try {
            Thread.sleep(100);
          } catch (InterruptedException err) {
            logger.log(Level.WARNING, null, err);
            // Restore the interrupted status
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
      throw new MessagingException("Unable to get folder size after " + (attempt - 1) + " attempts");
    } catch (Error | RuntimeException | IOException | SQLException | MessagingException err) {
      closeAdminStore();
      throw err;
    }
  }

  public static long getInboxSize(User.Name username) throws IOException, SQLException, MessagingException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (
        osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
    ) {
      return getCyrusFolderSize(username, "", true);
      /*
ad GETANNOTATION user/cyrus.test/Junk@suspendo.aoindustries.com "*" "value.shared"
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/expire" ("value.shared" "31")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/condstore" ("value.shared" "false")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/lastpop" ("value.shared" " ")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/lastupdate" ("value.shared" " 7-Dec-2008 20:36:25 -0600")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/size" ("value.shared" "33650")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/partition" ("value.shared" "default")
ad OK Completed
*/
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
  }

  public static long getInboxModified(User.Name username) throws IOException, SQLException, MessagingException, ParseException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (
        osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
    ) {
      try {
        // Connect to the store
        IMAPStore store = getAdminStore();
        if (store == null) {
          // Not an IMAP server, consistent with File.lastModified() above
          return 0L;
        }
        String user = getUser(username);
        String domain = getDomain(username);
        String inboxFolderName = getFolderName(user, domain, "");
        IMAPFolder inboxFolder = (IMAPFolder) store.getFolder(inboxFolderName);
        try {
          String value = getAnnotation(inboxFolder, "/vendor/cmu/cyrus-imapd/lastupdate", "value.shared");
          if (value == null) {
            throw new MessagingException("username = " + username + ": \"/vendor/cmu/cyrus-imapd/lastupdate\" \"value.shared\" annotation not found");
          }

          // Parse values
          // 8-Dec-2008 00:24:30 -0600
          value = value.trim();
          // Day
          int hyphen1 = value.indexOf('-');
          if (hyphen1 == -1) {
            throw new ParseException("Can't find first -", 0);
          }
          final int day = Integer.parseInt(value.substring(0, hyphen1));
          // Mon
          int hyphen2 = value.indexOf('-', hyphen1 + 1);
          if (hyphen2 == -1) {
            throw new ParseException("Can't find second -", hyphen1 + 1);
          }
          String monthString = value.substring(hyphen1 + 1, hyphen2);
          final int month;
          switch (monthString) {
            case "Jan":
              month = Calendar.JANUARY;
              break;
            case "Feb":
              month = Calendar.FEBRUARY;
              break;
            case "Mar":
              month = Calendar.MARCH;
              break;
            case "Apr":
              month = Calendar.APRIL;
              break;
            case "May":
              month = Calendar.MAY;
              break;
            case "Jun":
              month = Calendar.JUNE;
              break;
            case "Jul":
              month = Calendar.JULY;
              break;
            case "Aug":
              month = Calendar.AUGUST;
              break;
            case "Sep":
              month = Calendar.SEPTEMBER;
              break;
            case "Oct":
              month = Calendar.OCTOBER;
              break;
            case "Nov":
              month = Calendar.NOVEMBER;
              break;
            case "Dec":
              month = Calendar.DECEMBER;
              break;
            default:
              throw new ParseException("Unexpected month: " + monthString, hyphen1 + 1);
          }
          // Year
          int space1 = value.indexOf(' ', hyphen2 + 1);
          if (space1 == -1) {
            throw new ParseException("Can't find first space", hyphen2 + 1);
          }
          final int year = Integer.parseInt(value.substring(hyphen2 + 1, space1));
          // Hour
          int colon1 = value.indexOf(':', space1 + 1);
          if (colon1 == -1) {
            throw new ParseException("Can't find first colon", space1 + 1);
          }
          final int hour = Integer.parseInt(value.substring(space1 + 1, colon1));
          // Minute
          int colon2 = value.indexOf(':', colon1 + 1);
          if (colon2 == -1) {
            throw new ParseException("Can't find second colon", colon1 + 1);
          }
          final int minute = Integer.parseInt(value.substring(colon1 + 1, colon2));
          // Second
          int space2 = value.indexOf(' ', colon2 + 1);
          if (space2 == -1) {
            throw new ParseException("Can't find second space", colon2 + 1);
          }
          final int second = Integer.parseInt(value.substring(colon2 + 1, space2));
          // time zone
          final int zoneHours = Integer.parseInt(value.substring(space2 + 1, value.length() - 2));
          int zoneMinutes = Integer.parseInt(value.substring(value.length() - 2));
          if (zoneHours < 0) {
            zoneMinutes = -zoneMinutes;
          }

          // Convert to correct time
          GregorianCalendar gcal = new GregorianCalendar(Locale.US);
          // TODO: Use TimeZone instead?
          gcal.set(Calendar.ZONE_OFFSET, zoneHours * 60 * 60 * 1000 + zoneMinutes * 60 * 1000);
          gcal.set(Calendar.YEAR, year);
          gcal.set(Calendar.MONTH, month);
          gcal.set(Calendar.DAY_OF_MONTH, day);
          gcal.set(Calendar.HOUR_OF_DAY, hour);
          gcal.set(Calendar.MINUTE, minute);
          gcal.set(Calendar.SECOND, second);
          gcal.set(Calendar.MILLISECOND, 0);
          return gcal.getTimeInMillis();
        } finally {
          if (inboxFolder.isOpen()) {
            inboxFolder.close(false);
          }
        }
      } catch (Error | RuntimeException | IOException | SQLException | MessagingException err) {
        closeAdminStore();
        throw err;
      }
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
  }

  /**
   * Checks if cyrus-imapd is expected to be enabled on this server.
   *
   * <p>This is used to know when to enable saslauthd (See {@link SaslauthdManager}.</p>
   *
   * @see AppProtocol#IMAP2
   * @see AppProtocol#SIMAP
   * @see AppProtocol#POP3
   * @see AppProtocol#SPOP3
   * @see AppProtocol#SIEVE
   */
  public static boolean isCyrusImapdEnabled() throws IOException, SQLException {
    return AoservDaemon.getThisServer().getCyrusImapdServer() != null;
  }

  /**
   * Configures backups for cyrus-imapd.
   */
  public static void addFilesystemIteratorRules(FileReplication ffr, Map<String, FilesystemIteratorRule> filesystemRules) throws IOException, SQLException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (
        osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            || osvId == OperatingSystemVersion.CENTOS_7_X86_64
    ) {
      filesystemRules.put("/var/lib/imap/proc/", FilesystemIteratorRule.SKIP);
      filesystemRules.put("/var/lib/imap/socket/", FilesystemIteratorRule.SKIP);
      filesystemRules.put("/var/lock/subsys/cyrus-imapd", FilesystemIteratorRule.SKIP);
      filesystemRules.put("/var/run/cyrus-master.pid", FilesystemIteratorRule.SKIP);
      // See http://cyrusimap.web.cmu.edu/old/docs/cyrus-imapd/2.4.6/internal/var_directory_structure.php
      filesystemRules.put("/var/spool/imap/stage.", FilesystemIteratorRule.SKIP);
      filesystemRules.put("/var/spool/imap/sync.", FilesystemIteratorRule.SKIP);
      // Automatically exclude all Junk filters
      for (final UserServer lsa : thisServer.getLinuxServerAccounts()) {
        User la = lsa.getLinuxAccount();
        final PosixPath homePath = lsa.getHome();
        if (la.getType().isEmail() && homePath.toString().startsWith("/home/")) {
          // Split into user and domain
          final User.Name laUsername = la.getUsername_id();
          String user = getUser(laUsername);
          String domain = getDomain(laUsername);
          if ("default".equals(domain)) {
            // /var/spool/imap/(u)/user/(user^name)/Junk
            filesystemRules.put(
                "/var/spool/imap/"
                    + user.charAt(0)
                    + "/user/"
                    + user.replace('.', '^')
                    + "/Junk",
                FilesystemIteratorRule.SKIP
            );
          } else {
            // /var/spool/imap/domain/(d)/(domain.com)/(u)/user/(user^name)/Junk
            filesystemRules.put(
                "/var/spool/imap/domain/"
                    + domain.charAt(0)
                    + "/"
                    + domain
                    + "/"
                    + user.charAt(0)
                    + "/user/"
                    + user.replace('.', '^')
                    + "/Junk",
                FilesystemIteratorRule.SKIP
            );
          }
        }
      }
    }
  }
}
