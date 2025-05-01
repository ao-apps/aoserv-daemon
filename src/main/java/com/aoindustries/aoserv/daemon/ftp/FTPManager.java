/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.ftp;

import com.aoapps.collections.AoCollections;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.ftp.GuestUser;
import com.aoindustries.aoserv.client.ftp.PrivateServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.net.TcpRedirect;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of FTP configs and files.
 */
public final class FTPManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(FTPManager.class.getName());

  private static final PosixFile vsFtpdConfNew = new PosixFile("/etc/vsftpd/vsftpd.conf.new");
  private static final PosixFile vsFtpdConf = new PosixFile("/etc/vsftpd/vsftpd.conf");
  private static final PosixFile vsFtpdVhostsirectory = new PosixFile("/etc/vsftpd/vhosts");
  private static final PosixFile vsFtpdChrootList = new PosixFile("/etc/vsftpd/chroot_list");
  private static final PosixFile vsFtpdChrootListNew = new PosixFile("/etc/vsftpd/chroot_list.new");

  /**
   * The directory that is used for site-independent FTP access.
   */
  private static final PosixFile sharedFtpDirectory = new PosixFile("/var/ftp/pub");

  private static FTPManager ftpManager;

  private FTPManager() {
    // Do nothing
  }

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      Server thisServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();
      synchronized (rebuildLock) {
        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          doRebuildVsFtpd();
        } else {
          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }

        doRebuildSharedFtpDirectory();
      }
      return true;
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  /**
   * Rebuilds a vsftpd installation.
   */
  private static void doRebuildVsFtpd() throws IOException, SQLException {
    AoservConnector conn = AoservDaemon.getConnector();
    Server thisServer = AoservDaemon.getThisServer();
    int uidMin = thisServer.getUidMin().getId();
    int gidMin = thisServer.getGidMin().getId();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
      // Reused below
      final ByteArrayOutputStream bout = new ByteArrayOutputStream();

      // Jailed users
      {
        bout.reset();
        try (ChainWriter out = new ChainWriter(bout)) {
          for (GuestUser ftpGuestUser : thisServer.getFtpGuestUsers()) {
            out.print(ftpGuestUser.getLinuxAccount().getUsername().getUsername()).print('\n');
          }
        }
        byte[] newBytes = bout.toByteArray();

        // Only write to filesystem if missing or changed
        if (!vsFtpdChrootList.getStat().exists() || !vsFtpdChrootList.contentEquals(newBytes)) {
          try (FileOutputStream newOut = vsFtpdChrootListNew.getSecureOutputStream(PosixFile.ROOT_UID, PosixFile.ROOT_GID, 0600, true, uidMin, gidMin)) {
            newOut.write(newBytes);
          }
          vsFtpdChrootListNew.renameTo(vsFtpdChrootList);
        }
      }

      // Write the default config file
      {
        bout.reset();
        try (ChainWriter out = new ChainWriter(bout)) {
          out.print("# BOOLEAN OPTIONS\n"
              + "anonymous_enable=YES\n"
              + "async_abor_enable=YES\n"
              + "chroot_list_enable=YES\n"
              + "connect_from_port_20=YES\n"
              + "dirmessage_enable=YES\n"
              + "hide_ids=YES\n"
              + "local_enable=YES\n"
              + "ls_recurse_enable=NO\n"
              + "text_userdb_names=NO\n"
              + "userlist_enable=YES\n"
              + "write_enable=YES\n"
              + "xferlog_enable=YES\n"
              + "xferlog_std_format=YES\n"
              + "\n"
              + "# NUMERIC OPTIONS\n"
              + "accept_timeout=60\n"
              + "anon_max_rate=125000\n"
              + "connect_timeout=60\n"
              + "data_connection_timeout=7200\n"
              + "idle_session_timeout=7200\n"
              + "local_umask=002\n"
              + "pasv_max_port=50175\n"
              + "pasv_min_port=49152\n"
              + "\n"
              + "# STRING OPTIONS\n"
              + "chroot_list_file=/etc/vsftpd/chroot_list\n"
              + "ftpd_banner=FTP Host [").print(thisServer.getHostname()).print("]\n"
              + "pam_service_name=vsftpd\n");
        }
        byte[] newBytes = bout.toByteArray();

        // Only write to filesystem if missing or changed
        if (!vsFtpdConf.getStat().exists() || !vsFtpdConf.contentEquals(newBytes)) {
          try (FileOutputStream newOut = vsFtpdConfNew.getSecureOutputStream(PosixFile.ROOT_UID, PosixFile.ROOT_GID, 0600, true, uidMin, gidMin)) {
            newOut.write(newBytes);
          }
          vsFtpdConfNew.renameTo(vsFtpdConf);
        }
      }

      // Specific net_binds
      {
        // Make the vhosts directory if it doesn't exist
        if (!vsFtpdVhostsirectory.getStat().exists()) {
          vsFtpdVhostsirectory.mkdir(false, 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID);
        }

        // Find all the FTP binds
        List<Bind> binds = thisServer.getHost().getNetBinds(conn.getNet().getAppProtocol().get(AppProtocol.FTP));

        // Keep a list of the files that were verified
        Set<String> existing = AoCollections.newHashSet(binds.size());

        // Write each config file
        for (Bind bind : binds) {
          TcpRedirect redirect = bind.getNetTcpRedirect();
          PrivateServer privateServer = bind.getPrivateFtpServer();
          if (redirect != null) {
            if (privateServer != null) {
              throw new SQLException("Bind allocated as both TcpRedirect and PrivateServer: " + bind.getPkey());
            }
          } else {
            com.aoapps.net.Protocol netProtocol = bind.getPort().getProtocol();
            if (netProtocol != com.aoapps.net.Protocol.TCP) {
              throw new SQLException("vsftpd may only be configured for TCP service:  (net_binds.pkey=" + bind.getPkey() + ").net_protocol=" + netProtocol);
            }
            IpAddress ia = bind.getIpAddress();

            // Write to buffer
            bout.reset();
            try (ChainWriter out = new ChainWriter(bout)) {
              out.print("# BOOLEAN OPTIONS\n"
                  + "anonymous_enable=").print(privateServer == null || privateServer.allowAnonymous() ? "YES" : "NO").print("\n"
                  + "async_abor_enable=YES\n"
                  + "chroot_list_enable=YES\n"
                  + "connect_from_port_20=YES\n"
                  + "dirmessage_enable=YES\n"
                  + "hide_ids=").print(privateServer == null || privateServer.allowAnonymous() ? "YES" : "NO").print("\n"
                  + "local_enable=YES\n"
                  + "ls_recurse_enable=NO\n"
                  + "text_userdb_names=").print(privateServer == null || privateServer.allowAnonymous() ? "NO" : "YES").print("\n"
                  + "userlist_enable=YES\n"
                  + "write_enable=YES\n"
                  + "xferlog_enable=YES\n"
                  + "xferlog_std_format=YES\n"
                  + "\n"
                  + "# NUMERIC OPTIONS\n"
                  + "accept_timeout=60\n"
                  + "anon_max_rate=125000\n"
                  + "connect_timeout=60\n"
                  + "data_connection_timeout=7200\n"
                  + "idle_session_timeout=7200\n"
                  + "local_umask=002\n"
                  + "pasv_max_port=50175\n"
                  + "pasv_min_port=49152\n"
                  + "\n"
                  + "# STRING OPTIONS\n"
                  + "chroot_list_file=/etc/vsftpd/chroot_list\n");
              if (privateServer != null) {
                out.print("ftp_username=").print(privateServer.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername()).print('\n');
              }
              out.print("ftpd_banner=FTP Host [").print(
                  privateServer != null
                      ? privateServer.getHostname()
                      : (
                      ia.getInetAddress().isUnspecified()
                          ? thisServer.getHostname()
                          : ia.getHostname()
                  )
              ).print("]\n"
                  + "pam_service_name=vsftpd\n");
              if (privateServer != null) {
                out.print("xferlog_file=").print(privateServer.getLogfile()).print('\n');
              }
            }
            byte[] newBytes = bout.toByteArray();

            // Only write to filesystem if missing or changed
            String filename = "vsftpd_" + bind.getIpAddress().getInetAddress().toString() + "_" + bind.getPort().getPort() + ".conf";
            if (!existing.add(filename)) {
              throw new SQLException("Filename already used: " + filename);
            }
            PosixFile confFile = new PosixFile(vsFtpdVhostsirectory, filename, false);
            if (!confFile.getStat().exists() || !confFile.contentEquals(newBytes)) {
              PosixFile newConfFile = new PosixFile(vsFtpdVhostsirectory, filename + ".new", false);
              try (FileOutputStream newOut = newConfFile.getSecureOutputStream(PosixFile.ROOT_UID, PosixFile.ROOT_GID, 0600, true, uidMin, gidMin)) {
                newOut.write(newBytes);
              }
              newConfFile.renameTo(confFile);
            }
          }
        }

        // Clean the vhosts directory
        for (String filename : vsFtpdVhostsirectory.list()) {
          if (!existing.contains(filename)) {
            new PosixFile(vsFtpdVhostsirectory, filename, false).delete();
          }
        }
      }
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
  }

  /**
   * Rebuilds the contents of /var/ftp/pub  Each site optionally gets its own
   * shared FTP space.
   */
  private static void doRebuildSharedFtpDirectory() throws IOException, SQLException {
    Set<PosixFile> restorecon = new LinkedHashSet<>();
    try {
      List<File> deleteFileList = new ArrayList<>();

      Set<String> ftpDirectories;
      {
        String[] list = sharedFtpDirectory.list();
        if (list == null) {
          ftpDirectories = new HashSet<>();
        } else {
          ftpDirectories = new HashSet<>(Arrays.asList(list));
        }
      }

      for (Site httpdSite : AoservDaemon.getThisServer().getHttpdSites()) {
        HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);

        /*
         * Make the private FTP space, if needed.
         */
        if (manager.enableAnonymousFtp()) {
          String siteName = httpdSite.getName();
          manager.configureFtpDirectory(new PosixFile(sharedFtpDirectory, siteName, false), restorecon);
          ftpDirectories.remove(siteName);
        }
      }

      File sharedFtpDirectoryFile = sharedFtpDirectory.getFile();
      for (String filename : ftpDirectories) {
        File toDelete = new File(sharedFtpDirectoryFile, filename);
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Scheduling for removal: " + toDelete);
        }
        deleteFileList.add(toDelete);
      }

      // Back-up and delete the files scheduled for removal
      BackupManager.backupAndDeleteFiles(deleteFileList);
    } finally {
      DaemonFileUtils.restorecon(restorecon);
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
              && AoservDaemonConfiguration.isManagerEnabled(FTPManager.class)
              && ftpManager == null
      ) {
        System.out.print("Starting FTPManager: ");
        // Must be a supported operating system
        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          AoservConnector conn = AoservDaemon.getConnector();
          ftpManager = new FTPManager();
          conn.getFtp().getGuestUser().addTableListener(ftpManager, 0);
          conn.getWeb().getSite().addTableListener(ftpManager, 0);
          conn.getNet().getIpAddress().addTableListener(ftpManager, 0);
          conn.getLinux().getUser().addTableListener(ftpManager, 0);
          conn.getLinux().getUserServer().addTableListener(ftpManager, 0);
          conn.getNet().getBind().addTableListener(ftpManager, 0);
          conn.getFtp().getPrivateServer().addTableListener(ftpManager, 0);
          conn.getAccount().getUser().addTableListener(ftpManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  /**
   * Removes any file in the directory that is not listed in <code>files</code>.
   */
  public static void trimFiles(PosixFile dir, String[] files) throws IOException {
    String[] list = dir.list();
    if (list != null) {
      int len = list.length;
      int flen = files.length;
      for (int c = 0; c < len; c++) {
        String filename = list[c];
        boolean found = false;
        for (int d = 0; d < flen; d++) {
          if (filename.equals(files[d])) {
            found = true;
            break;
          }
        }
        if (!found) {
          PosixFile file = new PosixFile(dir, filename, false);
          if (file.getStat().exists()) {
            file.delete();
          }
        }
      }
    }
  }

  /**
   * Removes any file in the directory that is not listed in <code>files</code>.
   */
  public static void trimFiles(PosixFile dir, List<String> files) throws IOException {
    String[] sa = new String[files.size()];
    files.toArray(sa);
    trimFiles(dir, sa);
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild FTP";
  }
}
