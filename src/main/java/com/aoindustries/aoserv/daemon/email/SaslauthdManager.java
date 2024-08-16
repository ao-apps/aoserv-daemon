/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the saslauthd service.
 *
 * @author  AO Industries, Inc.
 */
// TODO: ROCKY_9_X86_64
public final class SaslauthdManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(SaslauthdManager.class.getName());

  private static SaslauthdManager saslauthdManager;

  private static final PosixFile saslauthConfig = new PosixFile("/etc/sysconfig/saslauthd");

  private static final String SYSTEMCTL = "/usr/bin/systemctl";

  private static final String SERVICE = "saslauthd.service";

  private SaslauthdManager() {
    // Do nothing
  }

  private static final Object rebuildLock = new Object();

  @Override
  protected boolean doRebuild() {
    try {
      Server thisServer = AoservDaemon.getThisServer();
      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (osvId != OperatingSystemVersion.CENTOS_7_X86_64) {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      synchronized (rebuildLock) {
        // Do nothing when package not installed
        if (PackageManager.getInstalledPackage(PackageManager.PackageName.CYRUS_SASL) != null) {
          Set<PosixFile> restorecon = new LinkedHashSet<>();
          try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try (ChainWriter out = new ChainWriter(bout)) {
              out.print("# Generated by ").print(SaslauthdManager.class.getName()).print("\n"
                  + "\n"
                  + "# Directory in which to place saslauthd's listening socket, pid file, and so\n"
                  + "# on.  This directory must already exist.\n"
                  + "SOCKETDIR=/run/saslauthd\n"
                  + "\n"
                  + "# Mechanism to use when checking passwords.  Run \"saslauthd -v\" to get a list\n"
                  + "# of which mechanism your installation was compiled with the ablity to use.\n"
                  + "MECH=pam\n"
                  + "\n"
                  + "# Additional flags to pass to saslauthd on the command line.  See saslauthd(8)\n"
                  + "# for the list of accepted flags.\n"
                  + "\n"
                  + "# -r is added to support \"username@domain\" format usernames while avoiding\n"
                  + "#    possible domain mismatch with \"username@default\" used by Cyrus IMAPD for\n"
                  + "#    no-domain usernames.\n"
                  + "FLAGS=-r\n");
            }

            // Only write to disk if changed
            boolean changed = DaemonFileUtils.atomicWrite(
                saslauthConfig,
                bout.toByteArray(),
                0644,
                PosixFile.ROOT_UID,
                PosixFile.ROOT_GID,
                null,
                restorecon
            );

            // SELinux before next steps
            DaemonFileUtils.restorecon(restorecon);
            restorecon.clear();

            if (
                SendmailCFManager.isSendmailEnabled()
                    || ImapManager.isCyrusImapdEnabled()
            ) {
              // Enable when sendmail or cyrus-imapd expected to be running
              logger.fine("Enabling " + SERVICE);
              AoservDaemon.exec(SYSTEMCTL, "enable", SERVICE);
              // Reload/start when changed
              if (changed) {
                logger.fine("Reloading or restarting " + SERVICE);
                AoservDaemon.exec(SYSTEMCTL, "reload-or-restart", SERVICE);
              } else {
                logger.fine("Starting " + SERVICE);
                AoservDaemon.exec(SYSTEMCTL, "start", SERVICE);
              }
            } else {
              // Disable when sendmail not expected to be running
              logger.fine("Stopping " + SERVICE);
              AoservDaemon.exec(SYSTEMCTL, "stop", SERVICE);
              logger.fine("Disabling " + SERVICE);
              AoservDaemon.exec(SYSTEMCTL, "disable", SERVICE);
            }
          } finally {
            DaemonFileUtils.restorecon(restorecon);
          }
        } else {
          if (logger.isLoggable(Level.FINE)) {
            logger.fine(
                PackageManager.PackageName.CYRUS_SASL
                    + " package not installed, skipping configuration of saslauthd."
            );
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
          osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
              && osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
              // Check config after OS check so config entry not needed
              && AoservDaemonConfiguration.isManagerEnabled(SaslauthdManager.class)
              && saslauthdManager == null
      ) {
        System.out.print("Starting SaslauthdManager: ");
        // Must be a supported operating system
        if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
          AoservConnector conn = AoservDaemon.getConnector();
          saslauthdManager = new SaslauthdManager();
          conn.getLinux().getUserServer().addTableListener(saslauthdManager, 0);
          conn.getNet().getBind().addTableListener(saslauthdManager, 0);
          PackageManager.addPackageListener(saslauthdManager);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild saslauthd";
  }
}
