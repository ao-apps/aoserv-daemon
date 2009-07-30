package com.aoindustries.aoserv.daemon.backup;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.backup.UnixFileEnvironment;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.BackupPartition;
import com.aoindustries.aoserv.client.FailoverFileReplication;
import com.aoindustries.aoserv.client.FailoverMySQLReplication;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.io.FileExistsRule;
import com.aoindustries.io.FilesystemIteratorRule;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An <code>AOServerEnvironment</code> controls the backup system on
 * an <code>AOServer</code>.
 * 
 * TODO: Save bandwidth by doing prelink -u --undo-output=(tmpfile) (do this to read the file instead of direct I/O).
 *       Can possibly use the distro data to know which ones are prelinked.
 * 
 * TODO: Use LVM snapshots when is a domU - also do MySQL lock to get steady-state snapshot
 * 
 * TODO: Should we use some tricky stuff to dump the databases straight out as we iterate?  (Backups only)
 * TODO: Or, just dump to disk and remove when completed?  (Backups only)
 * 
 * TODO: Adhere to the d attribute?  man chattr
 *
 * @see  AOServer
 *
 * @author  AO Industries, Inc.
 */
public class AOServerEnvironment extends UnixFileEnvironment {

    @Override
    public AOServConnector getConnector() throws IOException, SQLException {
        return AOServDaemon.getConnector();
    }

    @Override
    public Server getThisServer() throws IOException, SQLException {
        return AOServDaemon.getThisAOServer().getServer();
    }

    @Override
    public void preBackup(FailoverFileReplication ffr) throws IOException, SQLException {
        super.preBackup(ffr);
        BackupManager.cleanVarOldaccounts();

        // TODO: BackupManager.backupMySQLDatabases();

        // TODO: BackupManager.backupPostgresDatabases();
    }

    private final Map<FailoverFileReplication,List<String>> replicatedMySQLServerses = new HashMap<FailoverFileReplication,List<String>>();
    private final Map<FailoverFileReplication,List<String>> replicatedMySQLMinorVersionses = new HashMap<FailoverFileReplication,List<String>>();

    @Override
    public void init(FailoverFileReplication ffr) throws IOException, SQLException {
        super.init(ffr);
        // Determine which MySQL Servers are replicated (not mirrored with failover code)
        short retention = ffr.getRetention().getDays();
        if(retention==1) {
            AOServer toServer = ffr.getBackupPartition().getAOServer();
            List<FailoverMySQLReplication> fmrs = ffr.getFailoverMySQLReplications();
            List<String> replicatedMySQLServers = new ArrayList<String>(fmrs.size());
            List<String> replicatedMySQLMinorVersions = new ArrayList<String>(fmrs.size());
            Logger logger = getLogger();
            boolean isDebug = logger.isLoggable(Level.FINE);
            for(FailoverMySQLReplication fmr : fmrs) {
                MySQLServer mysqlServer = fmr.getMySQLServer();
                String name = mysqlServer.getName();
                String minorVersion = mysqlServer.getMinorVersion();
                replicatedMySQLServers.add(name);
                replicatedMySQLMinorVersions.add(minorVersion);
                if(isDebug) {
                    logger.logp(Level.FINE, getClass().getName(), "init", "runFailoverCopy to "+toServer+", replicatedMySQLServer: "+name);
                    logger.logp(Level.FINE, getClass().getName(), "init", "runFailoverCopy to "+toServer+", replicatedMySQLMinorVersion: "+minorVersion);
                }
            }
            synchronized(replicatedMySQLServerses) {
                replicatedMySQLServerses.put(ffr, replicatedMySQLServers);
            }
            synchronized(replicatedMySQLMinorVersionses) {
                replicatedMySQLMinorVersionses.put(ffr, replicatedMySQLMinorVersions);
            }
        }
    }

    @Override
    public void cleanup(FailoverFileReplication ffr) throws IOException, SQLException {
        try {
            short retention = ffr.getRetention().getDays();
            if(retention==1) {
                synchronized(replicatedMySQLServerses) {
                    replicatedMySQLServerses.remove(ffr);
                }
                synchronized(replicatedMySQLMinorVersionses) {
                    replicatedMySQLMinorVersionses.remove(ffr);
                }
            }
        } finally {
            super.cleanup(ffr);
        }
    }

    @Override
    public int getFailoverBatchSize(FailoverFileReplication ffr) throws IOException, SQLException {
        return AOServDaemon.getThisAOServer().getFailoverBatchSize();
    }

