package com.aoindustries.aoserv.daemon.net.xinetd;

/*
 * Copyright 2003-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Handles the building of xinetd configs and files.
 *
 * TODO: RedHat ES 4 will always have authd launched by xinetd.
 */
public final class XinetdManager extends BuilderThread {

    /**
     * The type used for UNLISTED services.
     */
    public static final String UNLISTED="UNLISTED";

    private static XinetdManager xinetdManager;

    public static final File xinetdDirectory=new File("/etc/xinetd.d");

    private XinetdManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, XinetdManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, XinetdManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            final Stat tempStat = new Stat();

            synchronized(rebuildLock) {
                LinuxServerAccount interbaseUser=aoServer.getLinuxServerAccount(LinuxAccount.INTERBASE);
                LinuxServerAccount nobodyUser=aoServer.getLinuxServerAccount(LinuxAccount.NOBODY);
                LinuxServerAccount rootUser=aoServer.getLinuxServerAccount(LinuxAccount.ROOT);
                LinuxServerGroup ttyGroup=aoServer.getLinuxServerGroup(LinuxGroup.TTY);

                // Build a list of services that should be running
                List<Service> services=new ArrayList<Service>();
                List<NetBind> binds=aoServer.getNetBinds();
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
                        || protocol.equals(Protocol.IMAP2)
                        || protocol.equals(Protocol.INTERSERVER)
                        || protocol.equals(Protocol.NTALK)
                        || protocol.equals(Protocol.POP2)
                        || protocol.equals(Protocol.POP3)
                        || protocol.equals(Protocol.SIMAP)
                        || protocol.equals(Protocol.SPOP3)
                        || protocol.equals(Protocol.TALK)
                        || protocol.equals(Protocol.TELNET)
                    ) {
                        Service service;
                        if(redirect!=null) {
                            NetProtocol netProtocol=bind.getNetProtocol();
                            if(!netProtocol.getProtocol().equals(NetProtocol.TCP)) throw new SQLException("Only TCP ports may be redirected: (net_binds.pkey="+bind.getPKey()+").net_protocol="+netProtocol.getProtocol());

                            service=new Service(
                                UNLISTED,
                                -1,
                                redirect.getConnectionsPerSecond()+" "+redirect.getConnectionsPerSecondOverloadSleepTime(),
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
                                    "100 30",
                                    "REUSE",
                                    portMatches?"cvspserver":"cvspserver-unlisted",
                                    bind.getNetProtocol(),
                                    bind.getIPAddress(),
                                    portMatches?null:port,
                                    false,
                                    rootUser,
                                    null,
                                    "/usr/bin/cvs",
                                    server_args.toString(),
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    null,
                                    null
                                );
                            } else if(protocol.equals(Protocol.IMAP2)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
                                    "100 30",
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
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    null,
                                    null
                                );
                            } else if(protocol.equals(Protocol.INTERSERVER)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
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
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    "128M",
                                    null
                                );
                            } else if(protocol.equals(Protocol.NTALK)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
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
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    null,
                                    null
                                );
                            } else if(protocol.equals(Protocol.POP2)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
                                    "100 30",
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
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    null,
                                    null
                                );
                            } else if(protocol.equals(Protocol.POP3)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
                                    "100 30",
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
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    null,
                                    null
                                );
                            } else if(protocol.equals(Protocol.SIMAP)) {
                                if(
                                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                                ) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        "100 30",
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
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.SPOP3)) {
                                if(
                                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                                ) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        "100 30",
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
                                        "HOST DURATION",
                                        "HOST USERID",
                                        -1,
                                        null,
                                        null
                                    );
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            } else if(protocol.equals(Protocol.TALK)) {
                                service=new Service(
                                    portMatches?null:UNLISTED,
                                    -1,
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
                                    "HOST DURATION",
                                    "HOST USERID",
                                    -1,
                                    null,
                                    null
                                );
                            } else if(protocol.equals(Protocol.TELNET)) {
                                if(
                                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                                ) {
                                    service=new Service(
                                        portMatches?null:UNLISTED,
                                        -1,
                                        "100 30",
                                        "REUSE",
                                        portMatches?"telnet":"telnet-unlisted",
                                        bind.getNetProtocol(),
                                        bind.getIPAddress(),
                                        portMatches?null:port,
                                        false,
                                        rootUser,
                                        null,
                                        "/usr/sbin/telnetd",
                                        "-a none",
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
                List<String> filenames=new SortedArrayList<String>();
                final int numServices=services.size();
                for(int c=0;c<numServices;c++) {
                    Service service=services.get(c);
                    String desiredFilename=service.getService();
                    String filename=null;
                    for(int d=1;d<Integer.MAX_VALUE;d++) {
                        String checkFilename=d==1?desiredFilename:(desiredFilename+"-"+d);
                        if(!filenames.contains(checkFilename)) {
                            filename=checkFilename;
                            break;
                        }
                    }
                    if(filename==null) throw new IOException("Unable to find available filename for service: "+desiredFilename);
                    filenames.add(filename);

                    UnixFile newUF=new UnixFile(xinetdDirectory, filename+".new");
                    ChainWriter out=new ChainWriter(new FileOutputStream(newUF.getFile()));
		    try {
			newUF.setMode(0600);
			service.printXinetdConfig(out);
		    } finally {
			out.flush();
			out.close();
		    }
                    
                    // Move into place if different than existing
                    UnixFile existingUF=new UnixFile(xinetdDirectory, filename);
                    if(existingUF.getStat(tempStat).exists() && newUF.contentEquals(existingUF)) newUF.delete();
                    else {
                        newUF.renameTo(existingUF);
                        needsReloaded=true;
                    }
                }

                // Cleanup extra configs
                String[] list=xinetdDirectory.list();
                if(list!=null) {
                    for(int c=0;c<list.length;c++) {
                        String filename=list[c];
                        if(!filenames.contains(filename)) {
                            new UnixFile(xinetdDirectory, filename).delete();
                            needsReloaded=true;
                        }
                    }
                }

                UnixFile rcFile=new UnixFile("/etc/rc.d/rc3.d/S56xinetd");
                if(numServices==0) {
                    // Turn off xinetd completely if not already off
                    if(rcFile.getStat(tempStat).exists()) {
                        AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/xinetd", "stop"});
                        AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--del", "xinetd"});
                    }
                } else {
                    // Turn on xinetd if not already on
                    if(!rcFile.getStat(tempStat).exists()) {
                        AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--add", "xinetd"});
                    }

                    // Restart xinetd if modified
                    if(needsReloaded) {
                        try {
                            AOServDaemon.exec(
                                new String[] {
                                    "/etc/rc.d/init.d/xinetd",
                                    "stop"
                                }
                            );
                        } catch(IOException err) {
                            AOServDaemon.reportError(err, null);
                        }
                        try {
                            Thread.sleep(1000);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                        try {
                            AOServDaemon.exec(
                                new String[] {
                                    "/etc/rc.d/init.d/xinetd",
                                    "start"
                                }
                            );
                        } catch(IOException err) {
                            AOServDaemon.reportError(err, null);
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, XinetdManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(XinetdManager.class) && xinetdManager==null) {
                synchronized(System.out) {
                    if(xinetdManager==null) {
                        System.out.print("Starting XinetdManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        xinetdManager=new XinetdManager();
                        conn.cvsRepositories.addTableListener(xinetdManager, 0);
                        conn.netBinds.addTableListener(xinetdManager, 0);
                        conn.netTcpRedirects.addTableListener(xinetdManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, XinetdManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild xinetd Configuration";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
