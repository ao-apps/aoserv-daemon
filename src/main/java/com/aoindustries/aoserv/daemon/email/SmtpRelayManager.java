/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.Strings;
import com.aoapps.lang.io.IoUtils;
import com.aoapps.lang.io.NullOutputStream;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.SmtpRelay;
import com.aoindustries.aoserv.client.email.SmtpRelayType;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls access to the mail server, supports auto-expiring SMTP access.
 *
 * @author  AO Industries, Inc.
 */
// TODO: ROCKY_9_X86_64
public final class SmtpRelayManager extends BuilderThread implements Runnable {

  private static final Logger logger = Logger.getLogger(SmtpRelayManager.class.getName());

  private static final int REFRESH_PERIOD = 15 * 60 * 1000;

  /**
   * sendmail configs.
   */
  private static final String ACCESS_FILENAME = "/etc/mail/access";

  private static final PosixFile ACCESS = new PosixFile(ACCESS_FILENAME);
  private static final PosixFile ACCESS_DB = new PosixFile(ACCESS_FILENAME + ".db");
  private static final PosixFile NEW_ACCESS_DB = new PosixFile("/etc/mail/newaccess.db");

  ///**
  // * qmail configs.
  // */
  //private static final String
  //  qmailFile = "/etc/tcp.smtp",
  //  newQmailFile = "/etc/tcp.smtp.new";

  private static SmtpRelayManager smtpRelayManager;

  private static final Object rebuildLock = new Object();

