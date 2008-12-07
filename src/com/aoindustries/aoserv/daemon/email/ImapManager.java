package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Any IMAP/Cyrus-specific features are here.
 *
 * @author  AO Industries, Inc.
 */
final public class ImapManager extends BuilderThread {

    public static final File mailSpool=new File("/var/spool/mail");

    private static ImapManager imapManager;

    private ImapManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        synchronized(rebuildLock) {
            // TODO: Store cyrus password in aoserv-daemon.properties
            //       Also, change this password daily with new, random values and update properties file?
            //       /usr/bin/cyradm --user cyrus@default 192.168.1.12
            
            // TODO: Configure certificates in /etc/pki/cyrus-imapd on a per-IP basis.
            //       file:///home/o/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
            //       value of "disabled" if the certificate file doesn't exist (or use server default)
            //       openssl req -new -x509 -nodes -out cyrus-imapd.pem -keyout cyrus-imapd.pem -days 3650
            //       Other automated certificate management, sendmail too?
            
            // TODO: Turn off cyrus-imapd service and chkconfig off when not needed (like done for xinetd)

            // TODO: Create missing cyrus users
            //       createmailbox user/{username}
            //       setaclmailbox user/{username} cyrus c
            //       also create the Trash folder if they have IMAP integration enabled
            //       also create Junk folder if they have IMAP integration enabled
            //       Also create other folders and convert from UW software
            //           And control .mailboxlist, too.
            //           http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/MboxCyrusMigration
            //           chown to root as completed - do not deleted until later
            
            // TODO: Remove extra cyrus users
            //       deletemailbox user/cyrus.test@suspendo.aoindustries.com

            // TODO: Control the synchronous mode for ext2/ext3 automatically?
            // file:///home/o/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
            // cd /var/imap
            // chattr +S user quota user/* quota/*
            // chattr +S /var/spool/imap /var/spool/imap/*
            // chattr +S /var/spool/mqueue
            
            // TODO: sieve
            //       sieveusehomedir
            // Test account 1:
            //       hostname: 192.168.1.12
            //       username: cyrus.test@suspendo.aoindustries.com
            //       password: Clusk48Kulp
            // Test account 2:
            //       hostname: 192.168.1.12
            //       username: cyrus.test2
            //       password: Eflay43Klar
            
            // sieveshell:
            //       sieveshell --authname=cyrus.test@suspendo.aoindustries.com 192.168.1.12
            //       /bin/su -s /bin/bash -c "/usr/bin/sieveshell 192.168.1.12" cyrus.test@suspendo.aoindustries.com
            //       works now with loginrealms: suspendo.aoindustries.com
            
            // TODO: sieve only listen on primary IP only (for chroot failover)
            
            // TODO: Explicitely disallow @default in any username - make sure none in DB
            //       Explicitely disallow ao_servers.hostname=default
            //       Explicitely disallow cyrus@* in any usernames - make sure none in DB
            //       Make sure only one @ allowed in usernames - check database
            //       Make sure @ never first or last (always have something before and after) - check DB
            
            // TODO: Auto-expunge each mailbox once per day - cyr_expire?
            //         Set /vendor/cmu/cyrus-imapd/expire for Junk and Trash - see man cyr_expire
            //         http://lists.andrew.cmu.edu/pipermail/cyrus-devel/2007-June/000331.html
            //       http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/MboxExpire
            //         mboxcfg INBOX/SPAM expire 10
            //         info INBOX/SPAM
            //         or: imap.setannotation('INBOX/SPAM', '/vendor/cmu/cyrus-imapd/expire',
            
            // TODO: Run chk_cyrus from NOC?
            
            // TODO: Backups:
            //           stop master, snapshot, start master
            //           Or, without snapshots, do ctl_mboxlist -d
            //               http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/Backup
            //           Also, don't back-up Junk folders?
            
            // TODO: Maybe can get inbox size (and # of messages) from: mbexamine user/cyrus.test2@default
            //       Or use quota and quota command.
            //       Or use IMAP?
            
            // TODO: No longer support POP2
            
            // TODO: Add smmapd support
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(ImapManager.class)
                && imapManager==null
            ) {
                System.out.print("Starting ImapManager: ");
                //AOServConnector connector=AOServDaemon.getConnector();
                imapManager=new ImapManager();
                //connector.ipAddresses.addTableListener(imapManager, 0);
                //connector.netBinds.addTableListener(imapManager, 0);
                //connector.aoServers.addTableListener(imapManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild IMAP and Cyrus configurations";
    }

    public static long[] getImapFolderSizes(String username, String[] folderNames) throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        LinuxServerAccount lsa=thisAOServer.getLinuxServerAccount(username);
        if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+thisAOServer);
        long[] sizes=new long[folderNames.length];
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            for(int c=0;c<folderNames.length;c++) {
                String folderName = folderNames[c];
                if(folderName.indexOf("..") !=-1) sizes[c]=-1;
                else {
                    File folderFile;
                    if(folderName.equals("INBOX")) folderFile=new File(mailSpool, username);
                    else folderFile=new File(new File(lsa.getHome(), "Mail"), folderName);
                    if(folderFile.exists()) sizes[c]=folderFile.length();
                    else sizes[c]=-1;
                }
            }
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            throw new SQLException("TODO: CYRUS: Implement");
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
        return sizes;
    }

    public static void setImapFolderSubscribed(String username, String folderName, boolean subscribed) throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        LinuxServerAccount lsa=thisAOServer.getLinuxServerAccount(username);
        if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+thisAOServer);
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            UnixFile mailboxlist=new UnixFile(lsa.getHome(), ".mailboxlist");
            List<String> lines=new ArrayList<String>();
            boolean currentlySubscribed=false;
            if(mailboxlist.getStat().exists()) {
                BufferedReader in=new BufferedReader(new InputStreamReader(mailboxlist.getSecureInputStream()));
                try {
                    String line;
                    while((line=in.readLine())!=null) {
                        lines.add(line);
                        if(line.equals(folderName)) currentlySubscribed=true;
                    }
                } finally {
                    in.close();
                }
            }
            if(subscribed!=currentlySubscribed) {
                PrintWriter out=new PrintWriter(mailboxlist.getSecureOutputStream(lsa.getUID().getID(), lsa.getPrimaryLinuxServerGroup().getGID().getID(), 0644, true));
                try {
                    for(int c=0;c<lines.size();c++) {
                        String line=lines.get(c);
                        if(subscribed || !line.equals(folderName)) {
                            // Only print if the folder still exists
                            if(
                                line.equals("INBOX")
                                || line.equals("Drafts")
                                || line.equals("Trash")
                                || line.equals("Junk")
                            ) out.println(line);
                            else {
                                File folderFile=new File(new File(lsa.getHome(), "Mail"), line);
                                if(folderFile.exists()) out.println(line);
                            }
                        }
                    }
                    if(subscribed) out.println(folderName);
                } finally {
                    out.close();
                }
            }
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            throw new SQLException("TODO: CYRUS: Implement");
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }

    public static long getInboxSize(String username) throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new File(mailSpool, username).length();
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            throw new SQLException("TODO: CYRUS: Implement");
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }

    public static long getInboxModified(String username) throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new File(mailSpool, username).lastModified();
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            throw new SQLException("TODO: CYRUS: Implement");
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }
}
