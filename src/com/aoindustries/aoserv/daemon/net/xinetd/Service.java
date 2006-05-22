package com.aoindustries.aoserv.daemon.net.xinetd;

/*
 * Copyright 2003-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.io.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;

/**
 * Represents one service in the xinetd.d directory.
 */
public final class Service {

    final private String type;
    final private int instances;
    final private String cps;
    final private String flags;
    final private String service;
    final private NetProtocol socket_type;
    final private IPAddress bind;
    final private NetPort port;
    final private boolean wait;
    final private LinuxServerAccount user;
    final private LinuxServerGroup group;
    final private String server;
    final private String server_args;
    final private String log_on_success;
    final private String log_on_failure;
    final private int nice;
    final private String rlimit_as;
    final private String redirect;

    public Service(
        String type,
        int instances,
        String cps,
        String flags,
        String service,
        NetProtocol socket_type,
        IPAddress bind,
        NetPort port,
        boolean wait,
        LinuxServerAccount user,
        LinuxServerGroup group,
        String server,
        String server_args,
        String log_on_success,
        String log_on_failure,
        int nice,
        String rlimit_as,
        String redirect
    ) throws SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, Service.class, "<init>(String,int,String,String,String,String,NetProtocol,IPAddress,NetPort,boolean,LinuxServerAccount,LinuxServerGroup,String,String,String,String,int,String,String)", null);
        try {
            this.type=type;
            this.instances=instances;
            this.cps=cps;
            this.flags=flags;
            this.service=service;
            this.socket_type=socket_type;
            this.bind=bind;
            this.port=port;
            this.wait=wait;
            this.user=user;
            this.group=group;
            this.server=server;
            this.server_args=server_args;
            this.log_on_success=log_on_success;
            this.log_on_failure=log_on_failure;
            this.nice=nice;
            this.rlimit_as=rlimit_as;
            this.redirect=redirect;

            if(redirect!=null && (server!=null || server_args!=null)) throw new SQLException("Unable to provide server or server_args when a redirect is requested");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public String getService() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, Service.class, "getService()", null);
        try {
           return service;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getRedirect() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, Service.class, "getRedirect()", null);
        try {
           return redirect;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public void printXinetdConfig(ChainWriter out) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, Service.class, "printXinetdConfig(ChainWriter)", null);
        try {
            out.print("service ").print(service).print("\n"
                    + "{\n");
            if(type!=null) out.print("\ttype = ").print(type).print('\n');
            out.print("\tinstances = ");
            if(instances==-1) out.print("UNLIMITED");
            else out.print(instances);
            out.print('\n');

            if(cps!=null) out.print("\tcps = ").print(cps).print('\n');

            if(flags!=null) out.print("\tflags = ").print(flags).print('\n');

            out.print("\tsocket_type = ");
            String protocol=socket_type.getProtocol();
            if(protocol.equals(NetProtocol.TCP)) out.print("stream");
            else if(protocol.equals(NetProtocol.UDP)) out.print("dgram");
            else throw new SQLException("Unknown value for socket_type: "+protocol);
            out.print('\n');

            if(bind!=null) {
                String ip=bind.getIPAddress();
                if(!ip.equals(IPAddress.WILDCARD_IP)) {
                    out.print("\tbind = ").print(ip).print('\n');
                }
            }
            if(port!=null) out.print("\tport = ").print(port.getPort()).print('\n');

            out.print("\twait = ").print(wait?"yes":"no").print('\n');

            out.print("\tuser = ").print(user.getLinuxAccount().getUsername().getUsername()).print('\n');

            if(group!=null) out.print("\tgroup = ").print(group.getLinuxGroup().getName()).print('\n');

            if(server!=null) out.print("\tserver = ").print(server).print('\n');

            if(server_args!=null) out.print("\tserver_args = ").print(server_args).print('\n');

            if(redirect!=null) out.print("\tredirect = ").print(redirect).print('\n');

            if(log_on_success!=null) out.print("\tlog_on_success = ").print(log_on_success).print('\n');

            if(log_on_failure!=null) out.print("\tlog_on_failure = ").print(log_on_failure).print('\n');

            if(nice!=-1) out.print("\tnice = ").print(nice).print('\n');

            if(rlimit_as!=null) out.print("\trlimit_as = ").print(rlimit_as).print('\n');

            out.print("\tdisable = no\n"
                    + "}\n");
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    
    public boolean bindMatches(Service other) {
        Profiler.startProfile(Profiler.FAST, Service.class, "bindMatches(Service)", null);
        try {
            return
                bind.equals(other.bind)
                && socket_type.equals(other.socket_type)
                && service.equals(other.service)
                && StringUtility.equals(port, other.port)
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}
