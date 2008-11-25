package com.aoindustries.aoserv.daemon.email.jilter;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailAddress;
import com.aoindustries.aoserv.client.EmailDomain;
import com.aoindustries.aoserv.client.EmailSmtpRelay;
import com.aoindustries.aoserv.client.EmailSmtpRelayType;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.Package;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.jilter.config.EmailLimit;
import com.aoindustries.aoserv.jilter.config.JilterConfiguration;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if(AOServDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class) && configurationWriter==null) {
            synchronized(System.out) {
                if(configurationWriter==null) {
                    System.out.print("Starting JilterConfigurationWriter: ");
                    AOServConnector connector=AOServDaemon.getConnector();
                    configurationWriter=new JilterConfigurationWriter();
                    connector.aoServers.addTableListener(configurationWriter, 0);
                    connector.netDevices.addTableListener(configurationWriter, 0);
                    connector.ipAddresses.addTableListener(configurationWriter, 0);
                    connector.emailDomains.addTableListener(configurationWriter, 0);
                    connector.emailAddresses.addTableListener(configurationWriter, 0);
                    connector.emailSmtpRelays.addTableListener(configurationWriter, 0);
                    connector.packages.addTableListener(configurationWriter, 0);
                    System.out.println("Done");
                }
            }
        }
    }

    public String getProcessTimerDescription() {
        return "JilterConfigurationWriter";
    }
    private static final Object rebuildLock=new Object();

    protected void doRebuild() throws IOException, SQLException {
        AOServer aoServer = AOServDaemon.getThisAOServer();
        Server server = aoServer.getServer();
        
        // primaryIP
        String primaryIP = aoServer.getPrimaryIPAddress().getIPAddress();

        // restrict_outbound_email
        boolean restrict_outbound_email = aoServer.getRestrictOutboundEmail();

        // domainPackages and domainAddresses
        Map<String,String> domainPackages = new HashMap<String,String>();
        Map<String,Set<String>> domainAddresses = new HashMap<String,Set<String>>();
        for(EmailDomain ed : aoServer.getEmailDomains()) {
            String domain = ed.getDomain();
            // domainPackages
            domainPackages.put(domain, ed.getPackage().getName());
            // domainAddresses
            List<EmailAddress> eas = ed.getEmailAddresses();
            Set<String> addresses = new HashSet<String>(eas.size()*4/3+1);
            for(EmailAddress ea : eas) addresses.add(ea.getAddress());
            domainAddresses.put(domain, addresses);
        }

        // ips
        List<IPAddress> ias = server.getIPAddresses();
        Set<String> ips = new HashSet<String>(ias.size()*4/3+1);
        for(IPAddress ia : ias) {
            if(!ia.isWildcard()) {
                ips.add(ia.getIPAddress());
            }
        }

        // email_smtp_relays
        Set<String> denies = new HashSet<String>();
        Set<String> denySpams = new HashSet<String>();
        Set<String> allowRelays = new HashSet<String>();
        for(EmailSmtpRelay esr : aoServer.getEmailSmtpRelays()) {
            String host = esr.getHost();
            String type = esr.getType().getName();
            if(EmailSmtpRelayType.DENY.equals(type)) denies.add(host);
            else if(EmailSmtpRelayType.DENY_SPAM.equals(type)) denySpams.add(host);
            else if(EmailSmtpRelayType.ALLOW_RELAY.equals(type)) allowRelays.add(host);
            else AOServDaemon.reportWarning(new SQLException("Unexpected value for type: "+type), null);
        }
        
        // Builds email limits only for the packages referenced in domainPackages
        int noGrowSize = domainPackages.size() * 4 / 3 + 1;
        Map<String,EmailLimit> emailInLimits = new HashMap<String,EmailLimit>(noGrowSize);
        Map<String,EmailLimit> emailOutLimits = new HashMap<String,EmailLimit>(noGrowSize);
        Map<String,EmailLimit> emailRelayLimits = new HashMap<String,EmailLimit>(noGrowSize);
        for(String packageName : domainPackages.values()) {
            Package pk = AOServDaemon.getConnector().packages.get(packageName);
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
                primaryIP,
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
    }
}
