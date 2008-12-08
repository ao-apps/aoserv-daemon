package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailSpamAssassinIntegrationMode;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.ErrorPrinter;
import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.ACL;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.Rights;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.IMAPResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Session;

/**
 * Any IMAP/Cyrus-specific features are here.
 *
 * Test account 1:
 *     hostname: 192.168.1.12
 *     username: cyrus.test@suspendo.aoindustries.com
 *     password: Clusk48Kulp
 * Test account 2:
 *     hostname: 192.168.1.12
 *     username: cyrus.test2
 *     password: Eflay43Klar
 * @author  AO Industries, Inc.
 */
final public class ImapManager extends BuilderThread {

    public static final File mailSpool=new File("/var/spool/mail");

    private static ImapManager imapManager;

    private ImapManager() {
    }

    private static final Object _sessionLock = new Object();
    private static Session _session;

    /**
     * Gets the Session used for admin control.
     */
    private static Session getSession() throws IOException, SQLException {
        synchronized(_sessionLock) {
            if(_session==null) {
                // Create and cache new session
                Properties props = new Properties();
                props.put("mail.store.protocol", "imap");
                props.put("mail.transport.protocol", "smtp");
                props.put("mail.smtp.auth", "true");
                props.put("mail.from", "cyrus@"+AOServDaemon.getThisAOServer().getHostname());
                _session = Session.getInstance(props, null);
                //_session.setDebug(true);
            }
            return _session;
        }
    }

    private static final Object _storeLock = new Object();
    private static IMAPStore _store;

    /**
     * Gets the IMAPStore for admin control.
     */
    private static IMAPStore getStore() throws IOException, SQLException, MessagingException {
        synchronized(_storeLock) {
            if(_store==null) {
                // Get things that may failed externally before allocating session and store
                String host = AOServDaemon.getThisAOServer().getPrimaryIPAddress().getIPAddress();
                String user = LinuxAccount.CYRUS+"@default";
                String password = AOServDaemonConfiguration.getCyrusPassword();

                // Create and cache new store here
                IMAPStore newStore = (IMAPStore)getSession().getStore();
                newStore.connect(
                    host,
                    user,
                    password
                );
                _store = newStore;
            }
            return _store;
        }
    }

