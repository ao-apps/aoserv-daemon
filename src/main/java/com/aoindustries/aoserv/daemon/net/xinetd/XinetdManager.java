/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2003-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.net.xinetd;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.Port;
import com.aoapps.net.Protocol;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.TcpRedirect;
import com.aoindustries.aoserv.client.scm.CvsRepository;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of xinetd configs and files.
 */
public final class XinetdManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(XinetdManager.class.getName());

  /**
   * The type used for UNLISTED services.
   */
  public static final String UNLISTED = "UNLISTED";

  private static XinetdManager xinetdManager;

  public static final File xinetdDirectory = new File("/etc/xinetd.d");

  private XinetdManager() {
    // Do nothing
  }

  private static final Object rebuildLock = new Object();

  @Override
  protected boolean doRebuild() {
    try {
      AoservConnector connector = AoservDaemon.getConnector();
      Server linuxServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = linuxServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      // Reused on inner loops
      final ByteArrayOutputStream bout = new ByteArrayOutputStream();

      synchronized (rebuildLock) {
        UserServer nobodyUser = linuxServer.getLinuxServerAccount(User.NOBODY);
        UserServer rootUser = linuxServer.getLinuxServerAccount(User.ROOT);
        GroupServer ttyGroup = linuxServer.getLinuxServerGroup(Group.TTY);

        // Build a list of services that should be running
        List<Bind> binds = linuxServer.getHost().getNetBinds();
        List<Service> services = new ArrayList<>(binds.size() + (ImapManager.WUIMAP_CONVERSION_ENABLED ? 1 : 0)); // Worst-case all binds are in xinetd

        if (ImapManager.WUIMAP_CONVERSION_ENABLED) {
          // Remove once conversion to CentOS has been completed
          services.add(
              new Service(
                  UNLISTED,
                  -1,
                  -1,
                  null,
                  null,
                  null,
                  "wuimap",
                  Protocol.TCP,
                  linuxServer.getPrimaryIpAddress(),
                  Port.valueOf(8143, Protocol.TCP),
                  false,
                  rootUser,
                  null,
                  "/opt/imap-2007d/bin/imapd",
                  null,
                  null,
                  "HOST DURATION",
                  "HOST USERID",
                  -1,
                  null,
                  null
              )
          );
        }

        for (Bind bind : binds) {
          Port port = bind.getPort();
          TcpRedirect redirect = bind.getNetTcpRedirect();
          AppProtocol protocolObj = bind.getAppProtocol();
          String protocol = protocolObj.getProtocol();
          if (
              redirect != null
                  // || protocol.equals(AppProtocolAUTH)
                  || protocol.equals(AppProtocol.CVSPSERVER)
                  || protocol.equals(AppProtocol.NTALK)
                  || protocol.equals(AppProtocol.TALK)
                  || protocol.equals(AppProtocol.TELNET)
                  || (
                  // FTP is handled through xinetd on CentOS 5
                  osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                      && protocol.equals(AppProtocol.FTP)
                )
          ) {
            Service service;
            if (redirect != null) {
              Protocol netProtocol = port.getProtocol();
              if (netProtocol != Protocol.TCP) {
                throw new SQLException("Only TCP ports may be redirected: (net_binds.pkey=" + bind.getPkey() + ").protocol=" + netProtocol);
              }

              service = new Service(
                  UNLISTED,
                  -1,
                  -1,
                  redirect.getConnectionsPerSecond() + " " + redirect.getConnectionsPerSecondOverloadSleepTime(),
                  null,
                  null,
                  "redirect",
                  netProtocol,
                  bind.getIpAddress(),
                  port,
                  false,
                  rootUser,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  -1,
                  null,
                  redirect.getDestinationHost().toString() + " " + redirect.getDestinationPort().getPort()
              );
            } else {
              boolean portMatches = protocolObj.getPort().equals(port);
              /*if (protocol.equals(AppProtocol.AUTH)) {
                service=new Service(
                  portMatches?null:UNLISTED,
                  -1,
                  null,
                  null,
                  portMatches?"auth":"auth-unlisted",
                  bind.getNetProtocol(),
                  bind.getInetAddress(),
                  portMatches?null:port,
                  true,
                  rootUser,
                  null,
                  "/usr/sbin/in.identd",
                  "-w -e",
                  null,
                  null,
                  -1,
                  null,
                  null
                );
              } else */
              switch (protocol) {
                case AppProtocol.CVSPSERVER:
                  {
                    List<CvsRepository> repos = linuxServer.getCvsRepositories();
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      StringBuilder server_args = new StringBuilder();
                      server_args.append("-f");
                      for (CvsRepository repo : repos) {
                        server_args.append(" --allow-root=").append(repo.getPath());
                      }
                      server_args.append(" pserver");
                      service = new Service(
                          portMatches ? null : UNLISTED,
                          -1,
                          -1,
                          "100 30",
                          null,
                          "REUSE",
                          portMatches ? "cvspserver" : "cvspserver-unlisted",
                          port.getProtocol(),
                          bind.getIpAddress(),
                          portMatches ? null : port,
                          false,
                          rootUser,
                          null,
                          "/usr/bin/cvs",
                          "HOME=" + CvsRepository.DEFAULT_CVS_DIRECTORY,
                          server_args.toString(),
                          "HOST DURATION",
                          "HOST USERID",
                          -1,
                          null,
                          null
                      );
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    break;
                  }
                case AppProtocol.FTP:
                  {
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      service = new Service(
                          portMatches ? null : UNLISTED,
                          100,
                          20, // Was 5, but confsys03 on www7.fc.aoindustries.com hit this limit on 2009-07-31
                          "100 30",
                          "/etc/vsftpd/busy_banner",
                          "IPv4",
                          portMatches ? "ftp" : "ftp-unlisted",
                          port.getProtocol(),
                          bind.getIpAddress(),
                          portMatches ? null : port,
                          false,
                          rootUser,
                          null,
                          "/usr/sbin/vsftpd",
                          null,
                          "/etc/vsftpd/vhosts/vsftpd_" + bind.getIpAddress().getInetAddress().toString() + "_" + port.getPort() + ".conf",
                          "PID HOST DURATION",
                          "HOST",
                          10,
                          null,
                          null
                      );
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    break;
                  }
                case AppProtocol.NTALK:
                  {
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      service = new Service(
                          portMatches ? null : UNLISTED,
                          -1, // instances
                          -1, // per_source
                          null, // cps
                          null, // banner_fail
                          "IPv4", // flags
                          portMatches ? "ntalk" : "ntalk-unlisted",
                          port.getProtocol(),
                          bind.getIpAddress(),
                          portMatches ? null : port,
                          true,
                          nobodyUser,
                          ttyGroup,
                          "/usr/sbin/in.ntalkd",
                          null, // env
                          null, // server_args
                          "HOST DURATION",
                          "HOST USERID",
                          -1,
                          null,
                          null
                      );
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    break;
                  }
                case AppProtocol.TALK:
                  {
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      service = new Service(
                          portMatches ? null : UNLISTED,
                          -1, // instances
                          -1, // per_source
                          null, // cps
                          null, // banner_fail
                          "IPv4", // flags
                          portMatches ? "talk" : "talk-unlisted",
                          port.getProtocol(),
                          bind.getIpAddress(),
                          portMatches ? null : port,
                          true,
                          nobodyUser,
                          ttyGroup,
                          "/usr/sbin/in.talkd",
                          null, // env
                          null, // server_args
                          "HOST DURATION",
                          "HOST USERID",
                          -1,
                          null,
                          null
                      );
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    break;
                  }
                case AppProtocol.TELNET:
                  {
                    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                      service = new Service(
                          portMatches ? null : UNLISTED,
                          -1,
                          -1,
                          "100 30",
                          null,
                          "REUSE",
                          portMatches ? "telnet" : "telnet-unlisted",
                          port.getProtocol(),
                          bind.getIpAddress(),
                          portMatches ? null : port,
                          false,
                          rootUser,
                          null,
                          "/usr/sbin/in.telnetd",
                          null,
                          null,
                          "HOST DURATION",
                          "HOST USERID",
                          -1,
                          null,
                          null
                      );
                    } else {
                      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                    }
                    break;
                  }
                default:
                  throw new RuntimeException("Unexpected protocol: " + protocol);
              }
            }

            // Do not add if is a duplicate ip address, net protocol, and port
            boolean foundDup = false;
            for (Service other : services) {
              if (service.bindMatches(other)) {
                foundDup = true;
                break;
              }
            }
            if (!foundDup) {
              services.add(service);
            }
          }
        }

        boolean needsReloaded = false;

        // (Re)build configs to match service list
        Set<String> filenames = new HashSet<>();
        final int numServices = services.size();
        for (int c = 0; c < numServices; c++) {
          Service service = services.get(c);
          String desiredFilename = service.getService();
          String filename = null;
          for (int d = 1; d < Integer.MAX_VALUE; d++) {
            String checkFilename = d == 1 ? desiredFilename : (desiredFilename + "-" + d);
            if (!filenames.contains(checkFilename)) {
              filename = checkFilename;
              break;
            }
          }
          if (filename == null) {
            throw new IOException("Unable to find available filename for service: " + desiredFilename);
          }
          filenames.add(filename);

          // Build to RAM first
          bout.reset();
          try (ChainWriter out = new ChainWriter(bout)) {
            service.printXinetdConfig(out);
          }
          byte[] newBytes = bout.toByteArray();

          // Move into place if different than existing
          PosixFile existingPosixFile = new PosixFile(xinetdDirectory, filename);
          if (
              !existingPosixFile.getStat().exists()
                  || !existingPosixFile.contentEquals(newBytes)
          ) {
            PosixFile newPosixFile = new PosixFile(xinetdDirectory, filename + ".new");
            try (OutputStream newOut = new FileOutputStream(newPosixFile.getFile())) {
              newPosixFile.setMode(0600);
              newOut.write(newBytes);
            }
            newPosixFile.renameTo(existingPosixFile);
            needsReloaded = true;
          }
        }

        // Cleanup extra configs
        String[] list = xinetdDirectory.list();
        if (list != null) {
          for (String filename : list) {
            if (!filenames.contains(filename)) {
              new PosixFile(xinetdDirectory, filename).delete();
              needsReloaded = true;
            }
          }
        }

        // Control service
        PosixFile rcFile = new PosixFile("/etc/rc.d/rc3.d/S56xinetd");
        if (numServices == 0) {
          // Turn off xinetd completely if not already off
          if (rcFile.getStat().exists()) {
            // Stop service
            AoservDaemon.exec("/etc/rc.d/init.d/xinetd", "stop");
            // Disable with chkconfig
            if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
              AoservDaemon.exec("/sbin/chkconfig", "xinetd", "off");
            } else {
              throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
            }
          }
        } else {
          // Turn on xinetd if not already on
          if (!rcFile.getStat().exists()) {
            // Enable with chkconfig
            if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
              AoservDaemon.exec("/sbin/chkconfig", "xinetd", "on");
            } else {
              throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
            }
            // Start service
            AoservDaemon.exec("/etc/rc.d/init.d/xinetd", "start");
          } else {
            // Reload xinetd if modified
            if (needsReloaded) {
              // Try reload config first
              // reload has several limitations documented in the man page for xinetd.conf, will always stop/start instead
              //try {
              //  AoservDaemon.exec(
              //    new String[] {
              //      "/etc/rc.d/init.d/xinetd",
              //      "reload"
              //    }
              //  );
              //} catch (IOException err) {
              //  logger.log(Level.SEVERE, null, err);

              // Try more forceful stop/start
              try {
                AoservDaemon.exec(
                    "/etc/rc.d/init.d/xinetd",
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
                  "/etc/rc.d/init.d/xinetd",
                  "start"
              );
              //}
            }
          }
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
              && AoservDaemonConfiguration.isManagerEnabled(XinetdManager.class)
              && xinetdManager == null
      ) {
        System.out.print("Starting XinetdManager: ");
        // Must be a supported operating system
        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          AoservConnector conn = AoservDaemon.getConnector();
          xinetdManager = new XinetdManager();
          conn.getScm().getCvsRepository().addTableListener(xinetdManager, 0);
          conn.getNet().getBind().addTableListener(xinetdManager, 0);
          conn.getNet().getTcpRedirect().addTableListener(xinetdManager, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild xinetd Configuration";
  }
}
