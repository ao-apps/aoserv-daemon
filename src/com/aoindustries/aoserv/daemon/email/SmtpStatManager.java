package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2002-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.Package;
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.email.maillog.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Combines and commits the data provided by <code>SmtpStatLogReader</code>.
 * Each use of the SMTP server is added to statistics.  If the account exceeds
 * its SMTP resource limits for the day, the monitoring contacts are notified.
 * For efficiency, updates of the actual data is cached and periodically
 * committed to the database.
 *
 * @see  SmtpStatLogReader
 *
 * @author  AO Industries, Inc.
 */
public class SmtpStatManager implements Runnable {

    private static final int MAX_FROM_QUEUE=10000;
    private static final float FROM_QUEUE_LOAD_FACTOR=0.75f;
    private static final int FROM_QUEUE_INITIAL_CAPACITY=(int)(MAX_FROM_QUEUE/FROM_QUEUE_LOAD_FACTOR)+1;
    private static final int FROM_QUEUE_CLEANUP_SIZE=1000;

    /**
     * The size an email will be considered if its from log entry is not known.
     */
    private static final int DEFAULT_EMAIL_SIZE=4096;

    /**
     * The delay between each database commit.
     */
    private static final long MEAN_COMMIT_PERIOD=5L*60*1000;
    private static final int COMMIT_PERIOD_DEVIATION=30*1000;

    /**
     * Each package has a separate lock for updates and commits.
     */
    private static final Map<Package,Object> packageLocks=new HashMap<Package,Object>();
    private static final Object packageLocksLock=new Object();

    /**
     * The from entries are queued and matched with their corresponding to entries
     */
    private static final Map<String,FromQueueEntry> fromQueue=new HashMap<String,FromQueueEntry>(FROM_QUEUE_INITIAL_CAPACITY, FROM_QUEUE_LOAD_FACTOR);

    /**
     * The total number of emails received since the last commit for each package and day.
     */
    private static final Map<Package,Map<java.util.Date,Integer>> emailInCounts=new HashMap<Package,Map<java.util.Date,Integer>>();
    
    /**
     * The total number of emails sent since the last commit for each package and day.
     */
    private static final Map<Package,Map<java.util.Date,Integer>> emailOutCounts=new HashMap<Package,Map<java.util.Date,Integer>>();

    /**
     * The total number of bytes received since the last commit for each package and day.
     */
    private static final Map<Package,Map<java.util.Date,Long>> emailInBandwidths=new HashMap<Package,Map<java.util.Date,Long>>();

    /**
     * The total number of bytes sent since the last commit for each package and day.
     */
    private static final Map<Package,Map<java.util.Date,Long>> emailOutBandwidths=new HashMap<Package,Map<java.util.Date,Long>>();

    private static boolean started=false;

