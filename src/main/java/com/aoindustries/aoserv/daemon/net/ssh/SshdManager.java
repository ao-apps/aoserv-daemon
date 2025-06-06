/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.net.ssh;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.encoding.EncodingContext;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.InetAddress;
import com.aoapps.net.Port;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.selinux.SEManagePort;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of SSHD configs and files.
 */
public final class SshdManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(SshdManager.class.getName());

  /**
   * The default SSH port.
   */
  private static final int DEFAULT_PORT = 22;

  /**
   * The value for MaxStartups.
   */
  private static final String MAX_STARTUPS = "60:30:100";

  /**
   * The SELinux type for the SSH daemon.
   */
  private static final String SELINUX_TYPE = "ssh_port_t";

  /**
   * Nine years ago we submitted a patch to eliminate the build-time limit on the number
   * of ListenAddress allowed in <code>sshd_config</code>.  We just tested CentOS 7 and
   * this limit still remains.
   *
   * <p>Software should just do what it's told without arbitrary compile-time limits, but
   * this is out of our control.</p>
   *
   * <p>Our use-case has changed as we move away from large shared servers into smaller
   * focused Xen-based virtual servers.  As such, we are no longer packing tons of
   * different IPs into single servers.</p>
   *
   * <p>Rather than continue building our own customized SSH RPM, we will simply refuse to
   * build any config file with more than 16 <code>net_binds</code>.</p>
   */
  private static final int MAX_LISTEN_SOCKS = 16; // Matches the value defined in sshd.c

  private static final PosixFile SSHD_CONFIG = new PosixFile("/etc/ssh/sshd_config");

  /**
   * Uses same encoding as {@link ChainWriter}.
   */
  private static final Charset ENCODING = EncodingContext.DEFAULT.getCharacterEncoding();

  private static final String SFTP_SUBSYSTEM = "Subsystem\tsftp\t/usr/libexec/openssh/sftp-server";

  private static SshdManager sshdManager;

  private SshdManager() {
    // Do nothing
  }

  private static void writeListenAddresses(Collection<? extends Bind> nbs, ChainWriter out, int maxListenSocks) throws SQLException, IOException {
    if (nbs.isEmpty()) {
      // Restore defaults
      out.print("#ListenAddress 0.0.0.0\n"
          + "#ListenAddress ::\n");
    } else {
      int count = 0;
      for (Bind nb : nbs) {
        if (count == maxListenSocks) {
          out.print("#\n"
              + "# Warning: MAX_LISTEN_SOCKS (" + maxListenSocks + ") exceeded, remaining ListenAddress are disabled.\n"
              + "#\n");
        }
        if (count >= maxListenSocks) {
          out.print('#');
        }
        out.print("ListenAddress ");
        InetAddress ip = nb.getIpAddress().getInetAddress();
        ProtocolFamily family = ip.getProtocolFamily();
        if (family.equals(StandardProtocolFamily.INET)) {
          out.print(ip.toString());
        } else if (family.equals(StandardProtocolFamily.INET6)) {
          out.print('[').print(ip.toString()).print(']');
        } else {
          throw new AssertionError("Unexpected family: " + family);
        }
        int port = nb.getPort().getPort();
        if (port != DEFAULT_PORT) {
          out.print(':').print(port);
        }
        out.print("\n");
        count++;
      }
    }
  }

  /**
   * Gets the three-digit octal representation of the given mask.
   *
   * @param sftpUmask Must be in the octal range 000 to 777.
   */
  private static String getSftpUmaskString(long sftpUmask) {
    String octal = Long.toOctalString(sftpUmask);
    if (sftpUmask < 0000) {
      throw new IllegalArgumentException("sftpUmask < 0000 : " + octal);
    }
    if (sftpUmask > 0777) {
      throw new IllegalArgumentException("sftpUmask > 0777 : " + octal);
    }
    int len = octal.length();
    if (len == 1) {
      octal = "00" + octal;
    } else if (len == 2) {
      octal = '0' + octal;
    }
    return octal;
  }

  /**
   * Builds the config file for CentOS 5.
   */
  private static void writeConfigFileCentos5(Server thisServer, Collection<? extends Bind> nbs, ChainWriter out) throws SQLException, IOException {
    out.print("#\n"
        + "# This configuration file is automatically generated by\n"
        + "# ").print(SshdManager.class.getName()).print("\n"
        + "#\n"
        + "Port " + DEFAULT_PORT + "\n"
        + "Protocol 2\n");
    // Changed to not allow Protocol 1 on 2005-02-01 by Dan Armstrong
    //+ "Protocol 2,1\n");
    writeListenAddresses(nbs, out, Integer.MAX_VALUE);
    out.print("AcceptEnv SCREEN_SESSION\n"
        + "SyslogFacility AUTHPRIV\n"
        + "PermitRootLogin yes\n"
        + "PasswordAuthentication yes\n"
        + "ChallengeResponseAuthentication no\n"
        + "GSSAPIAuthentication yes\n"
        + "GSSAPICleanupCredentials yes\n"
        + "UsePAM yes\n"
        + "AcceptEnv LANG LC_CTYPE LC_NUMERIC LC_TIME LC_COLLATE LC_MONETARY LC_MESSAGES\n"
        + "AcceptEnv LC_PAPER LC_NAME LC_ADDRESS LC_TELEPHONE LC_MEASUREMENT\n"
        + "AcceptEnv LC_IDENTIFICATION LC_ALL\n"
        + "MaxStartups " + MAX_STARTUPS + "\n"
        + "X11Forwarding yes\n"
        + "UsePrivilegeSeparation yes\n");
    out.print(SFTP_SUBSYSTEM.replace('\t', ' '));
    out.print(" -l VERBOSE");
    long sftpUmask = thisServer.getSftpUmask();
    if (sftpUmask != -1) {
      out.print(" -u ").print(getSftpUmaskString(sftpUmask));
    }
    out.print('\n');
  }

  /**
   * Builds the config file for CentOS 7.
   */
  private static void writeConfigFileCentos7(Server thisServer, Collection<? extends Bind> nbs, ChainWriter out) throws SQLException, IOException {
    out.print("#\n"
        + "# This configuration file is automatically generated by\n"
        + "# ").print(SshdManager.class.getName()).print("\n"
        + "#\n"
        + "\n"
        + "#\t$OpenBSD: sshd_config,v 1.100 2016/08/15 12:32:04 naddy Exp $\n"
        + "\n"
        + "# This is the sshd server system-wide configuration file.  See\n"
        + "# sshd_config(5) for more information.\n"
        + "\n"
        + "# This sshd was compiled with PATH=/usr/local/bin:/usr/bin\n"
        + "\n"
        + "# The strategy used for options in the default sshd_config shipped with\n"
        + "# OpenSSH is to specify options with their default value where\n"
        + "# possible, but leave them commented.  Uncommented options override the\n"
        + "# default value.\n"
        + "\n"
        + "# If you want to change the port on a SELinux system, you have to tell\n"
        + "# SELinux about this change.\n"
        + "# semanage port -a -t ssh_port_t -p tcp #PORTNUMBER\n"
        + "#\n"
        + "Port " + DEFAULT_PORT + "\n");
    if (nbs.isEmpty()) {
      // Restore to default settings before disabling service
      out.print("#AddressFamily any\n");
    } else {
      // Determine address family
      boolean hasIpv4 = false;
      boolean hasIpv6 = false;
      for (Bind nb : nbs) {
        InetAddress ip = nb.getIpAddress().getInetAddress();
        ProtocolFamily family = ip.getProtocolFamily();
        if (family.equals(StandardProtocolFamily.INET)) {
          hasIpv4 = true;
          if (hasIpv6) {
            break;
          }
        } else if (family.equals(StandardProtocolFamily.INET6)) {
          hasIpv6 = true;
          if (hasIpv4) {
            break;
          }
        } else {
          throw new AssertionError("Unexpected family: " + family);
        }
      }
      out.print("AddressFamily ");
      if (hasIpv4 && hasIpv6) {
        out.print("any"); // Both IPv4 and IPv6
      } else if (hasIpv4) {
        out.print("inet"); // IPv4 only
      } else if (hasIpv6) {
        out.print("inet6"); // IPv6 only
      } else {
        throw new AssertionError();
      }
      out.print('\n');
    }
    writeListenAddresses(nbs, out, Integer.MAX_VALUE);
    out.print("\n"
        + "HostKey /etc/ssh/ssh_host_rsa_key\n"
        + "#HostKey /etc/ssh/ssh_host_dsa_key\n"
        + "HostKey /etc/ssh/ssh_host_ecdsa_key\n"
        + "HostKey /etc/ssh/ssh_host_ed25519_key\n"
        + "\n"
        + "# Ciphers and keying\n"
        + "#RekeyLimit default none\n"
        + "\n"
        + "# Logging\n"
        + "#SyslogFacility AUTH\n"
        + "SyslogFacility AUTHPRIV\n"
        + "#LogLevel INFO\n"
        + "\n"
        + "# Authentication:\n"
        + "\n"
        + "#LoginGraceTime 2m\n"
        // TODO: When there is at least one non-disabled sudoer, should this be automatically set to "no"?
        + "#PermitRootLogin yes\n"
        + "#StrictModes yes\n"
        + "#MaxAuthTries 6\n"
        + "#MaxSessions 10\n"
        + "\n"
        + "#PubkeyAuthentication yes\n"
        + "\n"
        + "# The default is to check both .ssh/authorized_keys and .ssh/authorized_keys2\n"
        + "# but this is overridden so installations will only check .ssh/authorized_keys\n"
        + "AuthorizedKeysFile\t.ssh/authorized_keys\n"
        + "\n"
        + "#AuthorizedPrincipalsFile none\n"
        + "\n"
        + "#AuthorizedKeysCommand none\n"
        + "#AuthorizedKeysCommandUser nobody\n"
        + "\n"
        + "# For this to work you will also need host keys in /etc/ssh/ssh_known_hosts\n"
        + "#HostbasedAuthentication no\n"
        + "# Change to yes if you don't trust ~/.ssh/known_hosts for\n"
        + "# HostbasedAuthentication\n"
        + "#IgnoreUserKnownHosts no\n"
        + "# Don't read the user's ~/.rhosts and ~/.shosts files\n"
        + "#IgnoreRhosts yes\n"
        + "\n"
        + "# To disable tunneled clear text passwords, change to no here!\n"
        + "#PasswordAuthentication yes\n"
        + "#PermitEmptyPasswords no\n"
        + "PasswordAuthentication yes\n"
        + "\n"
        + "# Change to no to disable s/key passwords\n"
        + "#ChallengeResponseAuthentication yes\n"
        + "ChallengeResponseAuthentication no\n"
        + "\n"
        + "# Kerberos options\n"
        + "#KerberosAuthentication no\n"
        + "#KerberosOrLocalPasswd yes\n"
        + "#KerberosTicketCleanup yes\n"
        + "#KerberosGetAFSToken no\n"
        + "#KerberosUseKuserok yes\n"
        + "\n"
        + "# GSSAPI options\n"
        + "GSSAPIAuthentication yes\n"
        + "GSSAPICleanupCredentials no\n"
        + "#GSSAPIStrictAcceptorCheck yes\n"
        + "#GSSAPIKeyExchange no\n"
        + "#GSSAPIEnablek5users no\n"
        + "\n"
        + "# Set this to 'yes' to enable PAM authentication, account processing,\n"
        + "# and session processing. If this is enabled, PAM authentication will\n"
        + "# be allowed through the ChallengeResponseAuthentication and\n"
        + "# PasswordAuthentication.  Depending on your PAM configuration,\n"
        + "# PAM authentication via ChallengeResponseAuthentication may bypass\n"
        + "# the setting of \"PermitRootLogin without-password\".\n"
        + "# If you just want the PAM account and session checks to run without\n"
        + "# PAM authentication, then enable this but set PasswordAuthentication\n"
        + "# and ChallengeResponseAuthentication to 'no'.\n"
        + "# WARNING: 'UsePAM no' is not supported in Red Hat Enterprise Linux and may cause several\n"
        + "# problems.\n"
        + "UsePAM yes\n"
        + "\n"
        + "#AllowAgentForwarding yes\n"
        + "#AllowTcpForwarding yes\n"
        // We had on suspendo.aoindustries.com: GatewayPorts clientspecified
        + "#GatewayPorts no\n"
        + "X11Forwarding yes\n"
        + "#X11DisplayOffset 10\n"
        + "#X11UseLocalhost yes\n"
        + "#PermitTTY yes\n"
        + "#PrintMotd yes\n"
        + "#PrintLastLog yes\n"
        + "#TCPKeepAlive yes\n"
        + "#UseLogin no\n"
        + "#UsePrivilegeSeparation sandbox\n"
        + "#PermitUserEnvironment no\n"
        + "#Compression delayed\n"
        + "#ClientAliveInterval 0\n"
        + "#ClientAliveCountMax 3\n"
        + "#ShowPatchLevel no\n"
        + "#UseDNS yes\n"
        + "#PidFile /var/run/sshd.pid\n"
        + "MaxStartups " + MAX_STARTUPS + "\n"
        + "#PermitTunnel no\n"
        + "#ChrootDirectory none\n"
        + "#VersionAddendum none\n"
        + "\n"
        + "# no default banner path\n"
        + "#Banner none\n"
        + "\n"
        + "# Accept locale-related environment variables\n"
        + "AcceptEnv LANG LC_CTYPE LC_NUMERIC LC_TIME LC_COLLATE LC_MONETARY LC_MESSAGES\n"
        + "AcceptEnv LC_PAPER LC_NAME LC_ADDRESS LC_TELEPHONE LC_MEASUREMENT\n"
        + "AcceptEnv LC_IDENTIFICATION LC_ALL LANGUAGE\n"
        + "AcceptEnv XMODIFIERS\n"
        + "\n"
        + "# Accept variable for auto-screen\n"
        + "AcceptEnv SCREEN_SESSION\n"
        + "\n"
        + "# override default of no subsystems\n"
        + SFTP_SUBSYSTEM + " -l VERBOSE");
    long sftpUmask = thisServer.getSftpUmask();
    if (sftpUmask != -1) {
      out.print(" -u ").print(getSftpUmaskString(sftpUmask));
    }
    out.print("\n"
        + "\n"
        + "# Example of overriding settings on a per-user basis\n"
        + "#Match User anoncvs\n"
        + "#\tX11Forwarding no\n"
        + "#\tAllowTcpForwarding no\n"
        + "#\tPermitTTY no\n"
        + "#\tForceCommand cvs server\n"
    );
  }

  /**
   * Builds the /etc/ssh/sshd_config file for Rocky 9.
   */
  private static void writeConfigFileRocky9(Server thisServer, ChainWriter out) throws SQLException, IOException {
    boolean foundSftp = false;
    try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(SSHD_CONFIG.getFile()), ENCODING))) {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.startsWith(SFTP_SUBSYSTEM)) {
          if (foundSftp) {
            throw new AssertionError("More than one sftp subsystem defined");
          } else {
            foundSftp = true;
            out.print(SFTP_SUBSYSTEM + " -l VERBOSE");
            long sftpUmask = thisServer.getSftpUmask();
            if (sftpUmask != -1) {
              out.print(" -u ").print(getSftpUmaskString(sftpUmask));
            }
          }
        } else {
          out.print(line);
        }
        out.print('\n');
      }
    }
  }

  /**
   * Builds the /etc/ssh/sshd_config.d/10-binds.conf file for Rocky 9.
   */
  private static void writeBindsRocky9(Collection<? extends Bind> nbs, ChainWriter out) throws SQLException, IOException {
    out.print("#\n"
        + "# aoserv-sshd-config - SSH daemon configured by the AOServ Platform.\n"
        + "# Copyright (C) 2024  AO Industries, Inc.\n"
        + "#     support@aoindustries.com\n"
        + "#     7262 Bull Pen Cir\n"
        + "#     Mobile, AL 36695\n"
        + "#\n"
        + "# This file is part of aoserv-sshd-config.\n"
        + "#\n"
        + "# aoserv-sshd-config is free software: you can redistribute it and/or modify\n"
        + "# it under the terms of the GNU Lesser General Public License as published by\n"
        + "# the Free Software Foundation, either version 3 of the License, or\n"
        + "# (at your option) any later version.\n"
        + "#\n"
        + "# aoserv-sshd-config is distributed in the hope that it will be useful,\n"
        + "# but WITHOUT ANY WARRANTY; without even the implied warranty of\n"
        + "# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the\n"
        + "# GNU Lesser General Public License for more details.\n"
        + "#\n"
        + "# You should have received a copy of the GNU Lesser General Public License\n"
        + "# along with aoserv-sshd-config.  If not, see <https://www.gnu.org/licenses/>.\n"
        + "#\n"
        + "\n"
        + "#\n"
        + "# This configuration file is automatically generated by\n"
        + "# ").print(SshdManager.class.getName()).print("\n"
        + "#\n"
        + "#Port " + DEFAULT_PORT + "\n");
    if (nbs.isEmpty()) {
      // Restore to default settings before disabling service
      out.print("#AddressFamily any\n"
          + "#ListenAddress 0.0.0.0\n"
          + "#ListenAddress ::#\n");
    } else {
      // Determine address family
      boolean hasIpv4 = false;
      boolean hasIpv6 = false;
      for (Bind nb : nbs) {
        InetAddress ip = nb.getIpAddress().getInetAddress();
        ProtocolFamily family = ip.getProtocolFamily();
        if (family.equals(StandardProtocolFamily.INET)) {
          hasIpv4 = true;
          if (hasIpv6) {
            break;
          }
        } else if (family.equals(StandardProtocolFamily.INET6)) {
          hasIpv6 = true;
          if (hasIpv4) {
            break;
          }
        } else {
          throw new AssertionError("Unexpected family: " + family);
        }
      }
      out.print("AddressFamily ");
      if (hasIpv4 && hasIpv6) {
        out.print("any"); // Both IPv4 and IPv6
      } else if (hasIpv4) {
        out.print("inet"); // IPv4 only
      } else if (hasIpv6) {
        out.print("inet6"); // IPv6 only
      } else {
        throw new AssertionError();
      }
      out.print('\n');
    }
    writeListenAddresses(nbs, out, MAX_LISTEN_SOCKS);
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings("SleepWhileHoldingLock")
  protected boolean doRebuild() {
    try {
      Server thisServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();

      AoservConnector conn = AoservDaemon.getConnector();
      synchronized (rebuildLock) {
        Set<PosixFile> restorecon = new LinkedHashSet<>();
        try {
          // Find all the ports that should be bound to
          List<Bind> nbs = new ArrayList<>();
          boolean hasSpecificAddress = false;
          {
            AppProtocol sshProtocol = conn.getNet().getAppProtocol().get(AppProtocol.SSH);
            if (sshProtocol == null) {
              throw new SQLException("AppProtocol not found: " + AppProtocol.SSH);
            }
            for (Bind nb : thisServer.getHost().getNetBinds(sshProtocol)) {
              if (nb.getNetTcpRedirect() == null) {
                com.aoapps.net.Protocol netProtocol = nb.getPort().getProtocol();
                if (netProtocol != com.aoapps.net.Protocol.TCP) {
                  throw new IOException("Unsupported protocol for SSH: " + netProtocol);
                }
                nbs.add(nb);
                InetAddress ia = nb.getIpAddress().getInetAddress();
                if (!ia.isLoopback() && !ia.isUnspecified()) {
                  hasSpecificAddress = true;
                }
              }
            }
          }
          //if (nbs.size() > MAX_LISTEN_SOCKS) {
          //  throw new IOException("Refusing to build sshd_config with more than MAX_LISTEN_SOCKS(" + MAX_LISTEN_SOCKS + ") ListenAddress directives: " + nbs.size());
          //}
          // Restart only when something changed
          boolean[] needsRestart = {false};
          // Install openssh-server package if missing (when there is at least one port)
          if (!nbs.isEmpty()) {
            PackageManager.installPackage(
                PackageManager.PackageName.OPENSSH_SERVER,
                () -> {
                  // Enable service after package installation
                  if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    AoservDaemon.exec("/sbin/chkconfig", "sshd", "on");
                  } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64
                      || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                    AoservDaemon.exec("/usr/bin/systemctl", "enable", "sshd.service");
                  } else {
                    throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                  }
                  needsRestart[0] = true;
                }
            );
            // Install sshd-after-network-online package when needed
            if (hasSpecificAddress
                && (osvId == OperatingSystemVersion.CENTOS_7_X86_64 || osvId == OperatingSystemVersion.ROCKY_9_X86_64)
            ) {
              PackageManager.installPackage(PackageManager.PackageName.SSHD_AFTER_NETWORK_ONLINE);
            }
          }
          boolean isSshInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.OPENSSH_SERVER) != null;
          if (!nbs.isEmpty() && !isSshInstalled) {
            throw new AssertionError(PackageManager.PackageName.OPENSSH_SERVER + " not installed");
          }
          // Write/rewrite config when ssh server installed
          if (isSshInstalled) {
            // If there are not SSH ports, this will still build the config if the SSH daemon is installed.
            // In this case, the SSH daemon will be configured with no ListenAddress, which will default to
            // listening on all IPs should sshd be re-enable by the administrator.

            // Build the new config file to RAM
            byte[] newConfig;
            {
              ByteArrayOutputStream bout = new ByteArrayOutputStream();
              try (ChainWriter out = new ChainWriter(bout)) {
                if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                  writeConfigFileCentos5(thisServer, nbs, out);
                } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
                  writeConfigFileCentos7(thisServer, nbs, out);
                } else if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                  writeConfigFileRocky9(thisServer, out);
                } else {
                  throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                }
              }
              newConfig = bout.toByteArray();
            }

            // Write the new file only when file changed
            if (
                DaemonFileUtils.atomicWrite(
                    SSHD_CONFIG,
                    newConfig,
                    0600,
                    PosixFile.ROOT_UID,
                    PosixFile.ROOT_GID,
                    null,
                    restorecon
                )
            ) {
              needsRestart[0] = true;
            }
            if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
              // Install aoserv-sshd-config package before overwriting with specific configuration
              PackageManager.installPackage(PackageManager.PackageName.AOSERV_SSHD_CONFIG,
                  () -> needsRestart[0] = true);
              // Build the new config file to RAM
              byte[] newBindsConf;
              {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                try (ChainWriter out = new ChainWriter(bout)) {
                  writeBindsRocky9(nbs, out);
                }
                newBindsConf = bout.toByteArray();
              }
              // Write the new file only when file changed
              if (
                  DaemonFileUtils.atomicWrite(
                      new PosixFile("/etc/ssh/sshd_config.d/10-binds.conf"),
                      newBindsConf,
                      0600,
                      PosixFile.ROOT_UID,
                      PosixFile.ROOT_GID,
                      null,
                      restorecon
                  )
              ) {
                needsRestart[0] = true;
              }
            }
          }

          // SELinux before next steps
          DaemonFileUtils.restorecon(restorecon);
          restorecon.clear();

          // Manage SELinux:
          if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            // SELinux left in Permissive state, not configured here
          } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64
              || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
            // Note: SELinux configuration exists even without the openssh-server package installed.
            //       SELinux policies are provided independent of specific packages.
            //       Thus, we manage this even when the server not installed.

            // See https://bugzilla.redhat.com/show_bug.cgi?id=653579
            // Install /usr/sbin/semanage if missing
            if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
              PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON);
            } else if (osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
              PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON_UTILS);
            } else {
              throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
            }
            // Find the set of distinct ports used by SSH server
            SortedSet<Port> sshPorts = new TreeSet<>();
            for (Bind nb : nbs) {
              sshPorts.add(nb.getPort());
            }
            // Reconfigure SELinux ports
            if (SEManagePort.configure(sshPorts, SELINUX_TYPE)) {
              needsRestart[0] = true;
            }
          } else {
            throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
          }

          // Stop / restart after SELinux changes so ports may be opened
          if (isSshInstalled) {
            if (nbs.isEmpty()) {
              // Disable and shutdown service since there are no net_binds
              // openssh-server RPM is left installed
              if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                AoservDaemon.exec("/sbin/chkconfig", "sshd", "off");
                AoservDaemon.exec("/etc/rc.d/init.d/sshd", "stop");
              } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64
                  || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                AoservDaemon.exec("/usr/bin/systemctl", "disable", "sshd.service");
                AoservDaemon.exec("/usr/bin/systemctl", "stop", "sshd.service");
              } else {
                throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
              }
            } else if (needsRestart[0]) {
              if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                // Try reload config first
                try {
                  AoservDaemon.exec(
                      "/etc/rc.d/init.d/sshd",
                      "reload"
                  );
                } catch (IOException err) {
                  logger.log(Level.SEVERE, null, err);

                  // Try more forceful stop/start
                  try {
                    AoservDaemon.exec(
                        "/etc/rc.d/init.d/sshd",
                        "stop"
                    );
                  } catch (IOException err2) {
                    logger.log(Level.SEVERE, null, err2);
                  }
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException err2) {
                    logger.log(Level.WARNING, null, err2);
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                  }
                  AoservDaemon.exec(
                      "/etc/rc.d/init.d/sshd",
                      "start"
                  );
                }
              } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64
                  || osvId == OperatingSystemVersion.ROCKY_9_X86_64) {
                AoservDaemon.exec("/usr/bin/systemctl", "enable", "sshd.service");
                // TODO: Should this be reload-or-restart?
                AoservDaemon.exec("/usr/bin/systemctl", "restart", "sshd.service");
              } else {
                throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
              }
            }
          }
          // Uninstall sshd-after-network-online package when not needed
          if (
              !hasSpecificAddress
                  && (osvId == OperatingSystemVersion.CENTOS_7_X86_64 || osvId == OperatingSystemVersion.ROCKY_9_X86_64)
                  && AoservDaemonConfiguration.isPackageManagerUninstallEnabled()
          ) {
            PackageManager.removePackage(PackageManager.PackageName.SSHD_AFTER_NETWORK_ONLINE);
          }
        } finally {
          DaemonFileUtils.restorecon(restorecon);
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
              && AoservDaemonConfiguration.isManagerEnabled(SshdManager.class)
              && sshdManager == null
      ) {
        System.out.print("Starting SshdManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          sshdManager = new SshdManager();
          conn.getNet().getBind().addTableListener(sshdManager, 0);
          PackageManager.addPackageListener(sshdManager);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild SSH Configuration";
  }
}
