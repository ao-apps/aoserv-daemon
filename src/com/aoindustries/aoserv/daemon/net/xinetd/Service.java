package com.aoindustries.aoserv.daemon.net.xinetd;

/*
 * Copyright 2003-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetPort;
import com.aoindustries.aoserv.client.NetProtocol;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.util.StringUtility;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Represents one service in the xinetd.d directory.
 */
public final class Service {

    final private String type;
    final private int instances;
    final private int per_source;
    final private String cps;
    final private String banner_fail;
    final private String flags;
    final private String service;
    final private NetProtocol socket_type;
    final private IPAddress bind;
    final private NetPort port;
    final private boolean wait;
    final private LinuxServerAccount user;
    final private LinuxServerGroup group;
    final private String server;
    final private String env;
    final private String server_args;
    final private String log_on_success;
    final private String log_on_failure;
    final private int nice;
    final private String rlimit_as;
    final private String redirect;

    public Service(
        String type,
        int instances,
        int per_source,
        String cps,
        String banner_fail,
        String flags,
        String service,
        NetProtocol socket_type,
        IPAddress bind,
        NetPort port,
        boolean wait,
        LinuxServerAccount user,
        LinuxServerGroup group,
        String server,
        String env,
        String server_args,
        String log_on_success,
        String log_on_failure,
        int nice,
        String rlimit_as,
        String redirect
    ) throws SQLException {
        this.type=type;
        this.instances=instances;
        this.per_source = per_source;
        this.cps=cps;
        this.banner_fail = banner_fail;
        this.flags=flags;
        this.service=service;
        this.socket_type=socket_type;
        this.bind=bind;
        this.port=port;
        this.wait=wait;
        this.user=user;
        this.group=group;
        this.server=server;
        this.env = env;
        this.server_args=server_args;
        this.log_on_success=log_on_success;
        this.log_on_failure=log_on_failure;
        this.nice=nice;
        this.rlimit_as=rlimit_as;
        this.redirect=redirect;

        if(redirect!=null && (server!=null || env!=null || server_args!=null)) throw new SQLException("Unable to provide server, env, or server_args when a redirect is requested");
    }
    
    public String getService() {
        return service;
    }

    public String getRedirect() {
        return redirect;
    }

    public void printXinetdConfig(ChainWriter out) throws IOException, SQLException {
        out.print("service ").print(service).print("\n"
                + "{\n");
        if(type!=null) out.print("\ttype = ").print(type).print('\n');

        out.print("\tinstances = ");
        if(instances==-1) out.print("UNLIMITED");
        else out.print(instances);
        out.print('\n');

        out.print("\tper_source = ");
        if(per_source==-1) out.print("UNLIMITED");
        else out.print(per_source);
        out.print('\n');

        if(cps!=null) out.print("\tcps = ").print(cps).print('\n');

        if(banner_fail!=null) out.print("\tbanner_fail = ").print(banner_fail).print('\n');

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

        if(env!=null) out.print("\tenv = ").print(env).print('\n');

        if(server_args!=null) out.print("\tserver_args = ").print(server_args).print('\n');

        if(redirect!=null) out.print("\tredirect = ").print(redirect).print('\n');

        if(log_on_success!=null) out.print("\tlog_on_success = ").print(log_on_success).print('\n');

        if(log_on_failure!=null) out.print("\tlog_on_failure = ").print(log_on_failure).print('\n');

        if(nice!=-1) out.print("\tnice = ").print(nice).print('\n');

        if(rlimit_as!=null) out.print("\trlimit_as = ").print(rlimit_as).print('\n');

        out.print("\tdisable = no\n"
                + "}\n");
    }
    
    public boolean bindMatches(Service other) {
        return
            bind.equals(other.bind)
            && socket_type.equals(other.socket_type)
            && service.equals(other.service)
            && StringUtility.equals(port, other.port)
        ;
    }
}