    @Override
    protected Map<String,FilesystemIteratorRule> getFilesystemIteratorRules(FailoverFileReplication ffr) throws IOException, SQLException {
        AOServer thisServer = AOServDaemon.getThisAOServer();
        short retention = ffr.getRetention().getDays();
        Map<String,FilesystemIteratorRule> filesystemRules=new HashMap<String,FilesystemIteratorRule>();
        filesystemRules.put("", FilesystemIteratorRule.OK); // Default to being included unless explicitely excluded
        filesystemRules.put("/.journal", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/aquota.user", FilesystemIteratorRule.SKIP);
        //filesystemRules.put("/backup", FilesystemIteratorRule.NO_RECURSE);
        filesystemRules.put("/boot/.journal", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/boot/lost+found", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/dev/log", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/dev/pts/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/dev/shm/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/etc/mail/statistics", FilesystemIteratorRule.SKIP);

        // Don't send /etc/lilo.conf for failovers - it can cause severe problems if a nested server has its kernel RPMs upgraded
        if(retention==1) filesystemRules.put("/etc/lilo.conf", FilesystemIteratorRule.SKIP);

        filesystemRules.put(
            "/ao/",
            new FileExistsRule(
                new String[] {"/ao.aes128.img", "/ao.aes256.img", "/ao.copy.aes128.img", "/ao.copy.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put(
            "/ao.copy/",
            new FileExistsRule(
                new String[] {"/ao.aes128.img", "/ao.aes256.img", "/ao.copy.aes128.img", "/ao.copy.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put(
            "/encrypted/",
            new FileExistsRule(
                new String[] {"/encrypted.aes128.img", "/encrypted.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put(
            "/home/",
            new FileExistsRule(
                new String[] {"/home.aes128.img", "/home.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put(
            "/logs/",
            new FileExistsRule(
                new String[] {"/logs.aes128.img", "/logs.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put("/lost+found", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/mnt/cdrom", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/mnt/floppy", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/proc/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/selinux/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/swapfile.img", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/swapfile.aes128.img", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/swapfile.aes256.img", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/sys/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/tmp/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/usr/tmp/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/apache-mm/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/backup/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/backup1/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/backup2/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/backup3/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/backup4/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/failover/", FilesystemIteratorRule.SKIP);
        filesystemRules.put(
            "/var/cvs/",
            new FileExistsRule(
                new String[] {"/var/cvs.aes128.img", "/var/cvs.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put("/var/lib/mysql/.journal", FilesystemIteratorRule.SKIP);
        final String hostname = thisServer.getHostname();
        filesystemRules.put("/var/lib/mysql/4.0/"+hostname+".pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/mysql/4.1/"+hostname+".pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/mysql/5.0/"+hostname+".pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/mysql/5.1/"+hostname+".pid", FilesystemIteratorRule.SKIP);
        if(retention==1) {
            // Skip files for any MySQL Server that is being replicated through MySQL replication
            List<String> replicatedMySQLServers;
            synchronized(replicatedMySQLServerses) {
                replicatedMySQLServers = replicatedMySQLServerses.get(ffr);
            }
            for(String name : replicatedMySQLServers) {
                String path = "/var/lib/mysql/"+name;
                filesystemRules.put(path, FilesystemIteratorRule.SKIP);
                //if(log.isDebugEnabled()) log.debug("runFailoverCopy to "+toServer+", added skip rule for "+path);
            }
        }
        filesystemRules.put(
            "/var/lib/pgsql/",
            new FileExistsRule(
                new String[] {"/var/lib/pgsql.aes128.img", "/var/lib/pgsql.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put("/var/lib/pgsql/.journal", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/pgsql/7.1/postmaster.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/pgsql/7.2/postmaster.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/pgsql/7.3/postmaster.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/pgsql/8.0/postmaster.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/pgsql/8.1/postmaster.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/sasl2/saslauthd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lib/sasl2/mux.accept", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/aoserv-daemon", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/aoserv-jilter", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/clear_jvm_stats", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/clear_postgresql_pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/crond", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/daemons", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/kheader", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd1", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd2", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd3", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd4", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd5", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd6", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd7", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/httpd8", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/identd", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/local", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/messagebus", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/mysql-4.0", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/mysql-4.1", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/mysql-5.0", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/mysql-5.1", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/network", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/numlock", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/postgresql-7.1", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/postgresql-7.2", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/postgresql-7.3", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/postgresql-8.0", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/postgresql-8.1", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/proftpd", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/route", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/saslauthd", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/sendmail", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/sm-client", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/spamd", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/spamassassin", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/sshd1", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/syslog", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/xfs", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/xinetd", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/lock/subsys/xvfb", FilesystemIteratorRule.SKIP);

        List<HttpdServer> httpdServers = thisServer.getHttpdServers();
        for(HttpdServer hs : httpdServers) {
            filesystemRules.put("/var/log/httpd"+hs.getNumber()+"/ssl_scache.sem", FilesystemIteratorRule.SKIP);
        }
        filesystemRules.put("/var/oldaccounts/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/aoserv-daemon-java.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/aoserv-daemon.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/crond.pid", FilesystemIteratorRule.SKIP);
        for(HttpdServer hs : httpdServers) {
            filesystemRules.put("/var/run/httpd"+hs.getNumber()+".pid", FilesystemIteratorRule.SKIP);
        }
        filesystemRules.put("/var/run/identd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/klogd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/proftpd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/proftpd/proftpd.scoreboard", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/runlevel.dir", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/sendmail.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/sm-client.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/sshd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/syslogd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/xfs.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/xinetd.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/run/xvfb.pid", FilesystemIteratorRule.SKIP);
        filesystemRules.put(
            "/var/spool/",
            new FileExistsRule(
                new String[] {"/var/spool.aes128.img", "/var/spool.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        if(retention>1) filesystemRules.put("/var/spool/clientmqueue/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/spool/clientmqueue/sm-client.pid", FilesystemIteratorRule.SKIP);
        if(retention>1) filesystemRules.put("/var/spool/mqueue/", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/var/tmp/", FilesystemIteratorRule.SKIP);
        filesystemRules.put(
            "/www/",
            new FileExistsRule(
                new String[] {"/www.aes128.img", "/www.aes256.img"},
                FilesystemIteratorRule.SKIP,
                FilesystemIteratorRule.OK
            )
        );
        filesystemRules.put("/www/.journal", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/www/aquota.user", FilesystemIteratorRule.SKIP);
        filesystemRules.put("/www/lost+found", FilesystemIteratorRule.SKIP);
        // Do not replicate the backup directories
        for(BackupPartition bp : thisServer.getBackupPartitions()) {
            filesystemRules.put(bp.getPath()+'/', FilesystemIteratorRule.SKIP);
        }
        return filesystemRules;
    }

    @Override
    protected Map<String,FilesystemIteratorRule> getFilesystemIteratorPrefixRules(FailoverFileReplication ffr) throws IOException, SQLException {
        Map<String,FilesystemIteratorRule> filesystemPrefixRules=new HashMap<String,FilesystemIteratorRule>();
        filesystemPrefixRules.put("/var/lib/mysql/5.0/mysql-bin.", FilesystemIteratorRule.SKIP);
        filesystemPrefixRules.put("/var/lib/mysql/5.0/relay-log.", FilesystemIteratorRule.SKIP);
        filesystemPrefixRules.put("/var/lib/mysql/5.1/mysql-bin.", FilesystemIteratorRule.SKIP);
        filesystemPrefixRules.put("/var/lib/mysql/5.1/relay-log.", FilesystemIteratorRule.SKIP);
        return filesystemPrefixRules;
    }

    @Override
    public String getDefaultSourceIPAddress() throws IOException, SQLException {
        AOServer thisServer = AOServDaemon.getThisAOServer();
        // Next, it will use the daemon bind address
        String sourceIPAddress = thisServer.getDaemonBind().getIPAddress().getIPAddress();
        // If daemon is binding to wildcard, then use source IP address of primary IP
        if(sourceIPAddress.equals(IPAddress.WILDCARD_IP)) sourceIPAddress = thisServer.getPrimaryIPAddress().getIPAddress();
        return sourceIPAddress;
    }

    @Override
    public List<String> getReplicatedMySQLServers(FailoverFileReplication ffr) throws IOException, SQLException {
        synchronized(replicatedMySQLServerses) {
            return replicatedMySQLServerses.get(ffr);
        }
    }

    @Override
    public List<String> getReplicatedMySQLMinorVersions(FailoverFileReplication ffr) throws IOException, SQLException {
        synchronized(replicatedMySQLMinorVersionses) {
            return replicatedMySQLMinorVersionses.get(ffr);
        }
    }

    /**
     * Uses the random source from AOServDaemon.
     */
    @Override
    public Random getRandom() {
        return AOServDaemon.getRandom();
    }

    @Override
    public Logger getLogger() {
        return LogFactory.getLogger(AOServerEnvironment.class);
    }
}
