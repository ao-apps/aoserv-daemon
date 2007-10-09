package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2005-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import com.aoindustries.util.sort.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * The primary purpose of the manager is to decouple the IMAP server from the SpamAssassin training.
 * The training process is slow and makes the IMAP server hesitate in ways that interfere with the
 * mail client and user.
 *
 * The SpamAssassin managed looks for emails left in a specific directories by the IMAP server.
 * When these files are found, the SpamAssassin training command (sa-learn) is invoked
 * on these directories.  The directories start with either ham_ or spam_ depending on
 * the type of messages.  In order to maintain the expected behavior, the directories
 * are processed in chronological time.  That way if a user drags a message to the Junk
 * folder then back to the INBOX, it will not be considered spam.
 *
 * To help avoid any race conditions, only directories that are at least 1 minute old (or in the future by 1 or more minutes
 * to handle clock changes) are considered on each pass.  This gives the IMAP server at least one minute to write all of
 * its files.
 *
 * Multiple directories from one user are sent to sa-learn at once when possible for efficiency.
 *
 * @author  AO Industries, Inc.
 */
public class SpamAssassinManager extends BuilderThread implements Runnable {

    /**
     * The interval to sleep after each pass.
     */
    private static final long DELAY_INTERVAL=(long)60*1000;

    /**
     * The directory containing the spam and ham directories.
     */
    private static final UnixFile incomingDirectory=new UnixFile("/var/spool/aoserv/spamassassin");

    private static SpamAssassinManager spamAssassinManager;

