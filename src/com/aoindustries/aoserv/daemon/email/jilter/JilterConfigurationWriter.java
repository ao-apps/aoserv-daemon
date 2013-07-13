/*
 * Copyright 2007-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email.jilter;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailAddress;
import com.aoindustries.aoserv.client.EmailDomain;
import com.aoindustries.aoserv.client.EmailSmtpRelay;
import com.aoindustries.aoserv.client.EmailSmtpRelayType;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Package;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.jilter.config.EmailLimit;
import com.aoindustries.aoserv.jilter.config.JilterConfiguration;
import com.aoindustries.io.FileUtils;
import com.aoindustries.sql.NoRowException;
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

	private static final String PACKAGE_RPM_NAME = "aoserv-jilter";

	private static JilterConfigurationWriter configurationWriter;

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class)
                && configurationWriter==null
            ) {
                System.out.print("Starting JilterConfigurationWriter: ");
                AOServConnector connector=AOServDaemon.getConnector();
                configurationWriter=new JilterConfigurationWriter();
                connector.getAoServers().addTableListener(configurationWriter, 0);
                connector.getNetBinds().addTableListener(configurationWriter, 0);
                connector.getNetDevices().addTableListener(configurationWriter, 0);
                connector.getIpAddresses().addTableListener(configurationWriter, 0);
                connector.getEmailDomains().addTableListener(configurationWriter, 0);
                connector.getEmailAddresses().addTableListener(configurationWriter, 0);
                connector.getEmailSmtpRelays().addTableListener(configurationWriter, 0);
                connector.getPackages().addTableListener(configurationWriter, 0);
                System.out.println("Done");
            }
        }
    }

	@Override
    public String getProcessTimerDescription() {
        return "JilterConfigurationWriter";
    }
    private static final Object rebuildLock=new Object();

	/**
	 * Finds the NetBind that the jilter should listen on.
	 * It looks for anything with app protocol='milter'.
	 * There must only be one or zero found.
	 * 
	 * @return  the NetBind or <code>null</code> if none found and jilter disabled.
	 */
	public static NetBind getJilterNetBind() throws IOException, SQLException {
		Protocol protocol = AOServDaemon.getConnector().getProtocols().get(Protocol.MILTER);
		if(protocol==null) throw new NoRowException("Protocol not found: " + Protocol.MILTER);
		List<NetBind> milterBinds = AOServDaemon.getThisAOServer().getServer().getNetBinds(protocol);
		if(milterBinds.size()>1) throw new SQLException("More than one milter found in net_binds, refusing to configure jilter");
		return milterBinds.isEmpty() ? null : milterBinds.get(0);
	}

	@Override
    protected boolean doRebuild() {
        try {
            AOServer aoServer = AOServDaemon.getThisAOServer();
            Server server = aoServer.getServer();

			// Look for the configured net bind for the jilter
			NetBind jilterNetBind = getJilterNetBind();
			// Only configure when the net bind has been found
			if(jilterNetBind!=null) {
				// Install package if needed
				PackageManager.installPackage(PACKAGE_RPM_NAME);

				// restrict_outbound_email
				boolean restrict_outbound_email = aoServer.getRestrictOutboundEmail();

				// domainPackages and domainAddresses
				Map<String,String> domainPackages = new HashMap<>();
				Map<String,Set<String>> domainAddresses = new HashMap<>();
				for(EmailDomain ed : aoServer.getEmailDomains()) {
					DomainName domain = ed.getDomain();
					// domainPackages
					domainPackages.put(domain.toString(), ed.getPackage().getName());
					// domainAddresses
					List<EmailAddress> eas = ed.getEmailAddresses();
					Set<String> addresses = new HashSet<>(eas.size()*4/3+1);
					for(EmailAddress ea : eas) addresses.add(ea.getAddress());
					domainAddresses.put(domain.toString(), addresses);
				}

				// ips
				List<IPAddress> ias = server.getIPAddresses();
				Set<String> ips = new HashSet<>(ias.size()*4/3+1);
				for(IPAddress ia : ias) {
					InetAddress ip = ia.getInetAddress();
					if(!ip.isUnspecified()) {
						ips.add(ip.toString());
					}
				}

				// email_smtp_relays
				Set<String> denies = new HashSet<>();
				Set<String> denySpams = new HashSet<>();
				Set<String> allowRelays = new HashSet<>();
				for(EmailSmtpRelay esr : aoServer.getEmailSmtpRelays()) {
					String host = esr.getHost().toString();
					String type = esr.getType().getName();
					switch (type) {
						case EmailSmtpRelayType.DENY:
							denies.add(host);
							break;
						case EmailSmtpRelayType.DENY_SPAM:
							denySpams.add(host);
							break;
						case EmailSmtpRelayType.ALLOW_RELAY:
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
					Package pk = AOServDaemon.getConnector().getPackages().get(packageName);
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
						jilterNetBind.getIPAddress().getInetAddress().toString(),
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
				}
			} else {
				// Remove the package
				PackageManager.removePackage(PACKAGE_RPM_NAME);

				// Remove any left-over config file and directory
				File rpmSaveFile = new File(JilterConfiguration.PROPS_FILE + ".rpmsave");
				if(rpmSaveFile.exists()) FileUtils.delete(rpmSaveFile);
				File configDir = rpmSaveFile.getParentFile();
				if(configDir.exists()) FileUtils.delete(configDir);
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
