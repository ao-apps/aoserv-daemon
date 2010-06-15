package com.aoindustries.aoserv.daemon.ftp;

/*
 * Copyright 2001-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.FtpGuestUser;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetProtocol;
import com.aoindustries.aoserv.client.NetTcpRedirect;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PrivateFtpServer;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Handles the building of FTP configs and files.
 */
final public class FTPManager extends BuilderThread {

    private static final UnixFile
        newProFtpdConf=new UnixFile("/etc/proftpd.conf.new"),
        proFtpdConf=new UnixFile("/etc/proftpd.conf")
    ;

    private static final UnixFile
        vsFtpdConfNew = new UnixFile("/etc/vsftpd/vsftpd.conf.new"),
        vsFtpdConf = new UnixFile("/etc/vsftpd/vsftpd.conf"),
        vsFtpdVhostsirectory = new UnixFile("/etc/vsftpd/vhosts"),
        vsFtpdChrootList = new UnixFile("/etc/vsftpd/chroot_list"),
        vsFtpdChrootListNew = new UnixFile("/etc/vsftpd/chroot_list.new")
    ;

    /**
     * The directory that is used for site-independant FTP access.
     */
    private static final UnixFile sharedFtpDirectory = new UnixFile("/var/ftp/pub");

    private static FTPManager ftpManager;

    private FTPManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            synchronized(rebuildLock) {
                if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    doRebuildVsFtpd();
                } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