    private SpamAssassinManager() {
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, SpamAssassinManager.class, "run()", null);
        try {
            long lastStartTime=-1;
            while(true) {
                try {
                    while(true) {
                        long delay;
                        if(lastStartTime==-1) delay=DELAY_INTERVAL;
                        else {
                            delay=lastStartTime+DELAY_INTERVAL-System.currentTimeMillis();
                            if(delay>DELAY_INTERVAL) delay=DELAY_INTERVAL;
                        }
                        if(delay>0) {
                            try {
                                Thread.sleep(DELAY_INTERVAL);
                            } catch(InterruptedException err) {
                                AOServDaemon.reportWarning(err, null);
                            }
                        }
                        lastStartTime=System.currentTimeMillis();
                        processIncomingMessages();
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(60000);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SpamAssassinManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(SpamAssassinManager.class) && spamAssassinManager==null) {
                synchronized(System.out) {
                    if(spamAssassinManager==null) {
                        System.out.print("Starting SpamAssassinManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        spamAssassinManager=new SpamAssassinManager();
                        connector.linuxServerAccounts.addTableListener(spamAssassinManager, 0);
                        connector.ipAddresses.addTableListener(spamAssassinManager, 0);
                        spamAssassinManager.delayAndRebuild();
                        new Thread(spamAssassinManager, "SpamAssassinManager").start();
                        // Once per day, the razor logs will be trimmed to only include the last 1000 lines
                        CronDaemon.addCronJob(new RazorLogTrimmer(), AOServDaemon.getErrorHandler());
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private synchronized static void processIncomingMessages() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SpamAssassinManager.class, "processIncomingMessages()", null);
        try {
            String[] fileList=incomingDirectory.list();
            if(fileList!=null && fileList.length>0) {
                // Get the list of UnixFile's of all messages that are at least one minute old or one minute in the future
                List<UnixFile> readyList=new ArrayList<UnixFile>(fileList.length);
                for(int c=0;c<fileList.length;c++) {
                    String filename=fileList[c];
                    if(filename.startsWith("ham_") || filename.startsWith("spam_")) {
                        // Must be a directory
                        UnixFile uf=new UnixFile(incomingDirectory, filename, false);
                        Stat ufStat = uf.getStat();
                        if(ufStat.isDirectory()) {
                            long mtime=ufStat.getModifyTime();
                            if(mtime==-1) AOServDaemon.reportWarning(new IOException("getModify() returned -1"), new Object[] {"incomingDirectory="+incomingDirectory.getFilename(), "filename="+filename});
                            else {
                                long currentTime=System.currentTimeMillis();
                                if(
                                    (mtime-currentTime)>60000
                                    || (currentTime-mtime)>60000
                                ) {
                                    // The entire filename must be acceptable characters a-z, A-Z, 0-9, -, and _
                                    // This is, along with the character restrictions for usernames, are the key aspects
                                    // of the security of the following suexec call
                                    boolean filenameOK=true;
                                    for(int d=0;d<filename.length();d++) {
                                        char ch=filename.charAt(d);
                                        if(
                                            (ch<'a' || ch>'z')
                                            && (ch<'A' || ch>'Z')
                                            && (ch<'0' || ch>'9')
                                            && ch!='-'
                                            && ch!='_'
                                        ) {
                                            filenameOK=false;
                                            break;
                                        }
                                    }
                                    if(filenameOK) readyList.add(uf);
                                    else AOServDaemon.reportWarning(new IOException("Invalid directory name"), new Object[] {"incomingDirectory="+incomingDirectory.getFilename(), "filename="+filename});
                                }
                            }
                        } else AOServDaemon.reportWarning(new IOException("Not a directory"), new Object[] {"incomingDirectory="+incomingDirectory.getFilename(), "filename="+filename});
                    } else AOServDaemon.reportWarning(new IOException("Unexpected filename, should start with spam_ or ham_"), new Object[] {"incomingDirectory="+incomingDirectory.getFilename(), "filename="+filename});
                }
                if(!readyList.isEmpty()) {
                    // Sort the list by oldest time first
                    AutoSort.sortStatic(
                        readyList,
                        new Comparator<UnixFile>() {
                            public int compare(UnixFile uf1, UnixFile uf2) {
                                try {
                                    long mtime1=uf1.getStat().getModifyTime();
                                    long mtime2=uf2.getStat().getModifyTime();
                                    return
                                        mtime1<mtime2 ? -1
                                        : mtime1>mtime2 ? 1
                                        : 0
                                    ;
                                } catch(IOException err) {
                                    throw new WrappedException(
                                        err,
                                        new Object[] {
                                            "uf1="+uf1.getFilename(),
                                            "uf2="+uf2.getFilename()
                                        }
                                    );
                                }
                            }
                        }
                    );

                    // Work through the list from oldest to newest, and for each user batching as many spam or ham directories together as possible
                    AOServer aoServer=AOServDaemon.getThisAOServer();
                    StringBuilder tempSB=new StringBuilder();
                    List<UnixFile> thisPass=new ArrayList<UnixFile>();
                    while(!readyList.isEmpty()) {
                        thisPass.clear();
                        UnixFile currentUF=readyList.get(0);
                        int currentUID=currentUF.getStat().getUID();
                        boolean currentIsHam=currentUF.getFile().getName().startsWith("ham_");
                        thisPass.add(currentUF);
                        readyList.remove(0);
                        for(int c=0;c<readyList.size();c++) {
                            UnixFile other=readyList.get(c);
                            // Only consider batching/terminating batching for same UID
                            if(other.getStat().getUID()==currentUID) {
                                boolean otherIsHam=other.getFile().getName().startsWith("ham_");
                                if(currentIsHam==otherIsHam) {
                                    // If both spam or both ham, batch to one call and remove from later processing
                                    thisPass.add(other);
                                    readyList.remove(c);
                                    c--;
                                } else {
                                    // Mode for that user switched, termination batching loop
                                    break;
                                }
                            }
                        }

                        // Find the account based on UID
                        LinuxServerAccount lsa=aoServer.getLinuxServerAccount(currentUID);
                        if(lsa==null) AOServDaemon.reportWarning(new SQLException("Unable to find LinuxServerAccount"), new Object[] {"aoServer="+aoServer.getServer().getHostname(), "currentUID="+currentUID});
                        else {
                            String username=lsa.getLinuxAccount().getUsername().getUsername();

                            // Only train SpamAssassin when integration mode not set to none
                            EmailSpamAssassinIntegrationMode integrationMode=lsa.getEmailSpamAssassinIntegrationMode();
                            if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
                                // Call sa-learn for this pass
                                tempSB.setLength(0);
                                tempSB.append("/usr/bin/sa-learn ").append(currentIsHam?"--ham":"--spam").append(" --dir");
                                for(int c=0;c<thisPass.size();c++) {
                                    UnixFile uf=thisPass.get(c);
                                    tempSB.append(' ').append(uf.getFilename());
                                }
                                AOServDaemon.suexec(
                                    username,
                                    tempSB.toString()
                                );
                            }

                            // Remove the files processed (or not processed based on integration mode) in this pass
                            for(int c=0;c<thisPass.size();c++) {
                                UnixFile uf=thisPass.get(c);
                                // Change the ownership and mode to make sure directory entries are not replaced during delete
                                uf.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID).setMode(0700);
                                String[] list=uf.list();
                                if(list!=null) {
                                    // Delete all the immediate children, not recursing deeper
                                    for(int d=0;d<list.length;d++) new UnixFile(uf, list[d], false).delete();
                                }
                                uf.delete();
                            }
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SpamAssassinManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer server=AOServDaemon.getThisAOServer();

            int osv=server.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            final String primaryIP = server.getPrimaryIPAddress().getIPAddress();

            /**
             * Build the /etc/sysconfig/..... file.
             */
            ByteArrayOutputStream bout=new ByteArrayOutputStream();
            ChainWriter newOut=new ChainWriter(bout);
            {
                // Build a new file in RAM
                newOut.print("# Customized settings for spamassassin (spamd)\n"
                           + "\n"
                           + "# Cmdline options\n"
                           + "SPAMDOPTIONS=\"-d -c -m25 -H -i ").print(primaryIP).print(" -A ");
                // Allow all IP addresses for this machine
                Set<String> usedIps = new HashSet<String>();
                List<IPAddress> ips = server.getIPAddresses();
                for(IPAddress ip : ips) {
                    if(!ip.isWildcard() && !ip.getNetDevice().getNetDeviceID().isLoopback()) {
                        String addr = ip.getIPAddress();
                        if(!usedIps.contains(addr)) {
                            if(!usedIps.isEmpty()) newOut.print(',');
                            newOut.print(addr);
                            usedIps.add(addr);
                        }
                    }
                }
                // Allow the primary IP of our current failover server
                /*
                AOServer failoverServer = server.getFailoverServer();
                if(failoverServer!=null) {
                    IPAddress foPrimaryIP = failoverServer.getPrimaryIPAddress();
                    if(foPrimaryIP==null) throw new SQLException("Unable to find Primary IP Address for failover server: "+failoverServer);
                    String addr = foPrimaryIP.getIPAddress();
                    if(!usedIps.contains(addr)) {
                        if(!usedIps.isEmpty()) newOut.print(',');
                        newOut.print(addr);
                        usedIps.add(addr);
                    }
                }*/
                newOut.print("\"\n");
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    newOut.print("# Run at nice level of 10\n"
                               + "NICELEVEL=\"+10\"\n");
                }
                newOut.flush();
                newOut.close();
                byte[] newBytes=bout.toByteArray();
                // Compare to existing
                UnixFile spamdConfigFile = getSpamdConfigFile();
                if(!spamdConfigFile.getStat().exists() || !spamdConfigFile.contentEquals(newBytes)) {
                    // Overwrite when changed
                    FileOutputStream out=new FileOutputStream(spamdConfigFile.getFile());
                    out.write(newBytes);
                    out.flush();
                    out.close();
                    spamdConfigFile.setMode(0644);
                    AOServDaemon.exec(getSpamdRestartCommand());
                }
            }

            /**
             * Build the spam assassin files per account.
             */
            List<LinuxServerAccount> lsas=server.getLinuxServerAccounts();
            synchronized(rebuildLock) {
                for(int c=0;c<lsas.size();c++) {
                    LinuxServerAccount lsa=lsas.get(c);
                    EmailSpamAssassinIntegrationMode integrationMode=lsa.getEmailSpamAssassinIntegrationMode();
                    // Only write files when SpamAssassin is turned on
                    if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
                        UnixFile homeDir=new UnixFile(lsa.getHome());
                        UnixFile spamAssassinDir=new UnixFile(homeDir, ".spamassassin", false);
                        if(!spamAssassinDir.getStat().exists()) spamAssassinDir.mkdir().setMode(0700).chown(lsa.getUID().getID(), lsa.getPrimaryLinuxServerGroup().getGID().getID());
                        UnixFile user_prefs=new UnixFile(spamAssassinDir, "user_prefs", false);
                        // Build the new file in RAM
                        bout.reset();
                        newOut=new ChainWriter(bout);
                        if(
                            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                        ) {
                            newOut.print("required_score ").print(lsa.getSpamAssassinRequiredScore()).print('\n');
                            if(integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.POP3)) {
                                newOut.print("rewrite_header Subject *****SPAM*****\n");
                            }
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        newOut.flush();
                        newOut.close();
                        byte[] newBytes=bout.toByteArray();

                        // Compare to existing
                        if(!user_prefs.getStat().exists() || !user_prefs.contentEquals(newBytes)) {
                            // Overwrite when changed
                            FileOutputStream out=user_prefs.getSecureOutputStream(lsa.getUID().getID(), lsa.getPrimaryLinuxServerGroup().getGID().getID(), 0600, true);
                            out.write(newBytes);
                            out.flush();
                            out.close();
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SpamAssassinManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild SpamAssassin User Preferences";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SpamAssassinManager.class, "getProcessTimerMaximumTime()", null);
        try {
            return (long)30*60*1000;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public static class RazorLogTrimmer implements CronJob {
        
        private static final int NUM_LINES_RETAINED = 1000;

        public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
            return minute==5 && hour==1;
        }

        public int getCronJobScheduleMode() {
            return CRON_JOB_SCHEDULE_SKIP;
        }

        public String getCronJobName() {
            return "RazorLogTrimmer";
        }

        /**
         * Once a day, all of the razor-agent.log files are cleaned to only include the last 1000 lines.
         */
        public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
            try {
                Queue<String> queuedLines = new LinkedList<String>();
                for(LinuxServerAccount lsa : AOServDaemon.getThisAOServer().getLinuxServerAccounts()) {
                    UnixFile home = new UnixFile(lsa.getHome());
                    UnixFile dotRazor = new UnixFile(home, ".razor", false);
                    UnixFile razorAgentLog = new UnixFile(dotRazor, "razor-agent.log", false);
                    if(razorAgentLog.getStat().exists()) {
                        try {
                            boolean removed = false;
                            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(razorAgentLog.getFile())));
                            try {
                                queuedLines.clear();
                                String line;
                                while((line=in.readLine())!=null) {
                                    queuedLines.add(line);
                                    if(queuedLines.size()>NUM_LINES_RETAINED) {
                                        queuedLines.remove();
                                        removed=true;
                                    }
                                }
                            } finally {
                                in.close();
                            }
                            if(removed) {
                                int uid = lsa.getUID().getID();
                                int gid = lsa.getPrimaryLinuxServerGroup().getGID().getID();
                                UnixFile tempFile = UnixFile.mktemp(razorAgentLog.getFilename()+'.');
                                try {
                                    PrintWriter out = new PrintWriter(new BufferedOutputStream(tempFile.getSecureOutputStream(uid, gid, 0644, true)));
                                    try {
                                        while(!queuedLines.isEmpty()) out.println(queuedLines.remove());
                                    } finally {
                                        out.close();
                                    }
                                    tempFile.renameTo(razorAgentLog);
                                } finally {
                                    if(tempFile.getStat().exists()) tempFile.delete();
                                }
                            }
                        } catch(IOException err) {
                            AOServDaemon.reportWarning(err, new Object[] {"lsa="+lsa});
                        }
                    }
                }
            } catch(RuntimeException err) {
                AOServDaemon.reportError(err, null);
            } catch(IOException err) {
                AOServDaemon.reportError(err, null);
            } catch(SQLException err) {
                AOServDaemon.reportError(err, null);
            }
        }

        public int getCronJobThreadPriority() {
            return Thread.MIN_PRIORITY;
        }
    }
    
    private static String[] getSpamdRestartCommand() throws IOException, SQLException {
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) {
            return new String[] {"/etc/rc.d/init.d/spamd", "restart"};
        } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new String[] {"/etc/rc.d/init.d/spamassassin", "restart"};
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }

    private static UnixFile getSpamdConfigFile() throws IOException, SQLException {
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) {
            return new UnixFile("/etc/sysconfig/spamd");
        } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new UnixFile("/etc/sysconfig/spamassassin");
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }
}