  @Override
  @SuppressWarnings({"UseSpecificCatch", "BroadCatchBlock", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      AoservConnector connector = AoservDaemon.getConnector();
      Server thisServer = AoservDaemon.getThisServer();
      Host thisHost = thisServer.getHost();
      OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
      int osvId = osv.getPkey();
      if (
          osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
              && osvId != OperatingSystemVersion.CENTOS_7_X86_64
      ) {
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
      }

      SmtpRelayType allowRelay = connector.getEmail().getSmtpRelayType().get(SmtpRelayType.ALLOW_RELAY);
      //boolean isQmail=server.isQmail();

      // The IP addresses that have been used
      Set<String> usedHosts = new HashSet<>();

      synchronized (rebuildLock) {
        // Only build when the sendmail package is installed
        if (PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL) != null) {
          Set<PosixFile> restorecon = new LinkedHashSet<>();
          try {
            PosixFile access;
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try (ChainWriter out = new ChainWriter(bout)) {
              /*if (isQmail) {
                access=new PosixFile(qmailFile);
                newFile=new PosixFile(newQmailFile);
              } else {*/
              // Rebuild the new config file
              out.print(
                  "# Check the /usr/share/doc/sendmail/README.cf file for a description\n"
                      + "# of the format of this file. (search for access_db in that file)\n"
                      + "# The /usr/share/doc/sendmail/README.cf is part of the sendmail-doc\n"
                      + "# package.\n"
                      + "#\n"
                      + "# If you want to use AuthInfo with \"M:PLAIN LOGIN\", make sure to have the \n"
                      + "# cyrus-sasl-plain package installed.\n"
                      + "#\n"
                      + "# By default we allow relaying from localhost...\n"
                      + "# Connect:localhost.localdomain\t\tRELAY\n"
                      + "# Connect:localhost\t\t\tRELAY\n"
                      + "# Connect:127.0.0.1\t\t\tRELAY\n"
                      + "\n"
                      + "# Generated by ").print(SmtpRelayManager.class.getName()).print('\n');
              access = new PosixFile(ACCESS_FILENAME);
              //}

              // Allow all of the local IP addresses
              for (Device nd : thisHost.getNetDevices()) {
                for (IpAddress ia : nd.getIpAddresses()) {
                  String ip = ia.getInetAddress().toString();
                  if (!usedHosts.contains(ip)) {
                    writeAccessLine(out, ip, allowRelay/*, isQmail*/);
                    usedHosts.add(ip);
                  }
                }
              }

              // Deny first
              List<SmtpRelay> relays = thisServer.getEmailSmtpRelays();
              for (SmtpRelay ssr : relays) {
                if (!ssr.isDisabled()) {
                  SmtpRelayType esrt = ssr.getType();
                  String type = esrt.getName();
                  if (
                      type.equals(SmtpRelayType.DENY)
                          || type.equals(SmtpRelayType.DENY_SPAM)
                  ) {
                    Timestamp expiration = ssr.getExpiration();
                    if (expiration == null || expiration.getTime() > System.currentTimeMillis()) {
                      String host = ssr.getHost().toString();
                      if (!usedHosts.contains(host)) {
                        writeAccessLine(out, host, esrt/*, isQmail*/);
                        usedHosts.add(host);
                      }
                    }
                  }
                }
              }

              // Allow last
              for (SmtpRelay ssr : relays) {
                if (!ssr.isDisabled()) {
                  SmtpRelayType esrt = ssr.getType();
                  String type = esrt.getName();
                  if (
                      type.equals(SmtpRelayType.ALLOW)
                          || type.equals(SmtpRelayType.ALLOW_RELAY)
                  ) {
                    Timestamp expiration = ssr.getExpiration();
                    if (expiration == null || expiration.getTime() > System.currentTimeMillis()) {
                      String host = ssr.getHost().toString();
                      if (!usedHosts.contains(host)) {
                        writeAccessLine(out, host, esrt/*, isQmail*/);
                        usedHosts.add(host);
                      }
                    }
                  }
                }
              }
            }
            // Write new file only when needed
            byte[] accessBytes = bout.toByteArray();
            boolean makemap;
            if (
                DaemonFileUtils.atomicWrite(
                    access,
                    accessBytes,
                    0644,
                    PosixFile.ROOT_UID,
                    PosixFile.ROOT_GID,
                    null,
                    restorecon
                )
            ) {
              makemap = true;
            } else {
              Stat accessDbStat = ACCESS_DB.getStat();
              if (!accessDbStat.exists()) {
                // access.db missing, make map
                makemap = true;
              } else {
                // make map when access newer than access.db
                makemap = ACCESS.getStat().getModifyTime() > accessDbStat.getModifyTime();
              }
            }
            if (makemap) {
              // SELinux before makemap
              DaemonFileUtils.restorecon(restorecon);
              restorecon.clear();
              makeAccessMap(accessBytes, restorecon);
            }
          } finally {
            DaemonFileUtils.restorecon(restorecon);
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

  private static void writeAccessLine(ChainWriter out, String host, SmtpRelayType type/*, boolean isQmail*/) {
    /*if (isQmail) {
      out.print(host).print(':').print(Strings.replace(type.getQmailConfig(), "%h", host)).print('\n');
    } else {*/
    out.print("Connect:").print(host).print('\t').print(Strings.replace(type.getSendmailConfig(), "%h", host)).print('\n');
    /*}*/
  }

  ///**
  // * Gets the number of dots in the String, returning a maximum of 3 even if there are more
  // */
  //private static int getDotCount(String s) {
  //  int count = 0;
  //  int len = s.length();
  //  for (int c = 0; c < len; c++) {
  //    if (s.charAt(c) == '.') {
  //      count++;
  //      if (count >= 3) {
  //        break;
  //      }
  //    }
  //  }
  //  return count;
  //}

  //private static final String[] qmailctlCdbCommand={
  //  "/var/qmail/bin/qmailctl",
  //  "cdb"
  //};

  private static void makeAccessMap(byte[] accessBytes, Set<PosixFile> restorecon) throws IOException, SQLException {
    /*if (AoservDaemon.getThisAOServer().isQmail()) {
      command = qmailctlCdbCommand;
    } else {*/
    OperatingSystemVersion osv = AoservDaemon.getThisServer().getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
      // Make sure /usr/sbin/makemap is installed as required by make_sendmail_access_map
      // access file only built when sendmail installed now: PackageManager.installPackage(PackageManager.PackageName.SENDMAIL);
      AoservDaemon.execRun(
          stdin -> stdin.write(accessBytes),
          stdout -> IoUtils.copy(stdout, NullOutputStream.getInstance()), // Do nothing with the output
          "/usr/sbin/makemap",
          "hash",
          NEW_ACCESS_DB.getPath()
      );
      NEW_ACCESS_DB.renameTo(ACCESS_DB);
      restorecon.add(ACCESS_DB);
    } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
      AoservDaemon.exec("/etc/mail/make", "access.db");
      restorecon.add(ACCESS_DB);
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
    //}
  }

  private SmtpRelayManager() {
    // Do nothing
  }

  @Override
  @SuppressWarnings({"SleepWhileInLoop", "UseSpecificCatch", "BroadCatchBlock", "TooBroadCatch"})
  public void run() {
    long lastTime = Long.MIN_VALUE;
    while (!Thread.currentThread().isInterrupted()) {
      try {
        try {
          Thread.sleep(REFRESH_PERIOD);
        } catch (InterruptedException err) {
          logger.log(Level.WARNING, null, err);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
          break;
        }
        long time = System.currentTimeMillis();
        boolean needRebuild = false;
        for (SmtpRelay relay : AoservDaemon.getThisServer().getEmailSmtpRelays()) {
          Timestamp expiration = relay.getExpiration();
          if (
              expiration != null
                  && expiration.getTime() >= lastTime
                  && expiration.getTime() < time
          ) {
            needRebuild = true;
            break;
          }
        }
        lastTime = time;
        if (needRebuild) {
          doRebuild();
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        logger.log(Level.SEVERE, null, t);
        try {
          Thread.sleep(REFRESH_PERIOD);
        } catch (InterruptedException err) {
          logger.log(Level.WARNING, null, err);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
        }
      }
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
              && AoservDaemonConfiguration.isManagerEnabled(SmtpRelayManager.class)
              && smtpRelayManager == null
      ) {
        System.out.print("Starting SmtpRelayManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          smtpRelayManager = new SmtpRelayManager();
          conn.getEmail().getSmtpRelay().addTableListener(smtpRelayManager, 0);
          conn.getNet().getIpAddress().addTableListener(smtpRelayManager, 0);
          conn.getNet().getDevice().addTableListener(smtpRelayManager, 0);
          PackageManager.addPackageListener(smtpRelayManager);
          new Thread(smtpRelayManager, "SmtpRelayManager").start();
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild SMTP Relays";
  }

  @Override
  public long getProcessTimerMaximumTime() {
    return 30L * 60 * 1000;
  }
}
