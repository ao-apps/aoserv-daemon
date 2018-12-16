/*
 * Copyright 2007-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email.jilter;

import com.aoindustries.aoserv.client.AOServConnector;
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
import com.aoindustries.aoserv.client.validator.AccountingCode;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.jilter.config.EmailLimit;
import com.aoindustries.aoserv.jilter.config.JilterConfiguration;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.DomainName;
import com.aoindustries.net.InetAddress;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Writes new configuration files for the JilterConfiguration when AOServ tables have been updated.
 *
 * @see  JilterConfiguration
 *
 * @author  AO Industries, Inc.
 */
public class JilterConfigurationWriter extends BuilderThread {

	private static JilterConfigurationWriter configurationWriter;

	public static void start() throws IOException, SQLException {
		Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class)
				&& configurationWriter == null
			) {
				System.out.print("Starting JilterConfigurationWriter: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					configurationWriter = new JilterConfigurationWriter();
					conn.getLinux().getAoServers().addTableListener(configurationWriter, 0);
					conn.getNet().getNetBinds().addTableListener(configurationWriter, 0);
					conn.getNet().getNetDevices().addTableListener(configurationWriter, 0);
					conn.getNet().getIpAddresses().addTableListener(configurationWriter, 0);
					conn.getEmail().getEmailDomains().addTableListener(configurationWriter, 0);
					conn.getEmail().getEmailAddresses().addTableListener(configurationWriter, 0);
					conn.getEmail().getEmailSmtpRelays().addTableListener(configurationWriter, 0);
					conn.getBilling().getPackages().addTableListener(configurationWriter, 0);
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
		AppProtocol protocol = AOServDaemon.getConnector().getNet().getProtocols().get(AppProtocol.MILTER);
		if(protocol==null) throw new SQLException("Protocol not found: " + AppProtocol.MILTER);
		List<Bind> milterBinds = AOServDaemon.getThisAOServer().getServer().getNetBinds(protocol);
		if(milterBinds.size()>1) throw new SQLException("More than one milter found in net_binds, refusing to configure jilter");
		return milterBinds.isEmpty() ? null : milterBinds.get(0);
	}

	@Override
	protected boolean doRebuild() {
		try {
			Server aoServer = AOServDaemon.getThisAOServer();
			Host server = aoServer.getServer();

			// Look for the configured net bind for the jilter
			Bind jilterNetBind = getJilterNetBind();
			// Only configure when the net bind has been found
			if(jilterNetBind != null) {
				// Install package if needed
				PackageManager.installPackage(PackageManager.PackageName.AOSERV_JILTER);

				// restrict_outbound_email
				boolean restrict_outbound_email = aoServer.getRestrictOutboundEmail();

				// domainPackages and domainAddresses
				Map<String,String> domainPackages = new HashMap<>();
				Map<String,Set<String>> domainAddresses = new HashMap<>();
				for(Domain ed : aoServer.getEmailDomains()) {
					DomainName domain = ed.getDomain();
					// domainPackages
					domainPackages.put(domain.toString(), ed.getPackage().getName().toString());
					// domainAddresses
					List<Address> eas = ed.getEmailAddresses();
					Set<String> addresses = new HashSet<>(eas.size()*4/3+1);
					for(Address ea : eas) addresses.add(ea.getAddress());
					domainAddresses.put(domain.toString(), addresses);
				}

				// ips
				List<IpAddress> ias = server.getIPAddresses();
				Set<String> ips = new HashSet<>(ias.size()*4/3+1);
				for(IpAddress ia : ias) {
					InetAddress ip = ia.getInetAddress();
					if(!ip.isUnspecified()) {
						ips.add(ip.toString());
					}
				}

				// email_smtp_relays
				Set<String> denies = new HashSet<>();
				Set<String> denySpams = new HashSet<>();
				Set<String> allowRelays = new HashSet<>();
				for(SmtpRelay esr : aoServer.getEmailSmtpRelays()) {
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
							LogFactory.getLogger(JilterConfigurationWriter.class).log(Level.WARNING, null, new SQLException("Unexpected value for type: "+type));
					}
				}

				// Builds email limits only for the packages referenced in domainPackages
				int noGrowSize = domainPackages.size() * 4 / 3 + 1;
				Map<String,EmailLimit> emailInLimits = new HashMap<>(noGrowSize);
				Map<String,EmailLimit> emailOutLimits = new HashMap<>(noGrowSize);
				Map<String,EmailLimit> emailRelayLimits = new HashMap<>(noGrowSize);
				for(String packageName : domainPackages.values()) {
					Package pk = AOServDaemon.getConnector().getBilling().getPackages().get(AccountingCode.valueOf(packageName));
					if(pk==null) throw new SQLException("Unable to find Package: "+packageName);
					int emailInBurst = pk.getEmailInBurst();
					float emailInRate = pk.getEmailInRate();
					if(emailInBurst!=-1 && !Float.isNaN(emailInRate)) emailInLimits.put(packageName, new EmailLimit(emailInBurst, emailInRate));
					int emailOutBurst = pk.getEmailOutBurst();
					float emailOutRate = pk.getEmailOutRate();
					if(emailOutBurst!=-1 && !Float.isNaN(emailOutRate)) emailOutLimits.put(packageName, new EmailLimit(emailOutBurst, emailOutRate));
					int emailRelayBurst = pk.getEmailRelayBurst();
					float emailRelayRate = pk.getEmailRelayRate();
					if(emailRelayBurst!=-1 && !Float.isNaN(emailRelayRate)) emailRelayLimits.put(packageName, new EmailLimit(emailRelayBurst, emailRelayRate));
				}
				synchronized(rebuildLock) {
					JilterConfiguration jilterConfiguration = new JilterConfiguration(
						jilterNetBind.getIpAddress().getInetAddress().toString(),
						jilterNetBind.getPort().getPort(),
						restrict_outbound_email,
						AOServDaemonConfiguration.getMonitorSmtpServer(),
						AOServDaemonConfiguration.getMonitorEmailSummaryFrom(),
						AOServDaemonConfiguration.getMonitorEmailSummaryTo(),
						AOServDaemonConfiguration.getMonitorEmailFullFrom(),
						AOServDaemonConfiguration.getMonitorEmailFullTo(),
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
					jilterConfiguration.saveIfChanged("This file is automatically generated by "+JilterConfigurationWriter.class.getName());
					// Adjust permissions
					int osv = aoServer.getServer().getOperatingSystemVersion().getPkey();
					if(osv == OperatingSystemVersion.CENTOS_7_X86_64) {
						int aoservJilterGid;
						{
							GroupServer aoservJilterLsg = aoServer.getLinuxServerGroup(Group.AOSERV_JILTER);
							if(aoservJilterLsg == null) throw new SQLException("Unable to find GroupServer: " + Group.AOSERV_JILTER);
							aoservJilterGid = aoservJilterLsg.getGid().getId();
						}
						UnixFile propsUF = new UnixFile(JilterConfiguration.PROPS_FILE);
						Stat propsStat = propsUF.getStat();
						if(
							propsStat.getUid() != UnixFile.ROOT_UID
							|| propsStat.getGid() != aoservJilterGid
						) {
							propsUF.chown(UnixFile.ROOT_UID, aoservJilterGid);
						}
						if(propsStat.getMode() != 0640) propsUF.setMode(0640);
					}
				}
			} else {
				// Remove the package
				if(AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
					PackageManager.removePackage(PackageManager.PackageName.AOSERV_JILTER);

					// Remove any left-over config file and directory
					File rpmSaveFile = new File(JilterConfiguration.PROPS_FILE + ".rpmsave");
					if(rpmSaveFile.exists()) FileUtils.delete(rpmSaveFile);
					File configDir = rpmSaveFile.getParentFile();
					if(configDir.exists()) FileUtils.delete(configDir);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(JilterConfigurationWriter.class).log(Level.SEVERE, null, T);
			return false;
		}
	}
}
