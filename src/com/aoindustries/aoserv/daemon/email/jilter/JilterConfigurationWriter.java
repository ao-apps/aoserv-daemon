package com.aoindustries.aoserv.daemon.email.jilter;

/*
 * Copyright 2007-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.Business;
import com.aoindustries.aoserv.client.BusinessService;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.IndexedSet;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.validator.AccountingCode;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
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
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class)
                && configurationWriter==null
            ) {
                System.out.print("Starting JilterConfigurationWriter: ");
                AOServConnector<?,?> connector=AOServDaemon.getConnector();
                configurationWriter=new JilterConfigurationWriter();
                connector.getAoServers().getTable().addTableListener(configurationWriter, 0);
                connector.getNetDevices().getTable().addTableListener(configurationWriter, 0);
                connector.getIpAddresses().getTable().addTableListener(configurationWriter, 0);
                connector.getEmailDomains().getTable().addTableListener(configurationWriter, 0);
                connector.getEmailAddresses().getTable().addTableListener(configurationWriter, 0);
                connector.getEmailSmtpRelays().getTable().addTableListener(configurationWriter, 0);
                connector.getBusinesses().getTable().addTableListener(configurationWriter, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "JilterConfigurationWriter";
    }
    private static final Object rebuildLock=new Object();

    protected boolean doRebuild() {
        try {
            AOServer aoServer = AOServDaemon.getThisAOServer();
            Server server = aoServer.getServer();

            // primaryIP
            InetAddress primaryIP = aoServer.getPrimaryIPAddress().getIPAddress();

            // restrict_outbound_email
            boolean restrict_outbound_email = aoServer.getRestrictOutboundEmail();

            // domainBusinesses and domainAddresses
            Map<String,String> domainBusinesses = new HashMap<String,String>();
            Map<String,Set<String>> domainAddresses = new HashMap<String,Set<String>>();
            for(EmailDomain ed : aoServer.getEmailDomains()) {
                String domain = ed.getDomain();
                // domainBusinesses
                domainBusinesses.put(domain, ed.getBusiness().getAccounting());
                // domainAddresses
                List<EmailAddress> eas = ed.getEmailAddresses();
                Set<String> addresses = new HashSet<String>(eas.size()*4/3+1);
                for(EmailAddress ea : eas) addresses.add(ea.getAddress());
                domainAddresses.put(domain, addresses);
            }

            // ips
            IndexedSet<IPAddress> ias = server.getIpAddresses();
            Set<String> ips = new HashSet<String>(ias.size()*4/3+1);
            for(IPAddress ia : ias) ips.add(ia.getIpAddress().getAddress());

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
                else LogFactory.getLogger(JilterConfigurationWriter.class).log(Level.WARNING, null, new SQLException("Unexpected value for type: "+type));
            }

            // Builds email limits only for the businesses referenced in domainBusinesses
            int noGrowSize = domainBusinesses.size() * 4 / 3 + 1;
            Map<String,EmailLimit> emailInLimits = new HashMap<String,EmailLimit>(noGrowSize);
            Map<String,EmailLimit> emailOutLimits = new HashMap<String,EmailLimit>(noGrowSize);
            Map<String,EmailLimit> emailRelayLimits = new HashMap<String,EmailLimit>(noGrowSize);
            BusinessService<?,?> businessTable = AOServDaemon.getConnector().getBusinesses();
            for(String accounting : domainBusinesses.values()) {
                Business bu = businessTable.get(AccountingCode.valueOf(accounting));
                int emailInBurst = bu.getEmailInBurst();
                float emailInRate = bu.getEmailInRate();
                if(emailInBurst!=-1 && !Float.isNaN(emailInRate)) emailInLimits.put(accounting, new EmailLimit(emailInBurst, emailInRate));
                int emailOutBurst = bu.getEmailOutBurst();
                float emailOutRate = bu.getEmailOutRate();
                if(emailOutBurst!=-1 && !Float.isNaN(emailOutRate)) emailOutLimits.put(accounting, new EmailLimit(emailOutBurst, emailOutRate));
                int emailRelayBurst = bu.getEmailRelayBurst();
                float emailRelayRate = bu.getEmailRelayRate();
                if(emailRelayBurst!=-1 && !Float.isNaN(emailRelayRate)) emailRelayLimits.put(accounting, new EmailLimit(emailRelayBurst, emailRelayRate));
            }
            synchronized(rebuildLock) {
                JilterConfiguration jilterConfiguration = new JilterConfiguration(
                    primaryIP.getAddress(),
                    restrict_outbound_email,
                    AOServDaemonConfiguration.getMonitorSmtpServer().toString(),
                    AOServDaemonConfiguration.getMonitorEmailSummaryFrom().toString(),
                    AOServDaemonConfiguration.getMonitorEmailSummaryTo().toString(),
                    AOServDaemonConfiguration.getMonitorEmailFullFrom().toString(),
                    AOServDaemonConfiguration.getMonitorEmailFullTo().toString(),
                    domainBusinesses,
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
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(JilterConfigurationWriter.class).log(Level.SEVERE, null, T);
            return false;
        }
    }
}
