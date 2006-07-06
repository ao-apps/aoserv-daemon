package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.cron.*;
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailAddressManager extends BuilderThread implements CronJob {

    /**
     * Sendmail files.
     */
    private static final UnixFile
        newAliases = new UnixFile("/etc/aliases.new"),
        aliases=new UnixFile("/etc/aliases"),
        backupAliases=new UnixFile("/etc/aliases.old"),

        newUserTable=new UnixFile("/etc/mail/virtusertable.new"),
        userTable=new UnixFile("/etc/mail/virtusertable"),
        backupUserTable=new UnixFile("/etc/mail/virtusertable.old")
    ;

    public static final File mailSpool=new File("/var/spool/mail");

    /**
     * qmail files.
     */
    private static final UnixFile
        newVirtualDomains=new UnixFile("/etc/qmail/virtualdomains.new"),
        virtualDomains=new UnixFile("/etc/qmail/virtualdomains"),
        oldVirtualDomains=new UnixFile("/etc/qmail/virtualdomains.old")
    ;
        
    private static EmailAddressManager emailAddressManager;

    private EmailAddressManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, EmailAddressManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailAddressManager.class, "doRebuild()", null);
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            AOServConnector connector=AOServDaemon.getConnector();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            List<EmailAddress> eas=aoServer.getEmailAddresses();
            synchronized(rebuildLock) {
                if(aoServer.isQmail()) {
                    // Write the /etc/qmail/virtualdomains.new
                    ChainWriter out = new ChainWriter(
                        new BufferedOutputStream(
                            newVirtualDomains.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                        )
                    );
                    try {
                        // Process the non-wildcard entries first
                        for(EmailAddress ea : eas) {
                            String address=ea.getAddress();
                            if(address.length()>0) {
                                writeQmailVirtualDomainLine(
                                    ea,
                                    out
                                );
                            }
                        }

                        // Process the wildcard entries
                        for(EmailAddress ea : eas) {
                            String address=ea.getAddress();
                            if(address.length()==0) {
                                writeQmailVirtualDomainLine(
                                    ea,
                                    out
                                );
                            }
                        }
                    } finally {
                        out.flush();
                        out.close();
                    }

                    // Move the previous files into backup position and the new ones into place
                    virtualDomains.renameTo(oldVirtualDomains);
                    newVirtualDomains.renameTo(virtualDomains);

                    EmailDomainManager.reloadMTA();
                } else {
                    //
                    // Write the new /etc/aliases file.
                    //

                    // Each username may only be used once within the aliases file
                    List<String> usernamesUsed=new SortedArrayList<String>();
                    ChainWriter aliasesOut = new ChainWriter(
                        new BufferedOutputStream(
                            newAliases.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_UID, 0644, true)
                        )
                    );
                    try {
                        aliasesOut.print(
                            "#\n"
                            + "#       @(#)aliases     8.2 (Berkeley) 3/5/94\n"
                            + "#\n"
                            + "#  Aliases in this file will NOT be expanded in the header from\n"
                            + "#  Mail, but WILL be visible over networks or from /bin/mail.\n"
                            + "#\n"
                            + "#       >>>>>>>>>>      The program \"newaliases\" must be run after\n"
                            + "#       >> NOTE >>      this file is updated for any changes to\n"
                            + "#       >>>>>>>>>>      show through to sendmail.\n"
                            + "#\n"
                            + "# Generated by "
                        );
                        aliasesOut.println(EmailAddressManager.class.getName());

                        // Write the system email aliases
                        for(SystemEmailAlias alias : aoServer.getSystemEmailAliases()) {
                            String address=alias.getAddress();
                            usernamesUsed.add(address);
                            aliasesOut.print(address).print(": ").println(alias.getDestination());
                        }

                        // Hide the Linux account usernames, so support@tantrix.com does not go to support@aoindustries.com
                        for(LinuxServerAccount lsa : aoServer.getLinuxServerAccounts()) {
                            String username=lsa.getLinuxAccount().getUsername().getUsername();
                            if(!usernamesUsed.contains(username)) {
                                if(username.indexOf('@')==-1) aliasesOut.print(username).println(": |/usr/aoserv/bin/ex_nouser");
                                usernamesUsed.add(username);
                            }
                        }

                        // Write the /etc/mail/virtusertable.new
                        ChainWriter usersOut = new ChainWriter(
                            new BufferedOutputStream(
                                newUserTable.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true)
                            )
                        );
                        try {
                            String[] devNullUsername=new String[1];
                            Map<String,String> singleForwardingTies=new HashMap<String,String>();
                            Map<String,String> singleListTies=new HashMap<String,String>();
                            Map<String,String> singlePipeTies=new HashMap<String,String>();
                            Map<String,String> singleInboxTies=new HashMap<String,String>();

                            // Process the non-wildcard entries first
                            for(EmailAddress ea : eas) {
                                String address=ea.getAddress();
                                if(address.length()>0) {
                                    writeEmailAddressConfigs(
                                        ea,
                                        usernamesUsed,
                                        devNullUsername,
                                        singleForwardingTies,
                                        singleListTies,
                                        singlePipeTies,
                                        singleInboxTies,
                                        aliasesOut,
                                        usersOut
                                    );
                                }
                            }

                            // Send all other special email addresses if they have not been overridden.
                            for(EmailDomain ed : aoServer.getEmailDomains()) {
                                String domain=ed.getDomain();
                                if(ed.getEmailAddress("abuse")==null) usersOut.print("abuse@").print(domain).print("\tabuse\n");
                                if(ed.getEmailAddress("devnull")==null) usersOut.print("devnull@").print(domain).print("\tdevnull\n");
                                if(ed.getEmailAddress("hostmaster")==null) usersOut.print("hostmaster@").print(domain).print("\thostmaster\n");
                                if(ed.getEmailAddress("mailer-daemon")==null) usersOut.print("mailer-daemon@").print(domain).print("\tmailer-daemon\n");
                                if(ed.getEmailAddress("postmaster")==null) usersOut.print("postmaster@").print(domain).print("\tpostmaster\n");
                                if(ed.getEmailAddress("root")==null) usersOut.print("root@").print(domain).print("\troot\n");
                            }

                            // Process the wildcard entries
                            for(EmailAddress ea : eas) {
                                String address=ea.getAddress();
                                if(address.length()==0) {
                                    writeEmailAddressConfigs(
                                        ea,
                                        usernamesUsed,
                                        devNullUsername,
                                        singleForwardingTies,
                                        singleListTies,
                                        singlePipeTies,
                                        singleInboxTies,
                                        aliasesOut,
                                        usersOut
                                    );
                                }
                            }

                            // Block all other email_domains that have not been explicitely configured as an email address.
                            // This had a dead.letter problem and was commented for a while
                            if(aoServer.getRestrictOutboundEmail()) {
                                for(EmailDomain ed : aoServer.getEmailDomains()) {
                                    String domain=ed.getDomain();
                                    if(ed.getEmailAddress("")==null) usersOut.print("@").print(domain).print("\terror:5.1.1:550 No such email address here\n");
                                }
                            }
                        } finally {
                            // Close the virtusertable
                            usersOut.flush();
                            usersOut.close();
                        }
                    } finally {
                        // Close the aliases file
                        aliasesOut.flush();
                        aliasesOut.close();
                    }
                    // Move the previous files into backup position and the new ones into place
                    userTable.renameTo(backupUserTable);
                    newUserTable.renameTo(userTable);

                    aliases.renameTo(backupAliases);
                    newAliases.renameTo(aliases);

                    // Rebuild the hash map
                    makeMap();

                    // Call newaliases
                    newAliases();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static void writeQmailVirtualDomainLine(EmailAddress ea, ChainWriter out) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailAddressManager.class, "writeQmailVirtualDomainLine(EmailAddress,ChainWriter)", null);
        try {
            List<LinuxAccAddress> laas=ea.getLinuxAccAddresses();
            if(!laas.isEmpty()) {
                String address=ea.getAddress();
                String domain=ea.getDomain().getDomain();
                for(LinuxAccAddress laa : laas) {
                    String username=laa.getLinuxAccount().getUsername().getUsername();
                    if(address.length()>0) out.print(address).print('@');
                    out.print(domain).print(':').print(username).print('\n');
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void writeEmailAddressConfigs(
        EmailAddress ea,
        List<String> usernamesUsed,
        String[] devNullUsername,
        Map<String,String> singleForwardingTies,
        Map<String,String> singleListTies,
        Map<String,String> singlePipeTies,
        Map<String,String> singleInboxTies,
        ChainWriter aliasesOut,
        ChainWriter usersOut
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailAddressManager.class, "writeEmailAddressConfigs(EmailAddress,List<String>,String[],Map<String,String>,Map<String,String>,Map<String,String>,Map<String,String>,ChainWriter,ChainWriter)", null);
        try {
            String address=ea.getAddress();
            String domain=ea.getDomain().getDomain();

            /*
             * The possible email deliveries:
             *
             * 1) /dev/null only
             * 2) One forwarding destination, BEA ignored (use singleForwardingTies)
             * 3) One email list, BEA ignored (use singleListTies)
             * 4) One pipe, BEA ignored (use singlePipeTies)
             * 5) One Inbox only, BEA ignored (use singleInboxTies)
             * 6) Multiple destinations, BEA ignored (list each)
             * 7) Nothing (ignore)
             */
            BlackholeEmailAddress bea=ea.getBlackholeEmailAddress();
            List<EmailForwarding> efs=ea.getEmailForwardings();
            List<EmailListAddress> elas=ea.getEnabledEmailListAddresses();
            List<EmailPipeAddress> epas=ea.getEnabledEmailPipeAddresses();
            List<LinuxAccAddress> laas=ea.getLinuxAccAddresses();

            String tieUsername;

            // 1) /dev/null only
            if(
                bea!=null
                && efs.isEmpty()
                && elas.isEmpty()
                && epas.isEmpty()
                && laas.isEmpty()
            ) {
                tieUsername=devNullUsername[0];
                if(tieUsername==null) {
                    devNullUsername[0]=tieUsername=getTieUsername(usernamesUsed);
                    aliasesOut.print(tieUsername).println(": /dev/null");
                }

            // 2) One forwarding destination, BEA ignored (use singleForwardingTies)
            } else if(
                efs.size()==1
                && elas.isEmpty()
                && epas.isEmpty()
                && laas.isEmpty()
            ) {
                String destination=efs.get(0).getDestination();
                tieUsername=singleForwardingTies.get(destination);
                if(tieUsername==null) {
                    singleForwardingTies.put(destination, tieUsername=getTieUsername(usernamesUsed));
                    aliasesOut.print(tieUsername).print(": ").println(destination);
                }

            // 3)  One email list, BEA ignored (use singleListTies)
            } else if(
                efs.isEmpty()
                && elas.size()==1
                && epas.isEmpty()
                && laas.isEmpty()
            ) {
                String path=elas.get(0).getEmailList().getPath();
                tieUsername=singleListTies.get(path);
                if(tieUsername==null) {
                    singleListTies.put(path, tieUsername=getTieUsername(usernamesUsed));
                    aliasesOut.print(tieUsername).print(": :include:").println(path);
                }

            // 4) One pipe, BEA ignored (use singlePipeTies)
            } else if(
                efs.isEmpty()
                && elas.isEmpty()
                && epas.size()==1
                && laas.isEmpty()
            ) {
                String path=epas.get(0).getEmailPipe().getPath();
                tieUsername=singlePipeTies.get(path);
                if(tieUsername==null) {
                    singlePipeTies.put(path, tieUsername=getTieUsername(usernamesUsed));
                    aliasesOut.print(tieUsername).print(": \"| ").print(path).println('"');
                }

            // 5) One Inbox only, BEA ignored (use singleInboxTies)
            } else if(
                efs.isEmpty()
                && elas.isEmpty()
                && epas.isEmpty()
                && laas.size()==1
            ) {
                LinuxAccount la=laas.get(0).getLinuxAccount();
                if(la!=null) {
                    Username un=la.getUsername();
                    if(un!=null) {
                        String username=un.getUsername();
                        tieUsername=singleInboxTies.get(username);
                        if(tieUsername==null) {
                            singleInboxTies.put(username, tieUsername=getTieUsername(usernamesUsed));
                            aliasesOut.print(tieUsername).print(": \\").println(StringUtility.replace(username, '@', "\\@"));
                        }
                    } else tieUsername=null;
                } else tieUsername=null;

            // 6) Multiple destinations, BEA ignored (list each)
            } else if(
                !efs.isEmpty()
                || !elas.isEmpty()
                || !epas.isEmpty()
                || !laas.isEmpty()
            ) {
                tieUsername=getTieUsername(usernamesUsed);
                aliasesOut.print(tieUsername).print(": ");
                boolean done=false;
                for(EmailForwarding ef : efs) {
                    if(done) aliasesOut.print(",\n\t");
                    else done=true;
                    aliasesOut.print(ef.getDestination());
                }
                for(EmailListAddress ela : elas) {
                    if(done) aliasesOut.print(",\n\t");
                    else done=true;
                    aliasesOut.print(":include:").print(ela.getEmailList().getPath());
                }
                for(EmailPipeAddress epa : epas) {
                    if(done) aliasesOut.print(",\n\t");
                    else done=true;
                    aliasesOut.print("\"| ").print(epa.getEmailPipe().getPath()).print('"');
                }
                for(LinuxAccAddress laa : laas) {
                    if(done) aliasesOut.print(",\n\t");
                    else done=true;
                    aliasesOut.print('\\').print(StringUtility.replace(laa.getLinuxAccount().getUsername().getUsername(),'@',"\\@"));
                }
                aliasesOut.println();

            // 7) Not used - ignore
            } else tieUsername=null;

            if(tieUsername!=null) usersOut.print(address).print('@').print(domain).print('\t').println(tieUsername);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static long[] getImapFolderSizes(String username, String[] folderNames) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, EmailAddressManager.class, "getImapFolderSizes(String,String[])", null);
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            LinuxServerAccount lsa=thisAOServer.getLinuxServerAccount(username);
            if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+thisAOServer);
            long[] sizes=new long[folderNames.length];
            for(int c=0;c<folderNames.length;c++) {
                String folderName = folderNames[c];
                if(folderName.indexOf("..") !=-1) sizes[c]=-1;
                else {
                    File folderFile;
                    if(folderName.equals("INBOX")) folderFile=new File(mailSpool, username);
                    else {
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                        ) {
                            folderFile=new File(new File(lsa.getHome(), "Mail"), folderName);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    if(folderFile.exists()) sizes[c]=folderFile.length();
                    else sizes[c]=-1;
                }
            }
            return sizes;
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void setImapFolderSubscribed(String username, String folderName, boolean subscribed) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, EmailAddressManager.class, "setImapFolderSubscribed(String,String,boolean)", null);
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();
            LinuxServerAccount lsa=thisAOServer.getLinuxServerAccount(username);
            if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+thisAOServer);
            UnixFile mailboxlist=new UnixFile(lsa.getHome(), ".mailboxlist");
            List<String> lines=new ArrayList<String>();
            boolean currentlySubscribed=false;
            if(mailboxlist.exists()) {
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
                                File folderFile;
                                if(
                                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                                ) {
                                    folderFile=new File(new File(lsa.getHome(), "Mail"), line);
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                                if(folderFile.exists()) out.println(line);
                            }
                        }
                    }
                    if(subscribed) out.println(folderName);
                } finally {
                    out.close();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static long getInboxSize(String username) throws IOException {
        Profiler.startProfile(Profiler.IO, EmailAddressManager.class, "getInboxSize(String)", null);
        try {
            return new File(mailSpool, username).length();
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static long getInboxModified(String username) throws IOException {
        Profiler.startProfile(Profiler.IO, EmailAddressManager.class, "getInboxModified(String)", null);
        try {
            return new File(mailSpool, username).lastModified();
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    private static final int TIE_USERNAME_DIGITS=12;
    private static final char[] tieChars={
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
        'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };
    private static String getTieUsername(List<String> usernamesUsed) {
        Profiler.startProfile(Profiler.UNKNOWN, EmailAddressManager.class, "getTieUsername(List<String>)", null);
        try {
            Random random=AOServDaemon.getRandom();
            StringBuilder SB=new StringBuilder(4+TIE_USERNAME_DIGITS);
            SB.append("tmp_");
            while(true) {
                SB.setLength(4);
                for(int c=0;c<TIE_USERNAME_DIGITS;c++) SB.append(tieChars[random.nextInt(tieChars.length)]);
                String username=SB.toString();
                if(!usernamesUsed.contains(username)) {
                    usernamesUsed.add(username);
                    return username;
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object makeMapLock=new Object();
    private static void makeMap() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailAddressManager.class, "makeMap()", null);
        try {
            synchronized(makeMapLock) {
                // Run the command
                String makemap;
                int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
                if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                ) makemap="/usr/sbin/makemap";
                else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                String[] cmd = { makemap, "hash", userTable.getFilename() };
                Process P = Runtime.getRuntime().exec(cmd);

                // Pipe the file into the process
                InputStream in = new BufferedInputStream(new FileInputStream(userTable.getFilename()));
                try {
                    OutputStream out = P.getOutputStream();
                    try {
                        int ch;
                        while ((ch = in.read()) != -1) out.write(ch);
                    } finally {
                        out.flush();
                        out.close();
                    }
                } finally {
                    in.close();
                }

                // Wait for the process to complete
                try {
                    P.waitFor();
                } catch (InterruptedException err) {
                    AOServDaemon.reportWarning(err, null);
                }

                // Check for error exit code
                int exit = P.exitValue();
                if (exit != 0) throw new IOException("Non-zero exit status: " + exit);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailAddressManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(EmailAddressManager.class) && emailAddressManager==null) {
                synchronized(System.out) {
                    if(emailAddressManager==null) {
                        System.out.print("Starting EmailAddressManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        emailAddressManager=new EmailAddressManager();
                        connector.blackholeEmailAddresses.addTableListener(emailAddressManager, 0);
                        connector.emailAddresses.addTableListener(emailAddressManager, 0);
                        connector.emailForwardings.addTableListener(emailAddressManager, 0);
                        connector.emailLists.addTableListener(emailAddressManager, 0);
                        connector.emailListAddresses.addTableListener(emailAddressManager, 0);
                        connector.emailPipes.addTableListener(emailAddressManager, 0);
                        connector.emailPipeAddresses.addTableListener(emailAddressManager, 0);
                        connector.linuxServerAccounts.addTableListener(emailAddressManager, 0);
                        connector.linuxAccAddresses.addTableListener(emailAddressManager, 0);
                        connector.systemEmailAliases.addTableListener(emailAddressManager, 0);
                        // Register in CronDaemon
                        CronDaemon.addCronJob(emailAddressManager, AOServDaemon.getErrorHandler());
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object newAliasesLock=new Object();
    private static final String[] newAliasesCommand={"/usr/bin/newaliases"};
    private static void newAliases() throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailDomainManager.class, "newAliases()", null);
        try {
            synchronized(newAliasesLock) {
                // Run the command
                AOServDaemon.exec(newAliasesCommand);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, EmailDomainManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild Email Addresses";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public String getCronJobName() {
        return EmailAddressManager.class.getName();
    }
    
    public int getCronJobScheduleMode() {
        return CRON_JOB_SCHEDULE_SKIP;
    }
    
    public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        return
            minute==30
            && hour==1
            && dayOfMonth==1
            && month==1
        ;
    }

    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        rebuildEmailCertificates();
    }
    
    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY;
    }

    public static void rebuildEmailCertificates() {
        // TODO: Rebuild certificates automatically once a year
    }
}