                doRebuildSharedFtpDirectory();
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(FTPManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    /**
     * Rebuilds a vsftpd installation.
     */
    private static void doRebuildVsFtpd() throws IOException {
        AOServConnector<?,?> conn=AOServDaemon.getConnector();
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            // Reused below
            final Stat tempStat = new Stat();
            final ByteArrayOutputStream bout = new ByteArrayOutputStream();

            // Jailed users
            {
                bout.reset();
                ChainWriter out = new ChainWriter(bout);
                try {
                    for(FtpGuestUser ftpGuestUser : new TreeSet<FtpGuestUser>(thisAOServer.getFtpGuestUsers())) {
                        out.print(ftpGuestUser.getLinuxAccount().getUsername().getUsername()).print('\n');
                    }
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Only write to filesystem if missing or changed
                if(!vsFtpdChrootList.getStat(tempStat).exists() || !vsFtpdChrootList.contentEquals(newBytes)) {
                    FileOutputStream newOut = vsFtpdChrootListNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    try {
                        newOut.write(newBytes);
                    } finally {
                        newOut.close();
                    }
                    vsFtpdChrootListNew.renameTo(vsFtpdChrootList);
                }
            }

            // Write the default config file
            {
                bout.reset();
                ChainWriter out = new ChainWriter(bout);
                try {
                    out.print("# BOOLEAN OPTIONS\n"
                            + "anonymous_enable=YES\n"
                            + "async_abor_enable=YES\n"
                            + "chroot_list_enable=YES\n"
                            + "connect_from_port_20=YES\n"
                            + "dirmessage_enable=YES\n"
                            + "hide_ids=YES\n"
                            + "local_enable=YES\n"
                            + "ls_recurse_enable=NO\n"
                            + "text_userdb_names=NO\n"
                            + "userlist_enable=YES\n"
                            + "write_enable=YES\n"
                            + "xferlog_enable=YES\n"
                            + "xferlog_std_format=YES\n"
                            + "\n"
                            + "# NUMERIC OPTIONS\n"
                            + "accept_timeout=60\n"
                            + "anon_max_rate=125000\n"
                            + "connect_timeout=60\n"
                            + "data_connection_timeout=7200\n"
                            + "idle_session_timeout=7200\n"
                            + "local_umask=002\n"
                            + "pasv_max_port=50175\n"
                            + "pasv_min_port=49152\n"
                            + "\n"
                            + "# STRING OPTIONS\n"
                            + "chroot_list_file=/etc/vsftpd/chroot_list\n"
                            + "ftpd_banner=FTP Server [").print(thisAOServer.getHostname()).print("]\n"
                            + "pam_service_name=vsftpd\n");
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Only write to filesystem if missing or changed
                if(!vsFtpdConf.getStat(tempStat).exists() || !vsFtpdConf.contentEquals(newBytes)) {
                    FileOutputStream newOut = vsFtpdConfNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    try {
                        newOut.write(newBytes);
                    } finally {
                        newOut.close();
                    }
                    vsFtpdConfNew.renameTo(vsFtpdConf);
                }
            }

            // Specific net_binds
            {
                // Make the vhosts directory if it doesn't exist
                if(!vsFtpdVhostsirectory.getStat(tempStat).exists()) {
                    vsFtpdVhostsirectory.mkdir(false, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
                }
                
                // Find all the FTP binds
                SortedSet<NetBind> binds = new TreeSet<NetBind>(thisAOServer.getServer().getNetBinds(conn.getProtocols().get(Protocol.FTP)));

                // Keep a list of the files that were verified
                Set<String> existing = new HashSet<String>(binds.size()*4/3+1);

                // Write each config file
                for(NetBind bind : binds) {
                    NetTcpRedirect redirect=bind.getNetTcpRedirect();
                    PrivateFtpServer privateServer=bind.getPrivateFtpServer();
                    if(redirect!=null) {
                        if(privateServer!=null) throw new AssertionError("NetBind allocated as both NetTcpRedirect and PrivateFtpServer: "+bind.getPkey());
                    } else {
                        String netProtocol=bind.getNetProtocol().getProtocol();
                        if(!netProtocol.equals(NetProtocol.TCP)) throw new AssertionError("vsftpd may only be configured for TCP service:  (net_binds.pkey="+bind.getPkey()+").net_protocol="+netProtocol);
                        IPAddress ia=bind.getIpAddress();

                        // Write to buffer
                        bout.reset();
                        ChainWriter out = new ChainWriter(bout);
                        try {
                            out.print("# BOOLEAN OPTIONS\n"
                                    + "anonymous_enable=").print(privateServer==null || privateServer.allowAnonymous() ? "YES" : "NO").print("\n"
                                    + "async_abor_enable=YES\n"
                                    + "chroot_list_enable=YES\n"
                                    + "connect_from_port_20=YES\n"
                                    + "dirmessage_enable=YES\n"
                                    + "hide_ids=").print(privateServer==null || privateServer.allowAnonymous() ? "YES" : "NO").print("\n"
                                    + "local_enable=YES\n"
                                    + "ls_recurse_enable=NO\n"
                                    + "text_userdb_names=").print(privateServer==null || privateServer.allowAnonymous() ? "NO" : "YES").print("\n"
                                    + "userlist_enable=YES\n"
                                    + "write_enable=YES\n"
                                    + "xferlog_enable=YES\n"
                                    + "xferlog_std_format=YES\n"
                                    + "\n"
                                    + "# NUMERIC OPTIONS\n"
                                    + "accept_timeout=60\n"
                                    + "anon_max_rate=125000\n"
                                    + "connect_timeout=60\n"
                                    + "data_connection_timeout=7200\n"
                                    + "idle_session_timeout=7200\n"
                                    + "local_umask=002\n"
                                    + "pasv_max_port=50175\n"
                                    + "pasv_min_port=49152\n"
                                    + "\n"
                                    + "# STRING OPTIONS\n"
                                    + "chroot_list_file=/etc/vsftpd/chroot_list\n");
                            if(privateServer!=null) {
                                out.print("ftp_username=").print(privateServer.getLinuxAccountGroup().getLinuxAccount().getUsername().getUsername()).print('\n');
                            }
                            out
                                .print("ftpd_banner=FTP Server [")
                                .print(
                                    privateServer!=null?privateServer.getHostname()
                                    :ia==null?thisAOServer.getHostname().getDomain()
                                    :ia.getHostname().getDomain()
                                ).print("]\n"
                                    + "pam_service_name=vsftpd\n");
                            if(privateServer!=null) {
                                out.print("xferlog_file=").print(privateServer.getLogfile()).print('\n');
                            }
                        } finally {
                            out.close();
                        }
                        byte[] newBytes = bout.toByteArray();

                        // Only write to filesystem if missing or changed
                        String filename = "vsftpd_"+bind.getIpAddress().getIpAddress().getAddress()+"_"+bind.getPort().getPort()+".conf";
                        if(!existing.add(filename)) throw new AssertionError("Filename already used: "+filename);
                        UnixFile confFile = new UnixFile(vsFtpdVhostsirectory, filename, false);
                        if(!confFile.getStat(tempStat).exists() || !confFile.contentEquals(newBytes)) {
                            UnixFile newConfFile = new UnixFile(vsFtpdVhostsirectory, filename+".new", false);
                            FileOutputStream newOut = newConfFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                            try {
                                newOut.write(newBytes);
                            } finally {
                                newOut.close();
                            }
                            newConfFile.renameTo(confFile);
                        }
                    }
                }

                // Clean the vhosts directory
                for(String filename : vsFtpdVhostsirectory.list()) {
                    if(!existing.contains(filename)) {
                        new UnixFile(vsFtpdVhostsirectory, filename, false).delete();
                    }
                }
            }
        } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
    }

    /**
     * Rebuilds the contents of /var/cvs  Each site optinally gets its own
     * shared FTP space.
     */
    private static void doRebuildSharedFtpDirectory() throws IOException, ValidationException {
        List<File> deleteFileList=new ArrayList<File>();

        String[] list = sharedFtpDirectory.list();
        Set<DomainName> ftpDirectories = new HashSet<DomainName>(list.length*4/3+1);
        for(int c=0;c<list.length;c++) ftpDirectories.add(DomainName.valueOf(list[c]));
        
        for(HttpdSite httpdSite : AOServDaemon.getThisAOServer().getHttpdSites()) {
            HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);

            /*
             * Make the private FTP space, if needed.
             */
            if(manager.enableAnonymousFtp()) {
                DomainName siteName = httpdSite.getSiteName();
                manager.configureFtpDirectory(new UnixFile(sharedFtpDirectory, siteName.getDomain(), false));
                ftpDirectories.remove(siteName);
            }
        }

        File sharedFtpDirectoryFile = sharedFtpDirectory.getFile();
        for(DomainName filename : ftpDirectories) deleteFileList.add(new File(sharedFtpDirectoryFile, filename.getDomain()));

        // Back-up and delete the files scheduled for removal
        BackupManager.backupAndDeleteFiles(deleteFileList);
    }

    public static void start() throws IOException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(FTPManager.class)
                && ftpManager==null
            ) {
                System.out.print("Starting FTPManager: ");
                AOServConnector<?,?> conn=AOServDaemon.getConnector();
                ftpManager=new FTPManager();
                conn.getFtpGuestUsers().getTable().addTableListener(ftpManager, 0);
                conn.getHttpdSites().getTable().addTableListener(ftpManager, 0);
                conn.getIpAddresses().getTable().addTableListener(ftpManager, 0);
                conn.getLinuxAccounts().getTable().addTableListener(ftpManager, 0);
                conn.getLinuxAccountGroups().getTable().addTableListener(ftpManager, 0);
                conn.getNetBinds().getTable().addTableListener(ftpManager, 0);
                conn.getPrivateFtpServers().getTable().addTableListener(ftpManager, 0);
                conn.getUsernames().getTable().addTableListener(ftpManager, 0);
                System.out.println("Done");
            }
        }
    }

    /**
     * Removes any file in the directory that is not listed in <code>files</code>.
     */
    public static void trimFiles(UnixFile dir, String[] files) throws IOException {
        String[] list=dir.list();
        if(list!=null) {
            final Stat tempStat = new Stat();
            int len=list.length;
            int flen=files.length;
            for(int c=0;c<len;c++) {
                String filename=list[c];
                boolean found=false;
                for(int d=0;d<flen;d++) {
                    if(filename.equals(files[d])) {
                        found=true;
                        break;
                    }
                }
                if(!found) {
                    UnixFile file=new UnixFile(dir, filename, false);
                    if(file.getStat(tempStat).exists()) file.delete();
                }
            }
        }
    }

    /**
     * Removes any file in the directory that is not listed in <code>files</code>.
     */
    public static void trimFiles(UnixFile dir, List<String> files) throws IOException {
        String[] SA=new String[files.size()];
        files.toArray(SA);
        trimFiles(dir, SA);
    }

    public String getProcessTimerDescription() {
        return "Rebuild FTP";
    }
}