    private SmtpStatManager() {
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpStatManager.class, "run()", null);
        try {
            while(true) {
                try {
                    Random random=AOServDaemon.getRandom();
                    while(true) {
                        // Sleep a slightly random amount
                        long delay=MEAN_COMMIT_PERIOD-COMMIT_PERIOD_DEVIATION+random.nextInt(COMMIT_PERIOD_DEVIATION*2);
                        try {
                            Thread.sleep(delay);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }

                        // Commit the changes to the database
                        List<Package> keys=new ArrayList<Package>();
                        synchronized(emailInCounts) {
                            Iterator<Package> I=emailInCounts.keySet().iterator();
                            while(I.hasNext()) keys.add(I.next());
                        }
                        int size=keys.size();
                        for(int c=0;c<size;c++) {
                            // Lock on the package
                            Package pack=keys.get(c);
                            Object packageLock;
                            synchronized(packageLocksLock) {
                                packageLock=packageLocks.get(pack);
                                if(packageLock==null) packageLocks.put(pack, packageLock=new Object());
                            }
                            synchronized(packageLock) {
                                Map<java.util.Date,Integer> inCounts=emailInCounts.get(pack);
                                Map<java.util.Date,Integer> outCounts=emailOutCounts.get(pack);
                                Map<java.util.Date,Long> inBandwidths=emailInBandwidths.get(pack);
                                Map<java.util.Date,Long> outBandwidths=emailOutBandwidths.get(pack);
                                Iterator dates=inCounts.keySet().iterator();
                                while(dates.hasNext()) {
                                    java.util.Date date=(java.util.Date)dates.next();
                                    int inCount=((Integer)inCounts.get(date)).intValue();
                                    int outCount=((Integer)outCounts.get(date)).intValue();
                                    long inBandwidth=inBandwidths.get(date).longValue();
                                    long outBandwidth=((Long)outBandwidths.get(date)).longValue();
                                    pack.addSendmailSmtpStat(date.getTime(), AOServDaemon.getThisAOServer(), inCount, inBandwidth, outCount, outBandwidth);
                                }
                                emailInCounts.remove(pack);
                                emailOutCounts.remove(pack);
                                emailInBandwidths.remove(pack);
                                emailOutBandwidths.remove(pack);
                            }
                        }
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
        Profiler.startProfile(Profiler.UNKNOWN, SmtpStatManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(SmtpStatManager.class) && !AOServDaemon.getThisAOServer().isQmail()) {
                if(!started) {
                    synchronized(System.out) {
                        if(!started) {
                            System.out.print("Starting SmtpStatManager: ");
                            new Thread(new SmtpStatManager(), "SmtpStatManager").start();
                            System.out.println("Done");
                            started=true;
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static class FromQueueEntry implements Comparable<FromQueueEntry> {

        private final SmtpFromEntry smtpFromEntry;
        private long lastAccessed;

        private FromQueueEntry(SmtpFromEntry from) {
            this.smtpFromEntry=from;
            this.lastAccessed=System.currentTimeMillis();
        }
        
        public int compareTo(FromQueueEntry other) {
            long diff=lastAccessed-other.lastAccessed;
            if(diff<Integer.MIN_VALUE) return Integer.MIN_VALUE;
            if(diff>Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int)diff;
        }        
    }

    private static void addToFromQueue(SmtpFromEntry from) {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpStatManager.class, "addToFromQueue(SmtpFromEntry)", null);
        try {
            synchronized(fromQueue) {
                if(fromQueue.size()>=MAX_FROM_QUEUE) cleanFromQueue();
                fromQueue.put(from.getMessageID(), new FromQueueEntry(from));
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void cleanFromQueue() {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpStatManager.class, "cleanFromQueue()", null);
        try {
            // All accessed to this method are already synchronized
            List<FromQueueEntry> elements=new ArrayList<FromQueueEntry>(MAX_FROM_QUEUE);
            elements.addAll(fromQueue.values());
            Collections.sort(elements);
            for(int c=0;c<FROM_QUEUE_CLEANUP_SIZE;c++) fromQueue.remove(elements.get(c).smtpFromEntry.getMessageID());
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static SmtpFromEntry getFromFromQueue(String messageID) {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpStatManager.class, "getFromFromQueue(String)", null);
        try {
            synchronized(fromQueue) {
                FromQueueEntry entry=fromQueue.get(messageID);
                if(entry==null) return null;
                entry.lastAccessed=System.currentTimeMillis();
                return entry.smtpFromEntry;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void processMailLogEntry(SmtpEntry entry) {
        Profiler.startProfile(Profiler.UNKNOWN, SmtpStatManager.class, "processMailLogEntry(SmtpEntry)", null);
        try {
            if(entry instanceof SmtpFromEntry) {
                SmtpFromEntry from=(SmtpFromEntry)entry;
                // Queue and wait for its to entry
                addToFromQueue(from);
            } else if(entry instanceof SmtpToEntry) {
                SmtpToEntry to=(SmtpToEntry)entry;
                String messageID=to.getMessageID();

                // Find the matching from entry
                SmtpFromEntry from=getFromFromQueue(messageID);

                try {
                    /*
                     * Resolving the package for the entry
                     *
                     * 1) If mailer is "local", credit it as only incoming to the recipient, either addy or username
                     * 2) If from is a username, credit to that account
                     * 3) If ctladdr provides UID, credit to that account
                     * 4) If the from provides IDENT on an AO IP, credit to that account
                     * 5) If from domain matches a relay for the IP, use that package
                     * 6) Match one random of the non-AO from relays
                     * 7) Match from email domain or =domain.com@ in the from
                     * 8) Match to as the destination of an email forwarding, random and weighted by
                     *    package probability
                     * 9) Log as error and credit to AO
                     */
                    AOServConnector conn=AOServDaemon.getConnector();
                    AOServer thisAOServer=AOServDaemon.getThisAOServer();
                    String rootAccounting=conn.businesses.getRootAccounting();
                    String toAddy=null;
                    {
                        String addy=to.getTo();
                        if(addy!=null) {
                            int addylen=addy.length();
                            if(
                                addylen>3
                                && addy.charAt(0)=='<'
                                && addy.charAt(addylen-1)=='>'
                            ) toAddy=addy.substring(1, addylen-1);
                            else toAddy=addy;
                        }
                    }
                    Package toPackage=null;
                    if(to.getMailer().equals(SmtpToEntry.LOCAL)) {
                        if(toAddy!=null && toAddy!=null) {
                            EmailDomain sd=getEmailDomainForAddress(thisAOServer, toAddy);
                            if(sd!=null) toPackage=sd.getPackage();
                        }
                        if(toPackage==null) {
                            String tempTo=to.getTo();
                            if(tempTo!=null) {
                                Username un=conn.usernames.get(tempTo);
                                if(un!=null) toPackage=un.getPackage();
                            }
                        }
                    }
                    if(toPackage!=null) {
                        // 1) Parse the domain and credit to the owner
                        addToPackage(toPackage, to.getDate(), from==null?DEFAULT_EMAIL_SIZE:from.getSize(), true, false);
                    } else {
                        Username un;
                        if(from!=null && (un=conn.usernames.get(from.getFrom()))!=null) {
                            // 2) From is a username, credit to the account
                            addToPackage(un.getPackage(), to.getDate(), from.getSize(), false, true);
                        } else {
                            String ctladdr=to.getCtlAddr();
                            LinuxServerAccount lsa=null;
                            if(ctladdr!=null) {
                                int ctllen=ctladdr.length();
                                if(ctllen>3 && ctladdr.charAt(ctllen-1)==')') {
                                    int slashPos=ctladdr.lastIndexOf('/', ctllen-2);
                                    if(slashPos!=-1) {
                                        int parenPos=ctladdr.lastIndexOf('(', slashPos-1);
                                        if(parenPos!=-1) {
                                            int uid=Integer.parseInt(ctladdr.substring(parenPos+1, slashPos));
                                            lsa=thisAOServer.getLinuxServerAccount(uid);
                                        }
                                    }
                                }
                            }
                            if(lsa!=null) {
                                // 3) ctladdr provided a valid UID
                                addToPackage(lsa.getLinuxAccount().getUsername().getPackage(), to.getDate(), from==null?DEFAULT_EMAIL_SIZE:from.getSize(), false, true);
                            } else {
                                Package identPackage=null;
                                if(from!=null) {
                                    String relay=from.getRelay();
                                    int relayLen=relay.length();
                                    if(relayLen>6 && relay.substring(0, 6).equalsIgnoreCase("IDENT:")) {
                                        int atPos=relay.indexOf('@', 6);
                                        if(atPos!=-1) {
                                            String username=relay.substring(6, atPos);
                                            Username relayUn=conn.usernames.get(username);
                                            if(relayUn!=null) {
                                                LinuxAccount relayLa=relayUn.getLinuxAccount();
                                                if(relayLa!=null && relay.charAt(relayLen-1)==']') {
                                                    int leftPos=relay.lastIndexOf('[', relayLen-2);
                                                    if(leftPos!=-1) {
                                                        String ip=relay.substring(leftPos+1, relayLen-1);
                                                        AOServer ao=null;
                                                        if(ip.equals(IPAddress.LOOPBACK_IP)) {
                                                            ao=thisAOServer;
                                                        } else {
                                                            List<IPAddress> ipAddresses=conn.ipAddresses.getIPAddresses(ip);
                                                            if(!ipAddresses.isEmpty()) {
                                                                NetDevice nd=ipAddresses.get(0).getNetDevice();
                                                                if(nd!=null) ao=nd.getAOServer();
                                                            }
                                                        }
                                                        if(ao!=null) {
                                                            LinuxServerAccount relayLsa=relayLa.getLinuxServerAccount(ao);
                                                            if(relayLsa!=null) identPackage=relayUn.getPackage();
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // 4) If the from provides IDENT on an AO IP, credit to that account
                                if(identPackage!=null) {
                                    addToPackage(
                                        identPackage,
                                        to.getDate(),
                                        from==null?DEFAULT_EMAIL_SIZE:from.getSize(),
                                        false,
                                        true
                                    );
                                } else {
                                    List<Package> relayPackages=new SortedArrayList<Package>();
                                    if(from!=null) {
                                        String relay=from.getRelay();
                                        if(relay!=null) {
                                            int relayLen=relay.length();
                                            if(relayLen>=9) {
                                                if(relay.charAt(relayLen-1)==']') {
                                                    int leftPos=relay.lastIndexOf('[', relayLen-2);
                                                    if(leftPos!=-1) {
                                                        String ip=relay.substring(leftPos+1, relayLen-1);
                                                        for(EmailSmtpRelay ssr : thisAOServer.getEmailSmtpRelays()) {
                                                            if(ssr.getHost().equals(ip)) {
                                                                Package pk=ssr.getPackage();
                                                                if(
                                                                    !pk.getName().equals(rootAccounting)
                                                                    && !relayPackages.contains(pk)
                                                                ) relayPackages.add(pk);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Package domainPackage=null;
                                    if(from!=null) {
                                        String addy=from.getFrom();
                                        if(addy!=null && addy.length()>0) {
                                            if(addy.charAt(0)=='<') {
                                                int rightPos=addy.indexOf('>', 1);
                                                if(rightPos!=-1) {
                                                    addy=addy.substring(1, rightPos);
                                                    EmailDomain sd=getEmailDomainForAddress(thisAOServer, addy);
                                                    if(sd!=null) domainPackage=sd.getPackage();
                                                }
                                            }
                                            if(domainPackage==null) {
                                                int equalPos=addy.indexOf('=');
                                                if(equalPos!=-1) {
                                                    int atPos=addy.indexOf('@', equalPos+1);
                                                    if(atPos!=-1) {
                                                        String domain=addy.substring(equalPos+1, atPos).trim();
                                                        if(domain.length()>0) {
                                                            EmailDomain sd=thisAOServer.getEmailDomain(domain.toLowerCase());
                                                            if(sd!=null) domainPackage=sd.getPackage();
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if(domainPackage!=null && relayPackages.contains(domainPackage)) {
                                        // 5) If from domain matches a relay for the IP, use that package
                                        addToPackage(domainPackage, to.getDate(), from==null?DEFAULT_EMAIL_SIZE:from.getSize(), true, true);
                                    } else if(relayPackages.size()>0) {
                                        // 6) Match one random of the non-AO from relays
                                        addToPackage(
                                            relayPackages.get(AOServDaemon.getRandom().nextInt(relayPackages.size())),
                                            to.getDate(),
                                            from==null?DEFAULT_EMAIL_SIZE:from.getSize(),
                                            true,
                                            true
                                        );
                                    } else if(domainPackage!=null) {
                                        // 7) Match from email domain or =domain.com@ in the from
                                        addToPackage(domainPackage, to.getDate(), from==null?DEFAULT_EMAIL_SIZE:from.getSize(), true, true);
                                    } else {
                                        // TODO: should this list be just for thisAOServer?
                                        SortedArrayList<Package> forwardingPackages=new SortedArrayList<Package>();
                                        IntList forwardingCounts=new IntArrayList();
                                        int forwardingTotal=0;
                                        for(EmailForwarding ef : conn.emailForwardings) {
                                            String dest=ef.getDestination();
                                            if(
                                                dest.equalsIgnoreCase(to.getTo())
                                                || dest.equalsIgnoreCase(toAddy)
                                            ) {
                                                Package pk=ef.getEmailAddress().getDomain().getPackage();
                                                int index=forwardingPackages.indexOf(pk);
                                                if(index==-1) {
                                                    forwardingPackages.add(pk);
                                                    index=forwardingPackages.indexOf(pk);
                                                    forwardingCounts.add(index, 1);
                                                } else {
                                                    forwardingCounts.set(index, forwardingCounts.getInt(index)+1);
                                                }
                                                forwardingTotal++;
                                            }
                                        }
                                        if(forwardingTotal>0) {
                                            // 8) Match to as the destination of an email forwarding, random and weighted by
                                            //    package probability
                                            int randNum=AOServDaemon.getRandom().nextInt(forwardingTotal);
                                            int pos=0;
                                            int index=-1;
                                            for(int c=0;c<forwardingCounts.size();c++) {
                                                pos+=forwardingCounts.getInt(c);
                                                if(pos>randNum) {
                                                    index=c;
                                                    break;
                                                }
                                            }
                                            if(index==-1) throw new RuntimeException("index is -1");
                                            addToPackage(forwardingPackages.get(index), to.getDate(), from==null?DEFAULT_EMAIL_SIZE:from.getSize(), true, true);
                                        } else {
                                            // 9) Log as error and credit to AO
                                            //if(from!=null) AOServDaemon.reportErrorMessage("SmtpStatManager: Unable to determine email owner for message ID "+to.getMessageID());
                                            System.err.println("SmtpStatManager: Unable to determine email owner: from=\""+from+"\", to=\""+to+"\" - Adding to "+rootAccounting);
                                            addToPackage(conn.packages.get(rootAccounting), to.getDate(), from==null?DEFAULT_EMAIL_SIZE:from.getSize(), true, true);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch(IOException err) {
                     AOServDaemon.reportError(err, null);
                } catch(SQLException err) {
                     AOServDaemon.reportError(err, null);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static EmailDomain getEmailDomainForAddress(AOServer ao, String addy) throws IOException, SQLException {
        if(addy==null) return null;
        int pos=addy.lastIndexOf('@');
        if(pos==-1) return null;
        return ao.getEmailDomain(addy.substring(pos+1).toLowerCase());
    }

    private static void addToPackage(Package pack, java.util.Date date, int totalSize, boolean isIn, boolean isOut) {
        Profiler.startProfile(Profiler.FAST, SmtpStatManager.class, "addToPackage(Package,Date,int,boolean,boolean)", null);
        try {
            // Lock on the package
            Object packageLock;
            synchronized(packageLocksLock) {
                packageLock=packageLocks.get(pack);
                if(packageLock==null) packageLocks.put(pack, packageLock=new Object());
            }
            synchronized(packageLock) {
                // Add to the email counter
                Map<java.util.Date,Integer> dateHashIC=emailInCounts.get(pack);
                if(dateHashIC==null) emailInCounts.put(pack, dateHashIC=new HashMap<java.util.Date,Integer>());
                Integer I=dateHashIC.get(date);
                dateHashIC.put(date, Integer.valueOf((I==null?0:I.intValue())+(isIn?1:0)));

                // Add to the bandwidth counter
                Map<java.util.Date,Long> dateHashIB=emailInBandwidths.get(pack);
                if(dateHashIB==null) emailInBandwidths.put(pack, dateHashIB=new HashMap<java.util.Date,Long>());
                Long L=dateHashIB.get(date);
                dateHashIB.put(date, Long.valueOf((L==null?0l:L.longValue())+(isIn?totalSize:0)));

                // Add to the email counter
                Map<java.util.Date,Integer> dateHashOC=emailOutCounts.get(pack);
                if(dateHashOC==null) emailOutCounts.put(pack, dateHashOC=new HashMap<java.util.Date,Integer>());
                I=dateHashOC.get(date);
                dateHashOC.put(date, Integer.valueOf((I==null?0:I.intValue())+(isOut?1:0)));

                // Add to the bandwidth counter
                Map<java.util.Date,Long> dateHashOB=emailOutBandwidths.get(pack);
                if(dateHashOB==null) emailOutBandwidths.put(pack, dateHashOB=new HashMap<java.util.Date,Long>());
                L=dateHashOB.get(date);
                dateHashOB.put(date, Long.valueOf((L==null?0l:L.longValue())+(isOut?totalSize:0)));
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}