    /**
     * Closes IMAPStore.
     */
    private static void closeStore() {
        synchronized(_storeLock) {
            if(_store!=null) {
                try {
                    _store.close();
                } catch(MessagingException err) {
                    AOServDaemon.reportError(err, null);
                }
                _store = null;
            }
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException, MessagingException {
        synchronized(rebuildLock) {
            // TODO: Configure /etc/cyrus.conf
                // Require port 143 on primary IP, error if not there - used for admin connections
            // TODO: Configure /etc/imapd.conf
                // TODO: Configure certificates in /etc/pki/cyrus-imapd on a per-IP basis.
                //       file:///home/o/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
                //       value of "disabled" if the certificate file doesn't exist (or use server default)
                //       openssl req -new -x509 -nodes -out cyrus-imapd.pem -keyout cyrus-imapd.pem -days 3650
                //       Other automated certificate management, sendmail too?
            // TODO: Restart cyrus-imapd if needed

            rebuildUsers();

            // TODO: Future
            //     Control the synchronous mode for ext2/ext3 automatically?
            //         file:///home/o/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
            //         cd /var/imap
            //         chattr +S user quota user/* quota/*
            //         chattr +S /var/spool/imap /var/spool/imap/*
            //         chattr +S /var/spool/mqueue
            //     sieve to replace procmail and allow more directly delivery
            //         sieveusehomedir
            //         sieveshell:
            //             sieveshell --authname=cyrus.test@suspendo.aoindustries.com 192.168.1.12
            //             /bin/su -s /bin/bash -c "/usr/bin/sieveshell 192.168.1.12" cyrus.test@suspendo.aoindustries.com
            //         sieve only listen on primary IP only (for chroot failover)
            //     Run chk_cyrus from NOC?
            //     Backups:
            //           stop master, snapshot, start master
            //           Or, without snapshots, do ctl_mboxlist -d
            //               http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/Backup
            //           Also, don't back-up Junk folders?
            //     Add smmapd support
            //     Consider http://en.wikipedia.org/wiki/Application_Configuration_Access_Protocol or http://en.wikipedia.org/wiki/Application_Configuration_Access_Protocol
        }
    }

    /**
     * Gets a folder name for the provided user, domain, and folder.
     * 
     * @param  user    the username (without any @ sign)
     * @param  domain  null if no domain
     * @param  folder  the folder or null for INBOX
     */
    private static String getFolderName(String user, String domain, String folder) {
        StringBuilder sb = new StringBuilder();
        sb.append("user/").append(user);
        if(folder.length()>0) sb.append('/').append(folder);
        if(domain!=null) sb.append('@').append(domain);
        return sb.toString();
    }

    /**
     * Rebuild the ACL on a folder, both creating missing and removing extra rights.
     * 
     * http://www.faqs.org/rfcs/rfc2086.html
     * 
     * @param  folder  the IMAPFolder to verify the ACL on
     * @param  user    the username (without any @ sign)
     * @param  domain  null if no domain
     * @param  rights  the rights, one character to right
     */
    private static void rebuildAcl(IMAPFolder folder, String user, String domain, Rights rights) throws MessagingException {
        // Determine the username
        String username = domain==null ? user : (user+'@'+domain);

        ACL userAcl = null;
        for(ACL acl : folder.getACL()) {
            if(acl.getName().equals(username)) {
                userAcl = acl;
                break;
            }
        }
        if(userAcl==null) {
            ACL newAcl = new ACL(username, new Rights(rights));
            folder.addACL(newAcl);
        } else {
            // Verify rights
            Rights aclRights = userAcl.getRights();
            
            // Look for missing
            if(!aclRights.contains(rights)) {
                // Build the set of rights that are missing
                Rights missingRights = new Rights();
                for(Rights.Right right : rights.getRights()) {
                    if(!aclRights.contains(right)) missingRights.add(right);
                }
                userAcl.setRights(missingRights);
                folder.addRights(userAcl);
            }
            if(!rights.contains(aclRights)) {
                // Build the set of rights that are extra
                Rights extraRights = new Rights();
                for(Rights.Right right : aclRights.getRights()) {
                    if(!rights.contains(right)) extraRights.add(right);
                }
                userAcl.setRights(extraRights);
                folder.removeRights(userAcl);
            }
        }
    }

    /**
     * Gets the Cyrus user part of a username.
     * 
     * @see  #getDomain
     */
    private static String getUser(String username) {
        int atPos = username.indexOf('@');
        return (atPos==-1) ? username : username.substring(0, atPos);
    }
    
    /**
     * Gets the Cyrus domain part of a username or <code>null</code> for no domain.
     * 
     * @see  #getUser
     */
    private static String getDomain(String username) {
        int atPos = username.indexOf('@');
        return (atPos==-1) ? null : username.substring(atPos+1);
    }

    private static void rebuildUsers() throws IOException, SQLException, MessagingException {
        try {
            // Connect to the store
            IMAPStore store = getStore();

            // Make sure the user folder exists
            IMAPFolder userFolder = (IMAPFolder)store.getFolder("user");
            try {
                if(!userFolder.exists()) throw new MessagingException("Folder doesn't exist: user");

                // Verify all email users - only users who have a home under /home/ are considered
                for(LinuxServerAccount lsa : AOServDaemon.getThisAOServer().getLinuxServerAccounts()) {
                    LinuxAccount la = lsa.getLinuxAccount();
                    String homePath = lsa.getHome();
                    if(la.getType().isEmail() && homePath.startsWith("/home/")) {
                        // Split into user and domain
                        String laUsername = la.getUsername().getUsername();
                        String user = getUser(laUsername);
                        String domain = getDomain(laUsername);

                        // INBOX
                        String inboxFolderName = getFolderName(user, domain, "");
                        IMAPFolder inboxFolder = (IMAPFolder)store.getFolder(inboxFolderName);
                        try {
                            if(!inboxFolder.exists() && !inboxFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                throw new MessagingException("Unable to create folder: "+inboxFolder.getFullName());
                            }
                            rebuildAcl(inboxFolder, LinuxAccount.CYRUS, null, new Rights("ckrx"));
                            rebuildAcl(inboxFolder, user, domain, new Rights("acdeiklprstwx"));
                        } finally {
                            if(inboxFolder.isOpen()) inboxFolder.close(false);
                        }

                        // Trash
                        String trashFolderName = getFolderName(user, domain, "Trash");
                        IMAPFolder trashFolder = (IMAPFolder)store.getFolder(trashFolderName);
                        try {
                            if(!trashFolder.exists() && !trashFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                throw new MessagingException("Unable to create folder: "+trashFolder.getFullName());
                            }
                            rebuildAcl(trashFolder, LinuxAccount.CYRUS, null, new Rights("ckrx"));
                            rebuildAcl(trashFolder, user, domain, new Rights("acdeiklprstwx"));
                        } finally {
                            if(trashFolder.isOpen()) trashFolder.close(false);
                        }
                        
                        if(!lsa.getEmailSpamAssassinIntegrationMode().getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
                            // Junk
                            String junkFolderName = getFolderName(user, domain, "Junk");
                            IMAPFolder junkFolder = (IMAPFolder)store.getFolder(junkFolderName);
                            try {
                                if(!junkFolder.exists() && !junkFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                    throw new MessagingException("Unable to create folder: "+junkFolder.getFullName());
                                }
                                rebuildAcl(junkFolder, LinuxAccount.CYRUS, null, new Rights("ckrx"));
                                rebuildAcl(junkFolder, user, domain, new Rights("acdeiklprstwx"));
                            } finally {
                                if(junkFolder.isOpen()) junkFolder.close(false);
                            }
                        }

                        // TODO: Auto-expunge every mailbox once per day - cyr_expire?
                            //         Set /vendor/cmu/cyrus-imapd/expire for Junk and Trash - see man cyr_expire
                            //         http://lists.andrew.cmu.edu/pipermail/cyrus-devel/2007-June/000331.html
                            //       http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/MboxExpire
                            //         mboxcfg INBOX/SPAM expire 10
                            //         info INBOX/SPAM
                            //         or: imap.setannotation('INBOX/SPAM', '/vendor/cmu/cyrus-imapd/expire',
                            //       192.168.1.12> mboxcfg "Junk" expire 31
                            //       192.168.1.12> info "Junk"
                            //       {Junk}:
                            //       condstore: false
                            //       expire: 31
                            //       lastpop:
                            //       lastupdate:  7-Dec-2008 07:25:19 -0600
                            //       partition: default
                            //       size: 0
                        // TODO: Convert old folders from UW software
                            //           And control .mailboxlist, too.
                            //           http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/MboxCyrusMigration
                            //           chown to root as completed - do not deleted until later
                            //           Could we run old IMAP software on a different port and do JavaMail-based copies?
                            //           imapsync: http://www.zimbra.com/forums/migration/6487-mailbox-size-extremely-large.html
                    }
                }
                // TODO: Remove extra cyrus users (try from IMAP)
                    //       deletemailbox user/cyrus.test@suspendo.aoindustries.com
            } finally {
                if(userFolder.isOpen()) userFolder.close(false);
            }
        } catch(RuntimeException err) {
            closeStore();
            throw err;
        } catch(IOException err) {
            closeStore();
            throw err;
        } catch(SQLException err) {
            closeStore();
            throw err;
        } catch(MessagingException err) {
            closeStore();
            throw err;
        }
    }

    /**
     * TODO: Remove after testing, or move to JUnit tests.
     */
    public static void main(String[] args) {
        try {
            //rebuildUsers();

            for(LinuxServerAccount lsa : AOServDaemon.getThisAOServer().getLinuxServerAccounts()) {
                LinuxAccount la = lsa.getLinuxAccount();
                if(la.getType().isEmail() && lsa.getHome().startsWith("/home/")) {
                    String username = la.getUsername().getUsername();
                    System.out.println(username+": "+getInboxSize(username));
                    DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.US);
                    System.out.println(username+": "+dateFormat.format(new Date(getInboxModified(username))));
                }
            }
        } catch(Exception err) {
            ErrorPrinter.printStackTraces(err);
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
                AOServConnector connector=AOServDaemon.getConnector();
                imapManager=new ImapManager();
                connector.aoServers.addTableListener(imapManager, 0);
                connector.ipAddresses.addTableListener(imapManager, 0);
                connector.linuxAccounts.addTableListener(imapManager, 0);
                connector.linuxServerAccounts.addTableListener(imapManager, 0);
                connector.netBinds.addTableListener(imapManager, 0);
                connector.servers.addTableListener(imapManager, 0);
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

    static class Annotation {
        private final String mailboxName;
        private final String entry;
        private final Map<String,String> attributes;
        
        Annotation(String mailboxName, String entry, Map<String,String> attributes) {
            this.mailboxName = mailboxName;
            this.entry = entry;
            this.attributes = attributes;
        }
        
        String getMailboxName() {
            return mailboxName;
        }
        
        String getEntry() {
            return entry;
        }
        
        String getAttribute(String attributeSpecifier) {
            return attributes.get(attributeSpecifier);
        }
    }

    /**
     * Gets all of the annotations for the provided folder, entry, and attribute.
     * 
     * This uses ANNOTATEMORE
     *     Current: http://vman.de/cyrus/draft-daboo-imap-annotatemore-07.html
     *     Newer:   http://vman.de/cyrus/draft-daboo-imap-annotatemore-10.html
     *
     * ad GETANNOTATION user/cyrus.test/Junk@suspendo.aoindustries.com "/vendor/cmu/cyrus-imapd/size" "value.shared"
     * ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/size" ("value.shared" "33650")
     * ad OK Completed
     * 
     * http://java.sun.com/products/javamail/javadocs/com/sun/mail/imap/IMAPFolder.html#doCommand(com.sun.mail.imap.IMAPFolder.ProtocolCommand)
     */
    @SuppressWarnings({"unchecked"})
    private static List<Annotation> getAnnotations(IMAPFolder folder, final String entry, final String attribute) throws MessagingException {
        final String mailboxName = folder.getFullName();
        List<Annotation> annotations = (List)folder.doCommand(
            new IMAPFolder.ProtocolCommand() {
                public Object doCommand(IMAPProtocol p) throws ProtocolException {
                    // Issue command
                    Argument args = new Argument();
                    args.writeString(mailboxName);
                    args.writeString(entry);
                    args.writeString(attribute);

                    Response[] r = p.command("GETANNOTATION", args);
                    Response response = r[r.length-1];

                    // Grab response
                    List<Annotation> annotations = new ArrayList<Annotation>(r.length-1);
                    if (response.isOK()) { // command succesful 
                        for (int i = 0, len = r.length; i < len; i++) {
                            if (r[i] instanceof IMAPResponse) {
                                IMAPResponse ir = (IMAPResponse)r[i];
                                if (ir.keyEquals("ANNOTATION")) {
                                    String mailboxName = ir.readAtomString();
                                    String entry = ir.readAtomString();
                                    String[] list = ir.readStringList();
                                    // Must be even number of elements in list
                                    if((list.length&1)!=0) throw new ProtocolException("Uneven number of elements in attribute list: "+list.length);
                                    Map<String,String> attributes = new HashMap<String,String>(list.length*2/3+1);
                                    for(int j=0; j<list.length; j+=2) {
                                        attributes.put(list[j], list[j+1]);
                                    }
                                    annotations.add(new Annotation(mailboxName, entry, attributes));
                                    // Mark as handled
                                    r[i] = null;
                                }
                            }
                        }
                    } else {
                        throw new ProtocolException("Response is not OK: "+response);
                    }

                    // dispatch remaining untagged responses
                    p.notifyResponseHandlers(r);
                    p.handleResult(response);

                    return annotations;
                }
            }
        );
        return annotations;
    }

    /**
     * Gets a single, specific annotation (specific in mailbox-name, entry-specifier, and attribute-specifier).
     * 
     * @return  the value if found or <code>null</code> if unavailable
     */
    private static String getAnnotation(IMAPFolder folder, String entry, String attribute) throws MessagingException {
        String folderName = folder.getFullName();
        List<Annotation> annotations = getAnnotations(folder, entry, attribute);
        for(Annotation annotation : annotations) {
            if(
                annotation.getMailboxName().equals(folderName)
                && annotation.getEntry().equals(entry)
            ) {
                // Look for the "value.shared" attribute
                String value = annotation.getAttribute(attribute);
                if(value!=null) return value;
            }
        }
        // Not found
        return null;
    }

    public static long getInboxSize(String username) throws IOException, SQLException, MessagingException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new File(mailSpool, username).length();
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            try {
                // Connect to the store
                IMAPStore store = getStore();
                String user = getUser(username);
                String domain = getDomain(username);
                String inboxFolderName = getFolderName(user, domain, "");
                IMAPFolder inboxFolder = (IMAPFolder)store.getFolder(inboxFolderName);
                try {
                    String value = getAnnotation(inboxFolder, "/vendor/cmu/cyrus-imapd/size", "value.shared");
                    if(value!=null) return Long.parseLong(value);
                    throw new MessagingException("username="+username+": \"/vendor/cmu/cyrus-imapd/size\" \"value.shared\" annotation not found");
                } finally {
                    if(inboxFolder.isOpen()) inboxFolder.close(false);
                }
            } catch(RuntimeException err) {
                closeStore();
                throw err;
            } catch(IOException err) {
                closeStore();
                throw err;
            } catch(SQLException err) {
                closeStore();
                throw err;
            } catch(MessagingException err) {
                closeStore();
                throw err;
            }
            /*
ad GETANNOTATION user/cyrus.test/Junk@suspendo.aoindustries.com "*" "value.shared"
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/expire" ("value.shared" "31")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/condstore" ("value.shared" "false")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/lastpop" ("value.shared" " ")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/lastupdate" ("value.shared" " 7-Dec-2008 20:36:25 -0600")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/size" ("value.shared" "33650")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/partition" ("value.shared" "default")
ad OK Completed
*/
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }

    public static long getInboxModified(String username) throws IOException, SQLException, MessagingException, ParseException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new File(mailSpool, username).lastModified();
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            try {
                // Connect to the store
                IMAPStore store = getStore();
                String user = getUser(username);
                String domain = getDomain(username);
                String inboxFolderName = getFolderName(user, domain, "");
                IMAPFolder inboxFolder = (IMAPFolder)store.getFolder(inboxFolderName);
                try {
                    String value = getAnnotation(inboxFolder, "/vendor/cmu/cyrus-imapd/lastupdate", "value.shared");
                    if(value==null) throw new MessagingException("username="+username+": \"/vendor/cmu/cyrus-imapd/lastupdate\" \"value.shared\" annotation not found");

                    // Parse values
                    // 8-Dec-2008 00:24:30 -0600
                    value = value.trim();
                    // Day
                    int hyphen1 = value.indexOf('-');
                    if(hyphen1==-1) throw new ParseException("Can't find first -", 0);
                    int day = Integer.parseInt(value.substring(0, hyphen1));
                    // Mon
                    int hyphen2 = value.indexOf('-', hyphen1+1);
                    if(hyphen2==-1) throw new ParseException("Can't find second -", hyphen1+1);
                    String monthString = value.substring(hyphen1+1, hyphen2);
                    int month;
                    if("Jan".equals(monthString)) month = Calendar.JANUARY;
                    else if("Feb".equals(monthString)) month = Calendar.FEBRUARY;
                    else if("Mar".equals(monthString)) month = Calendar.MARCH;
                    else if("Apr".equals(monthString)) month = Calendar.APRIL;
                    else if("May".equals(monthString)) month = Calendar.MAY;
                    else if("Jun".equals(monthString)) month = Calendar.JUNE;
                    else if("Jul".equals(monthString)) month = Calendar.JULY;
                    else if("Aug".equals(monthString)) month = Calendar.AUGUST;
                    else if("Sep".equals(monthString)) month = Calendar.SEPTEMBER;
                    else if("Oct".equals(monthString)) month = Calendar.OCTOBER;
                    else if("Nov".equals(monthString)) month = Calendar.NOVEMBER;
                    else if("Dec".equals(monthString)) month = Calendar.DECEMBER;
                    else throw new ParseException("Unexpected month: "+monthString, hyphen1+1);
                    // Year
                    int space1 = value.indexOf(' ', hyphen2+1);
                    if(space1==-1) throw new ParseException("Can't find first space", hyphen2+1);
                    int year = Integer.parseInt(value.substring(hyphen2+1, space1));
                    // Hour
                    int colon1 = value.indexOf(':', space1+1);
                    if(colon1==-1) throw new ParseException("Can't find first colon", space1+1);
                    int hour = Integer.parseInt(value.substring(space1+1, colon1));
                    // Minute
                    int colon2 = value.indexOf(':', colon1+1);
                    if(colon2==-1) throw new ParseException("Can't find second colon", colon1+1);
                    int minute = Integer.parseInt(value.substring(colon1+1, colon2));
                    // Second
                    int space2 = value.indexOf(' ', colon2+1);
                    if(space2==-1) throw new ParseException("Can't find second space", colon2+1);
                    int second = Integer.parseInt(value.substring(colon2+1, space2));
                    // time zone
                    int zoneHours = Integer.parseInt(value.substring(space2+1, value.length()-2));
                    int zoneMinutes = Integer.parseInt(value.substring(value.length()-2));
                    if(zoneHours<0) zoneMinutes = -zoneMinutes;

                    // Convert to correct time
                    Calendar cal = Calendar.getInstance(Locale.US);
                    cal.set(Calendar.ZONE_OFFSET, zoneHours*60*60*1000 + zoneMinutes*60*1000);
                    cal.set(Calendar.YEAR, year);
                    cal.set(Calendar.MONTH, month);
                    cal.set(Calendar.DAY_OF_MONTH, day);
                    cal.set(Calendar.HOUR_OF_DAY, hour);
                    cal.set(Calendar.MINUTE, minute);
                    cal.set(Calendar.SECOND, second);
                    cal.set(Calendar.MILLISECOND, 0);
                    return cal.getTimeInMillis();
                } finally {
                    if(inboxFolder.isOpen()) inboxFolder.close(false);
                }
            } catch(RuntimeException err) {
                closeStore();
                throw err;
            } catch(IOException err) {
                closeStore();
                throw err;
            } catch(SQLException err) {
                closeStore();
                throw err;
            } catch(MessagingException err) {
                closeStore();
                throw err;
            }
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }
}
