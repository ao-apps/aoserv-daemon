package com.aoindustries.aoserv.daemon.net.xinetd;

/*
 * Copyright 2003-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.CvsRepository;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetPort;
import com.aoindustries.aoserv.client.NetProtocol;
import com.aoindustries.aoserv.client.NetTcpRedirect;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of xinetd configs and files.
 */
public final class XinetdManager extends BuilderThread {

    /**
     * The type used for UNLISTED services.
     */
    public static final String UNLISTED="UNLISTED";

    private static XinetdManager xinetdManager;

    public static final File xinetdDirectory=new File("/etc/xinetd.d");

    private XinetdManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            // Reused on inner loops
            final Stat tempStat = new Stat();
            final ByteArrayOutputStream bout = new ByteArrayOutputStream();

            synchronized(rebuildLock) {
                LinuxServerAccount interbaseUser=aoServer.getLinuxServerAccount(LinuxAccount.INTERBASE);
                LinuxServerAccount nobodyUser=aoServer.getLinuxServerAccount(LinuxAccount.NOBODY);
                LinuxServerAccount rootUser=aoServer.getLinuxServerAccount(LinuxAccount.ROOT);
                LinuxServerGroup ttyGroup=aoServer.getLinuxServerGroup(LinuxGroup.TTY);

                // Build a list of services that should be running
                List<NetBind> binds=aoServer.getServer().getNetBinds();
                List<Service> services=new ArrayList<Service>(binds.size()+(ImapManager.WUIMAP_CONVERSION_ENABLED ? 1 : 0)); // Worst-case all binds are in xinetd

                if(ImapManager.WUIMAP_CONVERSION_ENABLED) {
                    // Remove once conversion to CentOS has been completed
                    services.add(
                        new Service(
                            UNLISTED,
                            -1,
                            -1,
                            null,
                            null,
                            null,
                            "wuimap",
                            connector.getNetProtocols().get(NetProtocol.TCP),
                            aoServer.getPrimaryIPAddress(),
                            connector.getNetPorts().get(8143),
                            false,
                            rootUser,
                            null,
                            "/opt/imap-2007d/bin/imapd",
                            null,
                            null,
                            "HOST DURATION",
                            "HOST USERID",
                            -1,
                            null,
                            null
                        )
                    );
                }

                for(int c=0;c<binds.size();c++) {
                    NetBind bind=binds.get(c);
                    NetPort port=bind.getPort();
                    NetTcpRedirect redirect=bind.getNetTcpRedirect();
                    Protocol protocolObj=bind.getAppProtocol();
                    String protocol=protocolObj.getProtocol();
                    if(
                        redirect!=null
                        //|| protocol.equals(Protocol.AUTH)
                        || protocol.equals(Protocol.CVSPSERVER)
                        || protocol.equals(Protocol.INTERSERVER)
                        || protocol.equals(Protocol.NTALK)
                        || protocol.equals(Protocol.TALK)
                        || protocol.equals(Protocol.TELNET)
                        || (
                            // POP and IMAP is handled through xinetd on Mandriva 2006.0
                            osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                            && (
                                //protocol.equals(Protocol.POP2)
                                protocol.equals(Protocol.POP3)
                                || protocol.equals(Protocol.SIMAP)
                                || protocol.equals(Protocol.SPOP3)
                                || protocol.equals(Protocol.IMAP2)
                            )
                        ) || (
                            // FTP is handled through xinetd on CentOS 5
                            osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                            && protocol.equals(Protocol.FTP)
                        )
                    ) {
                        Service service;
                        if(redirect!=null) {
                            NetProtocol netProtocol=bind.getNetProtocol();
                            if(!netProtocol.getProtocol().equals(NetProtocol.TCP)) throw new SQLException("Only TCP ports may be redirected: (net_binds.pkey="+bind.getPkey()+").net_protocol="+netProtocol.getProtocol());

                            service=new Service(
                                UNLISTED,
                                -1,
                                -1,
                                redirect.getConnectionsPerSecond()+" "+redirect.getConnectionsPerSecondOverloadSleepTime(),
                                null,
                                null,
                                "redirect",
                                netProtocol,
                                bind.getIPAddress(),
                                port,
                                false,
                                rootUser,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                -1,
                                null,
                                redirect.getDestinationHost()+" "+redirect.getDestinationPort()
                            );
                        } else {
                            boolean portMatches=protocolObj.getPort(connector).equals(port);
                            /*if(protocol.equals(Protocol.AUTH)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
                                    null,
                                    null,
                                    portMatches?"auth":"auth-unlisted",
                                    bind.getNetProtocol(),
                                    bind.getIPAddress(),
                                    portMatches?null:port,
                                    true,
                                    rootUser,
                                    null,
                                    "/usr/sbin/in.identd",
                                    "-w -e",
                                    null,
                                    null,
                                    -1,
                                    null,
                                    null
                                );
                            } else */if(protocol.equals(Protocol.CVSPSERVER)) {
                                List<CvsRepository> repos=aoServer.getCvsRepositories();
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    StringBuilder server_args=new StringBuilder();
                                    for(int d=0;d<repos.size();d++) {
                                        CvsRepository repo=repos.get(d);
                                        if(d>0) server_args.append(' ');
                                        server_args.append("--allow-root=").append(repo.getPath());
                                    }
                                    if(!repos.isEmpty()) server_args.append(' ');
                                    server_args.append("-f pserver");
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        "REUSE",
                                        portMatches?"cvspserver":"cvspserver-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/bin/cvs",
                                        null,
                                        server_args.toString(),
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                    StringBuilder server_args=new StringBuilder();
                                    server_args.append("-f");
                                    for(CvsRepository repo : repos) {
                                        server_args.append(" --allow-root=").append(repo.getPath());
                                    }
                                    server_args.append(" pserver");
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        "REUSE",
                                        portMatches?"cvspserver":"cvspserver-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/bin/cvs",
                                        "HOME=/var/cvs",
                                        server_args.toString(),
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.FTP)) {
                                if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        100,
                                        20, // Was 5, but confsys03 on www7.fc.aoindustries.com hit this limit on 2009-07-31
                                        "100 30",
                                        "/etc/vsftpd/busy_banner",
                                        "IPv4",
                                        portMatches?"ftp":"ftp-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/vsftpd",
                                        null,
                                        "/etc/vsftpd/vhosts/vsftpd_"+bind.getIPAddress().getIPAddress()+"_"+port.getPort()+".conf",
                                        "PID HOST DURATION",
                                        "HOST",
                                        10,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.IMAP2)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        null,
                                        portMatches?"imap":"imap-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/imapd",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.INTERSERVER)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        null,
                                        null,
                                        null,
                                        portMatches?"interserver":"interserver-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        interbaseUser,
                                        null,
                                        "/usr/interclient/2.0/bin/interserver",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        "128M",
                                        null
                                    );
                                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        null,
                                        null,
                                        null,
                                        portMatches?"interserver":"interserver-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        interbaseUser,
                                        null,
                                        "/opt/interclient-2.0/bin/interserver",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        "128M",
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.NTALK)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        null,
                                        null,
                                        null,
                                        portMatches?"ntalk":"ntalk-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        true,
                                        nobodyUser,
                                        ttyGroup,
                                        "/usr/sbin/in.ntalkd",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1, // instances
                                        -1, // per_source
                                        null, // cps
                                        null, // banner_fail
                                        "IPv4", // flags
                                        portMatches?"ntalk":"ntalk-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        true,
                                        nobodyUser,
                                        ttyGroup,
                                        "/usr/sbin/in.ntalkd",
                                        null, // env
                                        null, // server_args
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            /*} else if(protocol.equals(Protocol.POP2)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        null,
                                        portMatches?"pop2":"pop2-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/ipop2d",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);*/
                            } else if(protocol.equals(Protocol.POP3)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        null,
                                        portMatches?"pop3":"pop3-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/ipop3d",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.SIMAP)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        null,
                                        portMatches?"imaps":"imaps-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/imapsd",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.SPOP3)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        null,
                                        portMatches?"pop3s":"pop3s-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/ipop3sd",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.TALK)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        null,
                                        null,
                                        null,
                                        portMatches?"talk":"talk-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        true,
                                        nobodyUser,
                                        ttyGroup,
                                        "/usr/sbin/in.talkd",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1, // instances
                                        -1, // per_source
                                        null, // cps
                                        null, // banner_fail
                                        "IPv4", // flags
                                        portMatches?"talk":"talk-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        true,
                                        nobodyUser,
                                        ttyGroup,
                                        "/usr/sbin/in.talkd",
                                        null, // env
                                        null, // server_args
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.TELNET)) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        "REUSE",
                                        portMatches?"telnet":"telnet-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/telnetd",
                                        null,
                                        "-a none",
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        -1,
                                        "100 30",
                                        null,
                                        "REUSE",
                                        portMatches?"telnet":"telnet-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/in.telnetd",
                                        null,
                                        null,
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else throw new RuntimeException("Unexpected protocol: "+protocol);
                        }

                        // Do not add if is a duplicate ip address, net protocol, and port
                        boolean foundDup=false;
                        for(Service other : services) {
                            if(service.bindMatches(other)) {
                                foundDup=true;
                                break;
                            }
                        }
                        if(!foundDup) services.add(service);
                    }
                }

                boolean needsReloaded=false;

                // (Re)build configs to match service list
                Set<String> filenames = new HashSet<String>();
                final int numServices=services.size();
                for(int c=0; c<numServices; c++) {
                    Service service = services.get(c);
                    String desiredFilename = service.getService();
                    String filename = null;
                    for(int d=1; d<Integer.MAX_VALUE; d++) {
                        String checkFilename = d==1 ? desiredFilename : (desiredFilename+"-"+d);
                        if(!filenames.contains(checkFilename)) {
                            filename = checkFilename;
                            break;
                        }
                    }
                    if(filename==null) throw new IOException("Unable to find available filename for service: "+desiredFilename);
                    filenames.add(filename);

                    // Build to RAM first
                    bout.reset();
                    ChainWriter out = new ChainWriter(bout);
                    try {
                        service.printXinetdConfig(out);
                    } finally {
                        out.close();
                    }
                    byte[] newBytes = bout.toByteArray();

                    // Move into place if different than existing
                    UnixFile existingUF=new UnixFile(xinetdDirectory, filename);
                    if(
                        !existingUF.getStat(tempStat).exists()
                        || !existingUF.contentEquals(newBytes)
                    ) {
                        UnixFile newUF = new UnixFile(xinetdDirectory, filename+".new");
                        // SecureOutputStream not required because /etc/xinetd.d is a secure directory already.
                        OutputStream newOut = new FileOutputStream(newUF.getFile());
                        try {
                            newUF.setMode(0600);
                            newOut.write(newBytes);
                        } finally {
                            newOut.close();
                        }
                        newUF.renameTo(existingUF);
                        needsReloaded = true;
                    }
                }

                // Cleanup extra configs
                String[] list = xinetdDirectory.list();
                if(list!=null) {
                    for(int c=0;c<list.length;c++) {
                        String filename=list[c];
                        if(!filenames.contains(filename)) {
                            new UnixFile(xinetdDirectory, filename).delete();
                            needsReloaded=true;
                        }
                    }
                }

                // Control service
                UnixFile rcFile=new UnixFile("/etc/rc.d/rc3.d/S56xinetd");
                if(numServices==0) {
                    // Turn off xinetd completely if not already off
                    if(rcFile.getStat(tempStat).exists()) {
                        // Stop service
                        AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/xinetd", "stop"});
                        // Disable with chkconfig
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--del", "xinetd"});
                        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                            AOServDaemon.exec(new String[] {"/sbin/chkconfig", "xinetd", "off"});
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                } else {
                    // Turn on xinetd if not already on
                    if(!rcFile.getStat(tempStat).exists()) {
                        // Enable with chkconfig
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--add", "xinetd"});
                        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                            AOServDaemon.exec(new String[] {"/sbin/chkconfig", "xinetd", "on"});
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        // Start service
                        AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/xinetd", "start"});
                    } else {
                        // Reload xinetd if modified
                        if(needsReloaded) {
                            // Try reload config first
                            /* reload has several limitations documented in the man page for xinetd.conf, will always stop/start instead
                            try {
                                AOServDaemon.exec(
                                    new String[] {
                                        "/etc/rc.d/init.d/xinetd",
                                        "reload"
                                    }
                                );
                            } catch(IOException err) {
                                LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err);*/

                                // Try more forceful stop/start
                                try {
                                    AOServDaemon.exec(
                                        new String[] {
                                            "/etc/rc.d/init.d/xinetd",
                                            "stop"
                                        }
                                    );
                                } catch(IOException err2) {
                                    LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err2);
                                }
                                try {
                                    Thread.sleep(1000);
                                } catch(InterruptedException err2) {
                                    LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err2);
                                }
                                AOServDaemon.exec(
                                    new String[] {
                                        "/etc/rc.d/init.d/xinetd",
                                        "start"
                                    }
                                );
                            //}
                        }
                    }
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(XinetdManager.class).log(Level.SEVERE, null, T);
            return false;
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
                && AOServDaemonConfiguration.isManagerEnabled(XinetdManager.class)
                && xinetdManager==null
            ) {
                System.out.print("Starting XinetdManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                xinetdManager=new XinetdManager();
                conn.getCvsRepositories().addTableListener(xinetdManager, 0);
                conn.getNetBinds().addTableListener(xinetdManager, 0);
                conn.getNetTcpRedirects().addTableListener(xinetdManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild xinetd Configuration";
    }
}
