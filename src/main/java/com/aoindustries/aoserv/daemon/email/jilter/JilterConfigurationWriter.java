/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.email.jilter;

import com.aoapps.collections.AoCollections;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.net.DomainName;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.account.Account;
import com.aoindustries.aoserv.client.billing.Package;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.Address;
import com.aoindustries.aoserv.client.email.Domain;
import com.aoindustries.aoserv.client.email.SmtpRelay;
import com.aoindustries.aoserv.client.email.SmtpRelayType;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.jilter.config.EmailLimit;
import com.aoindustries.aoserv.jilter.config.JilterConfiguration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes new configuration files for the JilterConfiguration when AOServ tables have been updated.
 *
 * @see  JilterConfiguration
 *
 * @author  AO Industries, Inc.
 */
// TODO: ROCKY_9_X86_64
public class JilterConfigurationWriter extends BuilderThread {

  private static final Logger logger = Logger.getLogger(JilterConfigurationWriter.class.getName());

  private static JilterConfigurationWriter configurationWriter;

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
              && AoservDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class)
              && configurationWriter == null
      ) {
        System.out.print("Starting JilterConfigurationWriter: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
        ) {
          AoservConnector conn = AoservDaemon.getConnector();
          configurationWriter = new JilterConfigurationWriter();
          conn.getLinux().getServer().addTableListener(configurationWriter, 0);
          conn.getNet().getBind().addTableListener(configurationWriter, 0);
          conn.getNet().getDevice().addTableListener(configurationWriter, 0);
          conn.getNet().getIpAddress().addTableListener(configurationWriter, 0);
          conn.getEmail().getDomain().addTableListener(configurationWriter, 0);
          conn.getEmail().getAddress().addTableListener(configurationWriter, 0);
          conn.getEmail().getSmtpRelay().addTableListener(configurationWriter, 0);
          conn.getBilling().getPackage().addTableListener(configurationWriter, 0);
          System.out.println("Done");
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
        }
      }
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "JilterConfigurationWriter";
  }

  private static final Object rebuildLock = new Object();

  /**
   * Finds the Bind that the jilter should listen on.
   * It looks for anything with app protocol='milter'.
   * There must only be one or zero found.
   *
   * @return  the Bind or <code>null</code> if none found and jilter disabled.
   */
  public static Bind getJilterNetBind() throws IOException, SQLException {
    AppProtocol protocol = AoservDaemon.getConnector().getNet().getAppProtocol().get(AppProtocol.MILTER);
    if (protocol == null) {
      throw new SQLException("AppProtocol not found: " + AppProtocol.MILTER);
    }
    List<Bind> milterBinds = AoservDaemon.getThisServer().getHost().getNetBinds(protocol);
    if (milterBinds.size() > 1) {
      throw new SQLException("More than one milter found in net_binds, refusing to configure jilter");
    }
    return milterBinds.isEmpty() ? null : milterBinds.get(0);
  }

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      Server thisServer = AoservDaemon.getThisServer();
      Host thisHost = thisServer.getHost();

      // Look for the configured net bind for the jilter
      Bind jilterNetBind = getJilterNetBind();
      // Only configure when the net bind has been found
      if (jilterNetBind != null) {
        // Install package if needed
        PackageManager.installPackage(PackageManager.PackageName.AOSERV_JILTER);

        // restrict_outbound_email
        boolean restrictOutboundEmail = thisServer.getRestrictOutboundEmail();

        // domainPackages and domainAddresses
        Map<String, String> domainPackages = new HashMap<>();
        Map<String, Set<String>> domainAddresses = new HashMap<>();
        for (Domain ed : thisServer.getEmailDomains()) {
          DomainName domain = ed.getDomain();
          // domainPackages
          domainPackages.put(domain.toString(), ed.getPackage().getName().toString());
          // domainAddresses
          List<Address> eas = ed.getEmailAddresses();
          Set<String> addresses = AoCollections.newHashSet(eas.size());
          for (Address ea : eas) {
            addresses.add(ea.getAddress());
          }
          domainAddresses.put(domain.toString(), addresses);
        }

        // ips
        List<IpAddress> ias = thisHost.getIpAddresses();
        Set<String> ips = AoCollections.newHashSet(ias.size());
        for (IpAddress ia : ias) {
          InetAddress ip = ia.getInetAddress();
          if (!ip.isUnspecified()) {
            ips.add(ip.toString());
          }
        }

        // email_smtp_relays
        Set<String> denies = new HashSet<>();
        Set<String> denySpams = new HashSet<>();
        Set<String> allowRelays = new HashSet<>();
        for (SmtpRelay esr : thisServer.getEmailSmtpRelays()) {
          String host = esr.getHost().toString();
          String type = esr.getType().getName();
          switch (type) {
            case SmtpRelayType.DENY:
              denies.add(host);
              break;
            case SmtpRelayType.DENY_SPAM:
              denySpams.add(host);
              break;
            case SmtpRelayType.ALLOW_RELAY:
              allowRelays.add(host);
              break;
            default:
              logger.log(Level.WARNING, null, new SQLException("Unexpected value for type: " + type));
          }
        }

        // Builds email limits only for the packages referenced in domainPackages
        int size = domainPackages.size();
        Map<String, EmailLimit> emailInLimits = AoCollections.newHashMap(size);
        Map<String, EmailLimit> emailOutLimits = AoCollections.newHashMap(size);
        Map<String, EmailLimit> emailRelayLimits = AoCollections.newHashMap(size);
        for (String packageName : domainPackages.values()) {
          Package pk = AoservDaemon.getConnector().getBilling().getPackage().get(Account.Name.valueOf(packageName));
          if (pk == null) {
            throw new SQLException("Unable to find Package: " + packageName);
          }
          int emailInBurst = pk.getEmailInBurst();
          float emailInRate = pk.getEmailInRate();
          if (emailInBurst != -1 && !Float.isNaN(emailInRate)) {
            emailInLimits.put(packageName, new EmailLimit(emailInBurst, emailInRate));
          }
          int emailOutBurst = pk.getEmailOutBurst();
          float emailOutRate = pk.getEmailOutRate();
          if (emailOutBurst != -1 && !Float.isNaN(emailOutRate)) {
            emailOutLimits.put(packageName, new EmailLimit(emailOutBurst, emailOutRate));
          }
          int emailRelayBurst = pk.getEmailRelayBurst();
          float emailRelayRate = pk.getEmailRelayRate();
          if (emailRelayBurst != -1 && !Float.isNaN(emailRelayRate)) {
            emailRelayLimits.put(packageName, new EmailLimit(emailRelayBurst, emailRelayRate));
          }
        }
        synchronized (rebuildLock) {
          JilterConfiguration jilterConfiguration = new JilterConfiguration(
              jilterNetBind.getIpAddress().getInetAddress().toString(),
              jilterNetBind.getPort().getPort(),
              restrictOutboundEmail,
              AoservDaemonConfiguration.getMonitorSmtpServer(),
              AoservDaemonConfiguration.getMonitorEmailSummaryFrom(),
              AoservDaemonConfiguration.getMonitorEmailSummaryTo(),
              AoservDaemonConfiguration.getMonitorEmailFullFrom(),
              AoservDaemonConfiguration.getMonitorEmailFullTo(),
              domainPackages,
              domainAddresses,
              ips,
              denies,
              denySpams,
              allowRelays,
              emailInLimits,
              emailOutLimits,
              emailRelayLimits
          );
          jilterConfiguration.saveIfChanged("This file is automatically generated by " + JilterConfigurationWriter.class.getName());
          // Adjust permissions
          int osv = thisServer.getHost().getOperatingSystemVersion().getPkey();
          if (osv == OperatingSystemVersion.CENTOS_7_X86_64) {
            int aoservJilterGid;
              {
                GroupServer aoservJilterLsg = thisServer.getLinuxServerGroup(Group.AOSERV_JILTER);
                if (aoservJilterLsg == null) {
                  throw new SQLException("Unable to find GroupServer: " + Group.AOSERV_JILTER);
                }
                aoservJilterGid = aoservJilterLsg.getGid().getId();
              }
            PosixFile propsPosixFile = new PosixFile(JilterConfiguration.PROPS_FILE);
            Stat propsStat = propsPosixFile.getStat();
            if (
                propsStat.getUid() != PosixFile.ROOT_UID
                    || propsStat.getGid() != aoservJilterGid
            ) {
              propsPosixFile.chown(PosixFile.ROOT_UID, aoservJilterGid);
            }
            if (propsStat.getMode() != 0640) {
              propsPosixFile.setMode(0640);
            }
          }
        }
      } else {
        // Remove the package
        if (AoservDaemonConfiguration.isPackageManagerUninstallEnabled()) {
          PackageManager.removePackage(PackageManager.PackageName.AOSERV_JILTER);

          // Remove any left-over config file and directory
          File rpmSaveFile = new File(JilterConfiguration.PROPS_FILE + ".rpmsave");
          if (rpmSaveFile.exists()) {
            Files.delete(rpmSaveFile.toPath());
          }
          File configDir = rpmSaveFile.getParentFile();
          if (configDir.exists()) {
            Files.delete(configDir.toPath());
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
}
